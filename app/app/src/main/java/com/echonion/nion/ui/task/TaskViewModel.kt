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

@Stable
data class ChecklistItem(
    val id: String,
    val name: String,
)

@Stable
data class FlatTaskItem(
    val task: TaskItem,
    val depth: Int,
    val parentId: String?,
    val isGroupFirst: Boolean,
    val isGroupLast: Boolean,
)

private const val TAG = "TaskViewModel"

class TaskViewModel(private val core: NionCore, private val onError: (String) -> Unit) : ViewModel() {

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    var activeChecklistId by mutableStateOf<String?>(null)
        private set

    var checklistCounts by mutableStateOf<Map<String?, Pair<Int, Int>>>(emptyMap())
        private set

    val todoTasks: List<TaskItem> by derivedStateOf { tasks.filter { !it.isDone } }
    val doneTasks: List<TaskItem> by derivedStateOf { tasks.filter { it.isDone } }
    val todoCount: Int by derivedStateOf { todoTasks.size }
    val doneCount: Int by derivedStateOf { doneTasks.size }

    val flatTodoTasks: List<FlatTaskItem> by derivedStateOf { flattenWithGroupInfo(todoTasks) }

    val activeChecklistName: String
        get() = if (activeChecklistId == null) "我的任务"
            else checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"

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
        Log.d(TAG, "TaskViewModel init, starting refresh()")
        refresh()
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
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(activeChecklistId)
                }
                tasks = loadedTasks
            } catch (e: Exception) {
                onError("加载任务失败: ${e.message}")
            }
            refreshCounts()
        }
    }

    fun setActiveChecklist(id: String?) {
        viewModelScope.launch {
            try {
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(id)
                }
                activeChecklistId = id
                tasks = loadedTasks
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("切换清单失败: ${e.message}")
            }
        }
    }

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

    fun moveTask(taskId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskParent(taskId, newParentId)
                }
                tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(activeChecklistId) }
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

private fun flattenWithGroupInfo(tasks: List<TaskItem>): List<FlatTaskItem> {
    val result = mutableListOf<FlatTaskItem>()
    for (task in tasks) {
        val subItems = mutableListOf<FlatTaskItem>()
        flattenSubs(task.subtasks, depth = 1, parentId = task.id, subItems)
        val hasSubs = subItems.isNotEmpty()
        result.add(FlatTaskItem(
            task = task,
            depth = 0,
            parentId = null,
            isGroupFirst = true,
            isGroupLast = !hasSubs,
        ))
        if (hasSubs) {
            subItems[subItems.lastIndex] = subItems.last().copy(isGroupLast = true)
            result.addAll(subItems)
        }
    }
    return result
}

private fun flattenSubs(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for ((index, sub) in subs.withIndex()) {
        val hasChildSubs = sub.subtasks.isNotEmpty()
        result.add(FlatTaskItem(
            task = sub,
            depth = depth,
            parentId = parentId,
            isGroupFirst = false,
            isGroupLast = index == subs.lastIndex && !hasChildSubs,
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
    createdAt = createdAt,
)

private fun ChecklistData.toUi(): ChecklistItem = ChecklistItem(
    id = id,
    name = name,
)

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
