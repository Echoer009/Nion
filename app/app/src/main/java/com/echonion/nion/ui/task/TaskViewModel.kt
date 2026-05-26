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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData
import uniffi.nion_core.ChecklistData
import uniffi.nion_core.GroupData

@Stable
data class TaskItem(
    val id: String,
    val title: String,
    val description: String?,
    val priority: String,
    val isDone: Boolean,
    val dueDate: String?,
    val createdAt: String,
    val subtasks: List<TaskItem> = emptyList(),
    /** 该任务累计专注秒数，来自 Rust 端 focus_seconds 字段 */
    val focusSeconds: Long = 0,
    /** 循环规则：null 或 "none" 表示不循环，"daily" 表示每日循环 */
    val recurrenceRule: String? = null,
    /** 每日循环的提醒时间，格式 "HH:MM"，仅当 recurrenceRule="daily" 时有效 */
    val recurrenceReminderTime: String? = null,
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

class TaskViewModel(
    private val core: NionCore,
    private val onError: (String) -> Unit,
    private val app: android.app.Application,
) : ViewModel() {

    companion object {
        /**
         * "今天"视图的虚拟清单 ID。
         * 不是真实存储在 DB 中的清单，而是 App 启动默认显示的跨清单聚合视图。
         */
        const val TODAY_ID = "today"
    }

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    /** 当前活跃清单 ID。null = "我的任务"（未分配），"today" = "今天"视图，其他 = 真实清单 ID */
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
            null -> "我的任务"
            else -> checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"
        }

    private var countsJob: Job? = null

    var selectedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    val isSelectionMode: Boolean by derivedStateOf { selectedTaskIds.isNotEmpty() }

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

    init {
        refresh()
        // 监听 Agent 工具执行后的数据变更事件，自动刷新任务列表
        viewModelScope.launch {
            app.dataEvents().collect { event ->
                Log.d("TaskViewModel", "收到数据变更事件: ${event.type}")
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    core.getChecklists().map { it.toUi() }
                }
                checklists = loaded
            } catch (e: Exception) {
                onError("加载清单失败: ${e.message}")
            }
            try {
                // 刷新当前清单下的分组（"今天"和"我的任务"视图无分组）
                groups = if (activeChecklistId != null && activeChecklistId != TODAY_ID) {
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
                    } else {
                        loadTasksWithSubtasks(activeChecklistId, activeGroupId)
                    }
                }
                tasks = loadedTasks
            } catch (e: Exception) {
                onError("加载任务失败: ${e.message}")
            }
            refreshCounts()
        }
    }

    /**
     * 切换活跃清单，同时加载该清单下的分组列表，并重置分组筛选。
     * groupId = null 表示显示该清单下所有任务。
     * id = "today" 切换至"今天"视图（跨清单聚合今日任务）。
     * id = null 切换至"我的任务"（未分配清单的孤儿任务）。
     */
    fun setActiveChecklist(id: String?) {
        viewModelScope.launch {
            try {
                // 切换清单时重置分组选择
                activeGroupId = null
                activeChecklistId = id
                // "今天"和"我的任务"视图不加载分组，真实清单才加载
                groups = if (id != null && id != TODAY_ID) {
                    withContext(Dispatchers.IO) {
                        core.getGroupsByChecklist(id).map { it.toUi() }
                    }
                } else {
                    emptyList()
                }
                val loadedTasks = withContext(Dispatchers.IO) {
                    if (id == TODAY_ID) {
                        loadTodayTasks()
                    } else {
                        loadTasksWithSubtasks(id, null)
                    }
                }
                tasks = loadedTasks
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
     * 在"今天"视图中创建时，category_id 为 null（不属于任何清单）。
     *
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param recurrenceReminderTime 每日循环提醒时间，格式 "HH:MM"
     */
    fun createTask(
        title: String,
        description: String?,
        priority: String,
        dueDate: String? = null,
        recurrenceRule: String? = null,
        recurrenceReminderTime: String? = null,
    ) {
        viewModelScope.launch {
            try {
                // "今天"不是真实清单，创建任务时 category_id 设为 null
                val realCategoryId = if (activeChecklistId == TODAY_ID) null else activeChecklistId
                withContext(Dispatchers.IO) {
                    core.createTask(title, description, priority, dueDate, realCategoryId, null, activeGroupId, recurrenceRule, recurrenceReminderTime)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建任务失败: ${e.message}")
            }
        }
    }

    /**
     * 创建子任务。继承父任务所在的清单和分组。
     */
    fun createSubtask(parentId: String, title: String, priority: String = "medium") {
        viewModelScope.launch {
            try {
                // 子任务继承父任务的分组归属
                val parentGroup = activeGroupId
                val realCategoryId = if (activeChecklistId == TODAY_ID) null else activeChecklistId
                withContext(Dispatchers.IO) {
                    core.createTask(title, null, priority, null, realCategoryId, parentId, parentGroup, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建子任务失败: ${e.message}")
            }
        }
    }

    fun toggleDone(task: TaskItem) {
        val markDone = !task.isDone
        val newStatus = if (markDone) "done" else "todo"
        val updatedTask = if (markDone) markAllDone(task) else markAllTodo(task)
        tasks = updateTaskInList(tasks, task.id, updatedTask)
        viewModelScope.launch {
            try {
                val allIds = collectIds(updatedTask)
                withContext(Dispatchers.IO) {
                    for (id in allIds) {
                        core.updateTask(id, null, null, null, newStatus, null, null, null, null, null, null)
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
    fun updateTask(id: String, title: String? = null, description: String? = null, priority: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(id, title, description, priority, null, null, null, null, null, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("更新任务失败: ${e.message}")
            }
        }
    }

    /** 更新任务截止日期，dueDate = null 表示清除日期 */
    fun updateDueDate(id: String, dueDate: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(id, null, null, null, null, dueDate, null, null, null, null, null)
                }
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新日期失败: ${e.message}")
            }
        }
    }

    /**
     * 更新任务的每日循环设置。
     *
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param reminderTime 提醒时间，格式 "HH:MM"
     */
    fun updateRecurrence(id: String, recurrenceRule: String?, reminderTime: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.setTaskRecurrence(id, recurrenceRule, reminderTime)
                }
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
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("移除循环失败: ${e.message}")
            }
        }
    }

    fun deleteTask(id: String) {
        val snapshot = tasks
        tasks = removeTaskFromList(tasks, id)
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
            // 当前视图的计数（可能是 "today", null, 或清单 ID）
            result[currentActiveId] = countItems(currentTasks)
            // "今天"视图计数（如果不是当前视图则单独计算）
            if (currentActiveId != TODAY_ID) {
                result[TODAY_ID] = countItems(loadTodayTasks())
            }
            // "我的任务"计数（如果不是当前视图则单独计算）
            if (currentActiveId != null) {
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
     * 加载今日任务：due_date = 今天 或 每日循环且未过期。
     * 调用 Rust 端 getTasksDueToday 进行跨清单聚合查询。
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
        return core.getTasksDueToday(todayStr).map { task ->
            val subs = loadChildren(task.id)
            task.toUi().copy(subtasks = subs)
        }
    }

    /**
     * 根据当前活跃视图加载任务数据。
     * "today" → 今日聚合查询；null → 未分配查询；其他 → 指定清单查询。
     */
    private suspend fun loadTasksForCurrentView(): List<TaskItem> = withContext(Dispatchers.IO) {
        if (activeChecklistId == TODAY_ID) {
            loadTodayTasks()
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
}

private fun flattenWithGroupInfo(tasks: List<TaskItem>): List<FlatTaskItem> {
    val result = mutableListOf<FlatTaskItem>()
    for (task in tasks) {
        flattenTodoGroup(task, depth = 0, result)
    }
    return result
}

/**
 * 递归展开待办任务组。未完成任务作为组根 + 递归子树；
 * 已完成任务跳过自身但继续深入子树（子任务可能被单独取消完成）。
 */
private fun flattenTodoGroup(
    task: TaskItem,
    depth: Int,
    result: MutableList<FlatTaskItem>,
) {
    if (!task.isDone) {
        val subItems = mutableListOf<FlatTaskItem>()
        flattenSubs(task.subtasks, depth = depth + 1, parentId = task.id, subItems)
        val hasSubs = subItems.isNotEmpty()
        result.add(FlatTaskItem(
            task = task,
            depth = depth,
            parentId = null,
            isGroupFirst = true,
            isGroupLast = !hasSubs,
        ))
        if (hasSubs) {
            subItems[subItems.lastIndex] = subItems.last().copy(isGroupLast = true)
            result.addAll(subItems)
        }
    } else {
        // 已完成 → 跳过自身，但子任务可能被单独取消完成，继续深入
        for (sub in task.subtasks) {
            flattenTodoGroup(sub, depth, result)
        }
    }
}

/** 递归展开子任务为 FlatTaskItem 列表，跳过已完成项但继续递归其子树（子任务可能被单独取消完成） */
private fun flattenSubs(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for ((index, sub) in subs.withIndex()) {
        if (sub.isDone) {
            // 父已完成但子任务可能被单独取消，仍需递归
            flattenSubs(sub.subtasks, depth + 1, sub.id, result)
            continue
        }
        val hasChildSubs = sub.subtasks.any { !it.isDone }
        val isLastInSameParent = index == subs.lastIndex || subs.drop(index + 1).all { it.isDone }
        result.add(FlatTaskItem(
            task = sub,
            depth = depth,
            parentId = parentId,
            isGroupFirst = false,
            isGroupLast = isLastInSameParent && !hasChildSubs,
        ))
        flattenSubs(sub.subtasks, depth + 1, sub.id, result)
    }
}

private fun updateTaskInList(tasks: List<TaskItem>, targetId: String, updated: TaskItem): List<TaskItem> {
    return tasks.map { task ->
        if (task.id == targetId) updated
        else task.copy(subtasks = updateTaskInList(task.subtasks, targetId, updated))
    }
}

private fun markAllDone(task: TaskItem): TaskItem {
    return task.copy(isDone = true, subtasks = task.subtasks.map { markAllDone(it) })
}

private fun markAllTodo(task: TaskItem): TaskItem {
    return task.copy(isDone = false, subtasks = task.subtasks.map { markAllTodo(it) })
}

/**
 * 递归遍历任务树，将已完成任务按母子分组添加到结果列表。
 * 已完成的任务作为组根，其已完成的子任务跟随其后、缩进显示。
 */
private fun addDoneGroups(
    tasks: List<TaskItem>,
    baseDepth: Int,
    result: MutableList<FlatTaskItem>,
) {
    for (task in tasks) {
        if (task.isDone) {
            // 已完成项作为组根
            val subs = mutableListOf<FlatTaskItem>()
            collectDoneSubs(task.subtasks, depth = baseDepth + 1, parentId = task.id, subs)
            val hasDoneSubs = subs.isNotEmpty()
            result.add(FlatTaskItem(
                task = task,
                depth = baseDepth,
                parentId = null,
                isGroupFirst = true,
                isGroupLast = !hasDoneSubs,
            ))
            if (hasDoneSubs) {
                subs[subs.lastIndex] = subs.last().copy(isGroupLast = true)
                result.addAll(subs)
            }
        } else {
            // 未完成，但其子任务中可能有已完成的，继续深入
            addDoneGroups(task.subtasks, baseDepth, result)
        }
    }
}

/**
 * 收集已完成父任务下所有已完成的子孙任务，跳过未完成的。
 * 只收集 isDone=true 的项，保持相对深度。
 */
private fun collectDoneSubs(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for ((index, sub) in subs.withIndex()) {
        if (!sub.isDone) {
            // 未完成的子任务不加入已完成列表，但仍需递归（其孙子可能已完成）
            collectDoneSubs(sub.subtasks, depth + 1, sub.id, result)
            continue
        }
        val hasDoneSubs = sub.subtasks.any { it.isDone }
        val isLast = index == subs.lastIndex || subs.drop(index + 1).all { !it.isDone }
        result.add(FlatTaskItem(
            task = sub,
            depth = depth,
            parentId = parentId,
            isGroupFirst = false,
            isGroupLast = isLast && !hasDoneSubs,
        ))
        collectDoneSubs(sub.subtasks, depth + 1, sub.id, result)
    }
}

private fun collectIds(task: TaskItem): List<String> {
    return listOf(task.id) + task.subtasks.flatMap { collectIds(it) }
}

private fun removeTaskFromList(tasks: List<TaskItem>, targetId: String): List<TaskItem> {
    return tasks.filter { it.id != targetId }
        .map { it.copy(subtasks = removeTaskFromList(it.subtasks, targetId)) }
}

private fun TaskData.toUi(): TaskItem = TaskItem(
    id = id,
    title = title,
    description = description,
    priority = priority,
    isDone = status == "done",
    dueDate = dueDate,
    createdAt = createdAt,
    focusSeconds = focusSeconds,
    recurrenceRule = recurrenceRule,
    recurrenceReminderTime = recurrenceReminderTime,
)

private fun ChecklistData.toUi(): ChecklistItem = ChecklistItem(
    id = id,
    name = name,
)

/** 将 Rust 端 GroupData 转换为 UI 模型 */
private fun GroupData.toUi(): GroupItem = GroupItem(
    id = id,
    name = name,
    checklistId = checklistId,
    color = color,
)

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
