package com.echonion.nion.ui.task

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echonion.nion.core
import com.echonion.nion.dataEvents
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.ReminderScheduler
import com.echonion.nion.reminder.ReminderStore
import com.echonion.nion.ui.companion.tools.DataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData
import uniffi.nion_core.OverdueDailyTask

@Stable
data class TaskItem(
    val id: String,
    val name: String,
    val description: String?,
    val priority: String,
    val isDone: Boolean,
    val createdAt: String,
    val subtasks: List<TaskItem> = emptyList(),
    /** 该任务累计专注秒数，来自 Rust 端 focus_seconds 字段 */
    val focusSeconds: Long = 0,
    /** 循环规则：null 或 "none" 表示不循环，"daily" 表示每日循环 */
    val recurrenceRule: String? = null,
    /** 每日循环的提醒时间，格式 "HH:MM"，仅当 recurrenceRule="daily" 时有效 */
    val recurrenceReminderTime: String? = null,
    /** 一次性提醒时间，格式 "YYYY-MM-DDTHH:MM"，null 表示未设置 */
    val reminder: String? = null,
    /** 是否为每日循环任务（recurrenceRule == "daily"） */
    val isDaily: Boolean = false,
    /** 当前查看日期的完成状态。每日任务看 completions 表，普通任务看 status */
    val isCompletedForDate: Boolean = false,
)

@Stable
data class ChecklistItem(
    val id: String,
    val name: String,
)

/**
 * 分组 UI 模型 —— 对应 Rust 端的 GroupData
 * 用于在清单下展示二级分类（如"语文"、"英语"）
 */
@Stable
data class GroupItem(
    val id: String,
    val name: String,
    val checklistId: String,
    val color: String?,
)

@Stable
data class FlatTaskItem(
    val task: TaskItem,
    val depth: Int,
    val parentId: String?,
    val isGroupFirst: Boolean,
    val isGroupLast: Boolean,
)

@OptIn(FlowPreview::class)
class TaskViewModel(
    private val core: NionCore,
    private val onError: (String) -> Unit,
    private val app: android.app.Application,
) : ViewModel() {

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    /**
     * 当前活跃清单 ID。
     * "today" = "今天"跨清单聚合视图；
     * "inbox" = "收集箱"孤儿任务视图（category_id = null 的任务）；
     * 其他 = 真实清单 ID。
     */
    var activeChecklistId by mutableStateOf<String?>(TODAY_ID)
        private set

    /** 当前清单下的分组列表 */
    var groups by mutableStateOf<List<GroupItem>>(emptyList())
        private set

    /**
     * 当前选中的分组 ID。
     * null 表示显示全部任务（不按分组过滤）
     */
    var activeGroupId by mutableStateOf<String?>(null)
        private set

    var checklistCounts by mutableStateOf<Map<String?, Pair<Int, Int>>>(emptyMap())
        private set

    val todoTasks: List<TaskItem> by derivedStateOf { tasks.filter { !it.isDone } }
    val doneTasks: List<TaskItem> by derivedStateOf { tasks.filter { it.isDone } }
    /**
     * 扁平化已完成任务 —— 保持母子分组关系。
     * 已完成父任务 = 组根；已完成子任务 = 组的成员，通过 depth / isGroupFirst / isGroupLast 渲染连接卡片。
     */
    val flatDoneTasks: List<FlatTaskItem> by derivedStateOf {
        val result = mutableListOf<FlatTaskItem>()
        addDoneGroups(tasks, baseDepth = 0, result)
        result
    }
    val todoCount: Int by derivedStateOf { flatTodoTasks.size }
    val doneCount: Int by derivedStateOf { flatDoneTasks.size }

    /** 扁平化待办任务 —— 遍历全量 tasks，flattenTodoGroup 自行处理已完成父任务下被单独取消的子任务 */
    val flatTodoTasks: List<FlatTaskItem> by derivedStateOf { flattenWithGroupInfo(tasks) }

    val activeChecklistName: String
        get() = when (activeChecklistId) {
            TODAY_ID -> "今天"
            INBOX_ID -> "收集箱"
            null -> "我的任务"
            else -> checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"
        }

    private var countsJob: Job? = null

    var selectedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 过期的每日任务列表（所有历史未完成的每日任务），仅在"今天"视图中加载 */
    var overdueDailyTasks by mutableStateOf<List<OverdueDailyTask>>(emptyList())
        private set

    val isSelectionMode: Boolean by derivedStateOf { selectedTaskIds.isNotEmpty() }

    /** 当前正在执行的刷新协程，用于 cancelPrevious 防止并发刷新导致旧数据覆盖新数据 */
    private var refreshJob: Job? = null

    init {
        // 初始加载：在协程中执行
        refreshJob = viewModelScope.launch { doRefreshInternal() }

        // 监听 Agent 工具执行后的数据变更事件，自动刷新任务列表
        // debounce(300)：AI 连续调用 N 个工具时，合并为一次刷新（300ms 内的事件只触发最后一次）
        // cancelPrevious：确保同一时刻只有一个刷新协程在运行，避免旧数据覆盖新数据
        viewModelScope.launch {
            app.dataEvents()
                .debounce(300)
                .collect { event ->
                    // 只处理任务数据变更事件，忽略偏好/记忆等不相关事件
                    if (DataType.TASK_DATA in event.types) {
                        Log.d("TaskViewModel", "收到数据变更事件: ${event.types}")
                        refreshJob?.cancel()
                        refreshJob = viewModelScope.launch { doRefreshInternal() }
                    }
                }
        }
    }

    fun toggleSelection(taskId: String) {
        selectedTaskIds = if (taskId in selectedTaskIds) {
            selectedTaskIds - taskId
        } else {
            selectedTaskIds + taskId
        }
    }

    fun clearSelection() {
        selectedTaskIds = emptySet()
    }

    fun deleteSelectedTasks() {
        val ids = selectedTaskIds
        if (ids.isEmpty()) return
        val snapshot = tasks
        tasks = tasks.filter { it.id !in ids }
            .map { it.copy(subtasks = it.subtasks.filter { sub -> sub.id !in ids }) }
        selectedTaskIds = emptySet()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (id in ids) { core.deleteTask(id) }
                }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("批量删除失败: ${e.message}")
                tasks = snapshot
            }
        }
    }

    private fun scheduleRefreshCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            delay(300)
            refreshCounts()
        }
    }

    /**
     * 手动触发刷新（供 UI 操作调用，如切换清单、创建任务后）。
     * 不走防抖，立即执行，同时取消正在进行的旧刷新。
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { doRefreshInternal() }
    }

    /**
     * 内部刷新实现 —— 从 Rust 层重新加载清单、分组、任务数据。
     * 所有刷新入口（init debounce、手动 refresh）最终都汇聚到这里，保证串行执行。
     */
    private suspend fun doRefreshInternal() {
        try {
            val loaded = withContext(Dispatchers.IO) {
                core.getChecklists().map { it.toUi() }
            }
            checklists = loaded
        } catch (e: Exception) {
            onError("加载清单失败: ${e.message}")
        }
        try {
            // 刷新当前清单下的分组（"今天"、"收集箱"视图无分组，只有真实清单才有分组）
            groups = if (activeChecklistId != null && activeChecklistId != TODAY_ID && activeChecklistId != INBOX_ID) {
                withContext(Dispatchers.IO) {
                    core.getGroupsByChecklist(activeChecklistId!!).map { it.toUi() }
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) { }
        try {
            val loadedTasks = withContext(Dispatchers.IO) {
                // "今天"视图使用跨清单聚合查询
                if (activeChecklistId == TODAY_ID) {
                    loadTodayTasks()
                } else if (activeChecklistId == INBOX_ID) {
                    // "收集箱"视图加载所有 category_id = null 的孤儿任务
                    loadTasksWithSubtasks(null, activeGroupId)
                } else {
                    loadTasksWithSubtasks(activeChecklistId, activeGroupId)
                }
            }
            tasks = loadedTasks
            // "今天"视图同时加载过期每日任务
            if (activeChecklistId == TODAY_ID) {
                withContext(Dispatchers.IO) { loadOverdueDailyTasks() }
            }
        } catch (e: Exception) {
            onError("加载任务失败: ${e.message}")
        }
        refreshCounts()
    }

    /**
     * 切换活跃清单，同时加载该清单下的分组列表，并重置分组筛选。
     * groupId = null 表示显示该清单下所有任务。
     * id = "today" 切换至"今天"视图（跨清单聚合今日任务）。
     * id = "inbox" 切换至"收集箱"视图（未分配清单的孤儿任务）。
     * id = null 切换至"我的任务"（未分配清单的孤儿任务，与 inbox 相同）。
     */
    fun setActiveChecklist(id: String?) {
        viewModelScope.launch {
            try {
                // 切换清单时重置分组选择
                activeGroupId = null
                activeChecklistId = id
                // "今天"和"收集箱"视图不加载分组，真实清单才加载
                groups = if (id != null && id != TODAY_ID && id != INBOX_ID) {
                    withContext(Dispatchers.IO) {
                        core.getGroupsByChecklist(id).map { it.toUi() }
                    }
                } else {
                    emptyList()
                }
                val loadedTasks = withContext(Dispatchers.IO) {
                    if (id == TODAY_ID) {
                        loadTodayTasks()
                    } else if (id == INBOX_ID) {
                        // "收集箱"视图加载所有 category_id = null 的孤儿任务
                        loadTasksWithSubtasks(null, null)
                    } else {
                        loadTasksWithSubtasks(id, null)
                    }
                }
                tasks = loadedTasks
                // "今天"视图同时加载过期每日任务
                if (id == TODAY_ID) {
                    withContext(Dispatchers.IO) { loadOverdueDailyTasks() }
                } else {
                    // 非"今天"视图清空过期列表
                    overdueDailyTasks = emptyList()
                }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("切换清单失败: ${e.message}")
            }
        }
    }

    /**
     * 切换活跃分组，筛选显示该分组下的任务。
     * groupId = null 表示显示全部（不按分组过滤）。
     */
    fun setActiveGroup(groupId: String?) {
        viewModelScope.launch {
            try {
                activeGroupId = groupId
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(activeChecklistId, groupId)
                }
                tasks = loadedTasks
            } catch (e: Exception) {
                onError("切换分组失败: ${e.message}")
            }
        }
    }

    /**
     * 创建任务。自动关联到当前活跃清单和活跃分组。
     * 在"今天"或"收集箱"视图中创建时，category_id 为 null（不属于任何清单），
     * 任务会自动出现在"收集箱"中。
     *
     * @param name 任务名称
     * @param description 任务描述，可为 null
     * @param priority 优先级："high" / "medium" / "low"
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param recurrenceReminderTime 每日循环提醒时间，格式 "HH:MM"
     * @param onCreated 任务创建成功后的回调，传入新任务 ID
     * @return 新创建的任务 ID，失败时返回 null
     */
    fun createTask(
        name: String,
        description: String?,
        priority: String,
        recurrenceRule: String? = null,
        recurrenceReminderTime: String? = null,
        onCreated: ((String) -> Unit) = {},
    ) {
        viewModelScope.launch {
            try {
                // "今天"和"收集箱"不是真实清单，创建任务时 category_id 设为 null
                val realCategoryId = when (activeChecklistId) {
                    TODAY_ID, INBOX_ID -> null
                    else -> activeChecklistId
                }
                val newTask = withContext(Dispatchers.IO) {
                    core.createTask(name, description, priority, realCategoryId, null, activeGroupId, recurrenceRule, recurrenceReminderTime)
                }
                // 创建成功后调度提醒闹钟
                scheduleReminderIfNeeded(newTask)
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
                onCreated(newTask.id)
            } catch (e: Exception) {
                onError("创建任务失败: ${e.message}")
            }
        }
    }

    /**
     * 创建子任务。继承父任务所在的清单和分组。
     */
    fun createSubtask(parentId: String, name: String, priority: String = "medium") {
        viewModelScope.launch {
            try {
                // 子任务继承父任务的分组归属
                val parentGroup = activeGroupId
                // "今天"和"收集箱"不是真实清单，子任务 category_id 设为 null
                val realCategoryId = when (activeChecklistId) {
                    TODAY_ID, INBOX_ID -> null
                    else -> activeChecklistId
                }
                withContext(Dispatchers.IO) {
                    core.createTask(name, null, priority, realCategoryId, parentId, parentGroup, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建子任务失败: ${e.message}")
            }
        }
    }

    /**
     * 切换任务完成状态。
     * 每日任务：使用实例化 API（status 变 done + 自动创建/删除明天实例）
     * 普通任务：改 tasks.status 为 "done"/"todo"
     */
    fun toggleDone(task: TaskItem) {
        if (task.isDaily) {
            toggleDailyDone(task)
        } else {
            toggleNormalDone(task)
        }
    }

    /**
     * 完成或取消完成过期每日任务的某一天（新模型：实例化）。
     * 过期任务是一个独立的任务实例，直接用 completeDailyTaskInstance 完成
     * （会自动创建下一天的实例）或用 uncompleteDailyTaskInstance 取消。
     * @param taskId 过期每日任务实例的 ID
     * @param date 该实例的日期（从 overdueDate 获取）
     * @param isCompleted 当前是否已完成（true = 要取消完成，false = 要标记完成）
     */
    fun toggleOverdueDailyDone(taskId: String, date: String, isCompleted: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isCompleted) {
                        core.uncompleteDailyTaskInstance(taskId)
                    } else {
                        core.completeDailyTaskInstance(taskId)
                    }
                }
                // 重新加载过期列表和今日任务
                overdueDailyTasks = withContext(Dispatchers.IO) {
                    val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    core.getOverdueDailyTasks(todayStr)
                }
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新过期任务失败: ${e.message}")
            }
        }
    }

    /**
     * 每日任务的完成/取消（新模型：实例化）。
     * 完成：调用 completeDailyTaskInstance → status 变 done + 自动创建明天的实例
     * 取消：调用 uncompleteDailyTaskInstance → status 恢复 todo + 自动删除明天实例
     *
     * "今天"视图下不做全量刷新：completeDailyTaskInstance 创建的"明天"实例不会出现在
     * getTasksDueToday 查询结果中，乐观更新已经是正确状态。跳过刷新可以避免替换整个
     * tasks 列表导致 animateItem 位移动画被打断（首次完成时 done 区从无到有，LazyColumn
     * 布局重建会重置 animateItem 的内部位置追踪）。
     * 非"今天"视图仍然全量刷新，因为明天的新实例可能影响其他清单的显示。
     */
    private fun toggleDailyDone(task: TaskItem) {
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val markDone = !task.isCompletedForDate
        // 乐观更新 UI（立即生效，启动 animateItem 位移动画）
        val updatedTask = task.copy(isDone = markDone, isCompletedForDate = markDone)
        tasks = updateTaskInList(tasks, task.id, updatedTask)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (markDone) {
                        // 完成并自动创建明天的实例
                        core.completeDailyTaskInstance(task.id)
                        // 取消今天的提醒闹钟
                        ReminderStore.resetTriggerCount(app, task.id)
                        ReminderScheduler.cancelReminder(app, task.id)
                        NotificationHelper.dismissNotification(app, task.id)
                    } else {
                        // 取消完成并删除明天的实例
                        core.uncompleteDailyTaskInstance(task.id)
                    }
                }
                // "今天"视图：跳过全量刷新，避免打断 animateItem 动画。
                // 乐观更新已经是正确状态（明天的新实例不会出现在今天的查询结果中）。
                // 非今天视图：需要全量刷新，因为新建/删除了明天的任务可能影响当前清单。
                if (activeChecklistId != TODAY_ID) {
                    tasks = loadTasksForCurrentView()
                }
                // 刷新过期列表（独立状态，不影响主列表动画）
                overdueDailyTasks = withContext(Dispatchers.IO) {
                    core.getOverdueDailyTasks(todayStr)
                }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("更新失败: ${e.message}")
                // 错误时始终全量刷新，恢复乐观更新前的真实状态
                tasks = loadTasksForCurrentView()
            }
        }
    }

    /**
     * 普通任务的完成/取消：改 tasks.status + 级联子任务。
     */
    private fun toggleNormalDone(task: TaskItem) {
        val markDone = !task.isDone
        val newStatus = if (markDone) "done" else "todo"
        val updatedTask = if (markDone) markAllDone(task) else markAllTodo(task)
        tasks = updateTaskInList(tasks, task.id, updatedTask)
        // 标记完成时取消该任务的提醒闹钟和循环
        if (markDone) {
            ReminderStore.resetTriggerCount(app, task.id)
            ReminderScheduler.cancelReminder(app, task.id)
            NotificationHelper.dismissNotification(app, task.id)
        }
        viewModelScope.launch {
            try {
                val allIds = collectIds(updatedTask)
                withContext(Dispatchers.IO) {
                    for (id in allIds) {
                        core.updateTask(id, null, null, null, newStatus, null, null, null, null, null)
                    }
                }
            } catch (e: Exception) {
                onError("更新失败: ${e.message}")
                tasks = loadTasksForCurrentView()
            }
        }
    }

    /**
     * 更新任务字段（标题、描述、优先级）。
     * 所有参数均为可选，仅非 null 参数会被更新。
     */
    fun updateTask(id: String, name: String? = null, description: String? = null, priority: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(id, name, description, priority, null, null, null, null, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("更新任务失败: ${e.message}")
            }
        }
    }

    /**
     * 更新任务的每日循环设置。
     *
     * @param id 任务 ID
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param reminderTime 提醒时间，格式 "HH:MM"
     */
    fun updateRecurrence(id: String, recurrenceRule: String?, reminderTime: String?) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    core.setTaskRecurrence(id, recurrenceRule, reminderTime)
                }
                // 更新循环设置后重新调度提醒闹钟
                scheduleReminderIfNeeded(updated)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新循环失败: ${e.message}")
            }
        }
    }

    /**
     * 移除任务的每日循环（将 recurrence_rule 和 recurrence_reminder_time 设为 NULL）。
     */
    fun removeRecurrence(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.removeTaskRecurrence(id)
                }
                // 移除循环后取消每日闹钟
                ReminderScheduler.cancelReminder(app, id)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("移除循环失败: ${e.message}")
            }
        }
    }

    fun deleteTask(id: String) {
        val snapshot = tasks
        tasks = removeTaskFromList(tasks, id)
        // 删除任务时取消提醒闹钟
        ReminderScheduler.cancelReminder(app, id)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteTask(id) }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除失败: ${e.message}")
                tasks = snapshot
            }
        }
    }

    fun createChecklist(name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.createChecklist(name) }
                val loaded = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                checklists = loaded
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建清单失败: ${e.message}")
            }
        }
    }

    fun deleteChecklist(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteChecklist(id) }
                if (activeChecklistId == id) {
                    activeChecklistId = TODAY_ID
                    tasks = withContext(Dispatchers.IO) { loadTodayTasks() }
                }
                checklists = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除清单失败: ${e.message}")
            }
        }
    }

    fun moveAndReorderTasks(taskId: String, newParentId: String?, siblingIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskParent(taskId, newParentId)
                    if (siblingIds.size > 1) {
                        core.reorderTasks(siblingIds)
                    }
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("移动任务失败: ${e.message}")
            }
        }
    }

    fun moveTask(taskId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskParent(taskId, newParentId)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("移动任务失败: ${e.message}")
            }
        }
    }

    fun reorderTasks(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderTasks(orderedIds) }
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("排序失败: ${e.message}")
            }
        }
    }

    fun reorderChecklists(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderChecklists(orderedIds) }
            } catch (e: Exception) {
                onError("清单排序失败: ${e.message}")
            }
        }
    }

    private suspend fun refreshCounts() {
        val currentChecklists = checklists
        val currentActiveId = activeChecklistId
        val currentTasks = tasks
        val counts = withContext(Dispatchers.IO) {
            val result = mutableMapOf<String?, Pair<Int, Int>>()
            // 当前视图的计数（可能是 "today", "inbox", null, 或清单 ID）
            result[currentActiveId] = countItems(currentTasks)
            // "今天"视图计数（如果不是当前视图则单独计算）
            if (currentActiveId != TODAY_ID) {
                result[TODAY_ID] = countItems(loadTodayTasks())
            }
            // "收集箱"计数：当前视图就是收集箱时复用已有数据，否则单独查询孤儿任务
            if (currentActiveId == INBOX_ID) {
                result[null] = result[INBOX_ID]!!
            } else if (currentActiveId != null) {
                result[null] = countItems(loadTasksWithSubtasks(null, null))
            }
            // 各清单计数
            for (cl in currentChecklists) {
                if (cl.id != currentActiveId) {
                    result[cl.id] = countItems(loadTasksWithSubtasks(cl.id, null))
                }
            }
            result.toMap()
        }
        checklistCounts = counts
    }

    private fun countItems(items: List<TaskItem>): Pair<Int, Int> {
        var taskCount = 0
        var subtaskCount = 0
        fun walk(items: List<TaskItem>, depth: Int) {
            for (item in items) {
                if (depth == 0) taskCount++ else subtaskCount++
                walk(item.subtasks, depth + 1)
            }
        }
        walk(items, 0)
        return Pair(taskCount, subtaskCount)
    }

    /**
     * 加载指定清单和分组下的任务树。
     * categoryId = null 表示"我的任务"；groupId = null 表示不按分组过滤。
     */
    private fun loadTasksWithSubtasks(categoryId: String?, groupId: String?): List<TaskItem> {
        fun loadChildren(parentId: String): List<TaskItem> {
            return core.getSubtasks(parentId).map { task ->
                val subs = loadChildren(task.id)
                task.toUi().copy(subtasks = subs)
            }
        }
        return core.getTasksByCategory(categoryId, groupId).map { task ->
            val subs = loadChildren(task.id)
            task.toUi().copy(subtasks = subs)
        }
    }

    /**
     * 加载今日任务：reminder 日期 = 今天 或 每日循环任务。
     * 调用 Rust 端 getTasksDueToday 获取 DailyTaskStatus，
     * 每日任务的完成状态由 daily_completions 表决定。
     * 子任务递归加载，不单独筛选。
     */
    private fun loadTodayTasks(): List<TaskItem> {
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        fun loadChildren(parentId: String): List<TaskItem> {
            return core.getSubtasks(parentId).map { task ->
                val subs = loadChildren(task.id)
                task.toUi().copy(subtasks = subs)
            }
        }
        return core.getTasksDueToday(todayStr).map { status ->
            val subs = loadChildren(status.task.id)
            status.toUi().copy(subtasks = subs)
        }
    }

    /**
     * 加载过期每日任务列表（所有历史未完成）。
     * 应在加载今日任务后调用，仅在"今天"视图中使用。
     */
    private fun loadOverdueDailyTasks() {
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        overdueDailyTasks = core.getOverdueDailyTasks(todayStr)
    }

    /**
     * 根据当前活跃视图加载任务数据。
     * "today" → 今日聚合查询；
     * "inbox" → 孤儿任务查询（category_id = null）；
     * 其他 → 指定清单查询。
     */
    private suspend fun loadTasksForCurrentView(): List<TaskItem> = withContext(Dispatchers.IO) {
        if (activeChecklistId == TODAY_ID) {
            loadTodayTasks()
        } else if (activeChecklistId == INBOX_ID) {
            loadTasksWithSubtasks(null, activeGroupId)
        } else {
            loadTasksWithSubtasks(activeChecklistId, activeGroupId)
        }
    }

    // ==================== 分组操作 ====================

    /**
     * 创建分组，创建后刷新分组列表。
     * @param name 分组名称（如"语文"）
     * @param color 可选的颜色标识（如"#FF5722"）
     */
    fun createGroup(name: String, color: String? = null) {
        val checklistId = activeChecklistId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.createGroup(name, checklistId, color)
                }
                groups = withContext(Dispatchers.IO) {
                    core.getGroupsByChecklist(checklistId).map { it.toUi() }
                }
            } catch (e: Exception) {
                onError("创建分组失败: ${e.message}")
            }
        }
    }

    /**
     * 删除分组，保留组内任务（group_id 被置空）。
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteGroup(groupId) }
                groups = withContext(Dispatchers.IO) {
                    activeChecklistId?.let { core.getGroupsByChecklist(it).map { g -> g.toUi() } } ?: emptyList()
                }
                // 如果删除的是当前选中分组，切回"全部"
                if (activeGroupId == groupId) {
                    activeGroupId = null
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除分组失败: ${e.message}")
            }
        }
    }

    /**
     * 重命名分组。
     */
    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.updateGroup(groupId, newName, null) }
                groups = withContext(Dispatchers.IO) {
                    activeChecklistId?.let { core.getGroupsByChecklist(it).map { g -> g.toUi() } } ?: emptyList()
                }
            } catch (e: Exception) {
                onError("重命名分组失败: ${e.message}")
            }
        }
    }

    // ==================== 附件管理 ====================

    /**
     * 获取指定任务的所有附件，返回 UI 模型列表。
     * 调用方需在协程中调用。
     */
    suspend fun getAttachments(taskId: String): List<AttachmentUiItem> {
        return withContext(Dispatchers.IO) {
            core.getAttachments(taskId).map { it.toUi() }
        }
    }

    /**
     * 为任务添加附件。
     * 文件应已复制到内部存储，此方法将附件记录写入数据库。
     *
     * @param taskId 任务 ID
     * @param filePath 内部存储文件路径
     * @param fileName 原始文件名
     * @param mimeType MIME 类型
     * @param fileSize 文件大小（字节）
     */
    fun addAttachment(
        taskId: String,
        filePath: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.addAttachment(taskId, fileName, filePath, mimeType, fileSize)
                }
            } catch (e: Exception) {
                onError("添加附件失败: ${e.message}")
            }
        }
    }

    /**
     * 删除附件。
     *
     * @param attachmentId 附件 ID
     */
    fun removeAttachment(attachmentId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.removeAttachment(attachmentId)
                }
            } catch (e: Exception) {
                onError("删除附件失败: ${e.message}")
            }
        }
    }

    /**
     * 批量关联临时附件到新创建的任务。
     * 用于新建任务场景：附件先存在临时列表中，任务创建后再批量写入数据库。
     *
     * @param taskId 新创建的任务 ID
     * @param pendingAttachments 临时附件信息列表
     */
    fun commitPendingAttachments(
        taskId: String,
        pendingAttachments: List<PickedFileInfo>,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (info in pendingAttachments) {
                        core.addAttachment(taskId, info.fileName, info.filePath, info.mimeType, info.fileSize)
                    }
                }
            } catch (e: Exception) {
                onError("关联附件失败: ${e.message}")
            }
        }
    }

    // ==================== 提醒调度 ====================

    /**
     * 根据任务数据调度提醒闹钟。
     *
     * 处理两种提醒：
     * - 每日循环任务（recurrenceRule="daily"）：调度每日重复闹钟
     * - 普通任务（reminder 字段有值）：调度一次性精确闹钟
     * 如果任务不需要提醒，取消已有闹钟。
     */
    private fun scheduleReminderIfNeeded(task: TaskData) {
        // 先取消已有闹钟，避免重复
        ReminderScheduler.cancelReminder(app, task.id)

        // 每日循环任务：解析 HH:MM 并调度每日闹钟
        if (task.recurrenceRule == "daily" && task.recurrenceReminderTime != null) {
            val parts = task.recurrenceReminderTime!!.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull()
                val minute = parts[1].toIntOrNull()
                if (hour != null && minute != null) {
                    ReminderScheduler.scheduleDailyReminder(app, task.id, hour, minute)
                }
            }
        }

        // 普通任务：解析 reminder 时间戳并调度一次性闹钟
        if (task.reminder != null) {
            try {
                // reminder 格式可能是 "YYYY-MM-DDTHH:MM" 或 RFC 3339
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                val ldt = java.time.LocalDateTime.parse(task.reminder, formatter)
                val millis = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (millis > System.currentTimeMillis()) {
                    ReminderScheduler.scheduleExactReminder(app, task.id, millis)
                }
            } catch (_: Exception) {
                // 解析失败静默忽略
            }
        }
    }

    /**
     * 更新任务的一次性提醒时间。
     * 同时调度/取消对应的闹钟。
     *
     * @param id 任务 ID
     * @param reminder 提醒时间字符串（"YYYY-MM-DDTHH:MM"），null 表示清除提醒
     */
    fun updateReminder(id: String, reminder: String?) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    core.updateTask(id, null, null, null, null, null, reminder, null, null, null)
                }
                // 更新提醒后重新调度闹钟
                scheduleReminderIfNeeded(updated)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新提醒失败: ${e.message}")
            }
        }
    }

    companion object {
        /**
         * "今天"视图的虚拟清单 ID。
         * 不是真实存储在 DB 中的清单，而是 App 启动默认显示的跨清单聚合视图。
         */
        const val TODAY_ID = "today"

        /**
         * "收集箱"视图的虚拟清单 ID。
         * 不是真实存储在 DB 中的清单，用于显示所有未分配清单的孤儿任务（category_id = null）。
         * 任何没有关联清单的任务都会自动出现在此视图中。
         */
        const val INBOX_ID = "inbox"
    }
}

@Composable
fun taskViewModel(): TaskViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TaskViewModel(app.core(), { msg ->
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }, app) as T
            }
        }
    )
}
