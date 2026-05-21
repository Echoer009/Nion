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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData
import uniffi.nion_core.ChecklistData

/** 任务 UI 模型，对应 Rust 端的 TaskData，支持嵌套子任务 */
@Stable
data class TaskItem(
    val id: String,
    val title: String,
    val description: String?,
    val priority: String,
    val isDone: Boolean,
    val createdAt: String,
    val subtasks: List<TaskItem> = emptyList(),
)

/** 清单 UI 模型，对应 Rust 端的 ChecklistData */
@Stable
data class ChecklistItem(
    val id: String,
    val name: String,
)

private const val TAG = "TaskViewModel"

/**
 * 任务页面 ViewModel
 * 管理任务列表、清单列表及其计数的加载、增删改操作
 * 所有数据库操作通过 NionCore（UniFFI 绑定）在 IO 线程执行
 */
class TaskViewModel(private val core: NionCore, private val onError: (String) -> Unit) : ViewModel() {

    /** 当前清单下的所有任务（含已完成和未完成） */
    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    /** 所有自定义清单列表 */
    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    /** 当前激活的清单 ID，null 表示"我的任务"（默认） */
    var activeChecklistId by mutableStateOf<String?>(null)
        private set

    /** 每个清单的任务计数，key 为清单 ID（null 表示"我的任务"），value 为 (任务数, 子任务数) */
    var checklistCounts by mutableStateOf<Map<String?, Pair<Int, Int>>>(emptyMap())
        private set

    /** 未完成任务列表，使用 derivedStateOf 避免不必要的重组 */
    val todoTasks: List<TaskItem> by derivedStateOf { tasks.filter { !it.isDone } }
    /** 已完成任务列表 */
    val doneTasks: List<TaskItem> by derivedStateOf { tasks.filter { it.isDone } }
    /** 未完成数量 */
    val todoCount: Int by derivedStateOf { todoTasks.size }
    /** 已完成数量 */
    val doneCount: Int by derivedStateOf { doneTasks.size }

    /** 当前清单的显示名称 */
    val activeChecklistName: String
        get() = if (activeChecklistId == null) "我的任务"
            else checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"

    /** 防抖 Job：延迟 300ms 后刷新计数，避免频繁切换清单时重复查询数据库 */
    private var countsJob: Job? = null

    /** 多选模式：已选中的任务 ID 集合 */
    var selectedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 是否处于多选模式 */
    val isSelectionMode: Boolean by derivedStateOf { selectedTaskIds.isNotEmpty() }

    /** 切换某个任务的选中状态 */
    fun toggleSelection(taskId: String) {
        selectedTaskIds = if (taskId in selectedTaskIds) {
            selectedTaskIds - taskId
        } else {
            selectedTaskIds + taskId
        }
    }

    /** 清除所有选中 */
    fun clearSelection() {
        selectedTaskIds = emptySet()
    }

    /** 批量删除选中的任务 */
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

    /** 调度计数刷新，取消上一次未完成的调度 */
    private fun scheduleRefreshCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            delay(300)
            refreshCounts()
        }
    }

    init {
        Log.d(TAG, "TaskViewModel init, starting refresh()")
        refresh()
    }

    /** 首次加载或手动刷新：加载清单 → 加载任务 → 刷新计数 */
    fun refresh() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    core.getChecklists().map { it.toUi() }
                }
                Log.d(TAG, "Loaded ${loaded.size} checklists: ${loaded.map { it.name }}")
                checklists = loaded
            } catch (e: Exception) {
                Log.e(TAG, "加载清单失败", e)
                onError("加载清单失败: ${e.message}")
            }
            try {
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(activeChecklistId)
                }
                Log.d(TAG, "Loaded ${loadedTasks.size} tasks for activeChecklistId=$activeChecklistId")
                tasks = loadedTasks
            } catch (e: Exception) {
                Log.e(TAG, "加载任务失败", e)
                onError("加载任务失败: ${e.message}")
            }
            refreshCounts()
        }
    }

    /** 切换激活的清单，从数据库加载该清单下的所有任务 */
    fun setActiveChecklist(id: String?) {
        val startMs = System.currentTimeMillis()
        Log.d(TAG, "setActiveChecklist($id) start")
        viewModelScope.launch {
            try {
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(id)
                }
                Log.d(TAG, "setActiveChecklist($id): loaded ${loadedTasks.size} tasks in ${System.currentTimeMillis() - startMs}ms")
                activeChecklistId = id
                tasks = loadedTasks
                scheduleRefreshCounts()
            } catch (e: Exception) {
                Log.e(TAG, "setActiveChecklist failed", e)
                onError("切换清单失败: ${e.message}")
            }
        }
    }

    /** 创建新任务，写入数据库后重新加载任务列表 */
    fun createTask(title: String, description: String?, priority: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.createTask(title, description, priority, null, activeChecklistId, null)
                }
                tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(activeChecklistId) }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建任务失败: ${e.message}")
            }
        }
    }

    /** 创建子任务，parentId 指定父任务 */
    fun createSubtask(parentId: String, title: String, priority: String = "medium") {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.createTask(title, null, priority, null, activeChecklistId, parentId)
                }
                tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(activeChecklistId) }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建子任务失败: ${e.message}")
            }
        }
    }

    /**
     * 切换任务完成状态
     * 先乐观更新 UI（立即反映状态变化），再异步写入数据库
     * 如果数据库写入失败则回滚到数据库实际数据
     */
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
                        core.updateTask(id, null, null, null, newStatus)
                    }
                }
            } catch (e: Exception) {
                onError("更新失败: ${e.message}")
                tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(activeChecklistId) }
            }
        }
    }

    /**
     * 删除任务
     * 先乐观更新 UI，再异步删除数据库记录
     * 失败时用快照回滚
     */
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

    /** 创建新清单，刷新清单列表和计数 */
    fun createChecklist(name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.createChecklist(name) }
                val loaded = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                Log.d(TAG, "createChecklist('$name'): now ${loaded.size} checklists")
                checklists = loaded
                scheduleRefreshCounts()
            } catch (e: Exception) {
                Log.e(TAG, "createChecklist failed", e)
                onError("创建清单失败: ${e.message}")
            }
        }
    }

    /** 删除清单，如果删除的是当前激活的清单则切回"我的任务" */
    fun deleteChecklist(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteChecklist(id) }
                if (activeChecklistId == id) {
                    activeChecklistId = null
                    tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(null) }
                }
                checklists = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除清单失败: ${e.message}")
            }
        }
    }

    /** 持久化拖拽排序后的任务顺序到数据库 */
    fun reorderTasks(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderTasks(orderedIds) }
            } catch (e: Exception) {
                onError("排序失败: ${e.message}")
            }
        }
    }

    /** 持久化拖拽排序后的清单顺序到数据库 */
    fun reorderChecklists(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderChecklists(orderedIds) }
            } catch (e: Exception) {
                onError("清单排序失败: ${e.message}")
            }
        }
    }

    /**
     * 刷新所有清单的任务/子任务计数
     * 遍历每个清单加载任务并统计数量，结果写入 checklistCounts 触发 Sidebar 更新
     * 注意：当前活跃清单的计数直接从内存中的 tasks 统计，避免重复查询
     */
    private suspend fun refreshCounts() {
        val startMs = System.currentTimeMillis()
        val currentChecklists = checklists
        val currentActiveId = activeChecklistId
        val currentTasks = tasks
        val counts = withContext(Dispatchers.IO) {
            val result = mutableMapOf<String?, Pair<Int, Int>>()
            result[currentActiveId] = countItems(currentTasks)
            if (currentActiveId != null) {
                result[null] = countItems(loadTasksWithSubtasks(null))
            }
            for (cl in currentChecklists) {
                if (cl.id != currentActiveId) {
                    result[cl.id] = countItems(loadTasksWithSubtasks(cl.id))
                }
            }
            result.toMap()
        }
        checklistCounts = counts
        Log.d(TAG, "refreshCounts: ${counts.size} entries in ${System.currentTimeMillis() - startMs}ms")
    }

    /** 递归统计任务和子任务数量，返回 (任务数, 子任务数) */
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
     * 从数据库加载指定清单下的所有任务，递归加载子任务
     * 必须在 IO 线程调用
     */
    private fun loadTasksWithSubtasks(categoryId: String?): List<TaskItem> {
        fun loadChildren(parentId: String): List<TaskItem> {
            return core.getSubtasks(parentId).map { task ->
                val subs = loadChildren(task.id)
                task.toUi().copy(subtasks = subs)
            }
        }
        return core.getTasksByCategory(categoryId).map { task ->
            val subs = loadChildren(task.id)
            task.toUi().copy(subtasks = subs)
        }
    }
}

/** 在任务树中递归查找并替换指定任务（用于 toggleDone 的乐观更新） */
private fun updateTaskInList(tasks: List<TaskItem>, targetId: String, updated: TaskItem): List<TaskItem> {
    return tasks.map { task ->
        if (task.id == targetId) updated
        else task.copy(subtasks = updateTaskInList(task.subtasks, targetId, updated))
    }
}

/** 递归将任务及其所有子任务标记为已完成 */
private fun markAllDone(task: TaskItem): TaskItem {
    return task.copy(isDone = true, subtasks = task.subtasks.map { markAllDone(it) })
}

/** 递归将任务及其所有子任务标记为未完成 */
private fun markAllTodo(task: TaskItem): TaskItem {
    return task.copy(isDone = false, subtasks = task.subtasks.map { markAllTodo(it) })
}

/** 递归收集任务及其所有子任务的 ID */
private fun collectIds(task: TaskItem): List<String> {
    return listOf(task.id) + task.subtasks.flatMap { collectIds(it) }
}

/** 从任务树中递归移除指定任务（用于 deleteTask 的乐观更新） */
private fun removeTaskFromList(tasks: List<TaskItem>, targetId: String): List<TaskItem> {
    return tasks.filter { it.id != targetId }
        .map { it.copy(subtasks = removeTaskFromList(it.subtasks, targetId)) }
}

/** Rust 端 TaskData → UI 端 TaskItem 的转换 */
private fun TaskData.toUi(): TaskItem = TaskItem(
    id = id,
    title = title,
    description = description,
    priority = priority,
    isDone = status == "done",
    createdAt = createdAt,
)

/** Rust 端 ChecklistData → UI 端 ChecklistItem 的转换 */
private fun ChecklistData.toUi(): ChecklistItem = ChecklistItem(
    id = id,
    name = name,
)

/** Composable 函数：获取与 Activity 生命周期绑定的 TaskViewModel 实例 */
@Composable
fun taskViewModel(): TaskViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TaskViewModel(app.core()) { msg ->
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                } as T
            }
        }
    )
}
