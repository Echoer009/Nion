package com.echonion.nion.ui.schedule

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
import com.echonion.nion.notifyDataChanged
import com.echonion.nion.ui.companion.tools.DataType
import com.echonion.nion.ui.components.TaskCardModel
import com.echonion.nion.ui.task.FlatTaskItem
import com.echonion.nion.ui.task.TaskItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.CalendarDateMarker
import uniffi.nion_core.DailyTaskStatus
import uniffi.nion_core.TaskData
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 日程页面的单日任务 UI 模型，带每日任务完成状态
 */
@Stable
data class ScheduleTaskItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val priority: String,
    val isDone: Boolean,
    val isDaily: Boolean,
    val reminderTime: String?,
    /** 一次性提醒时间，格式 "YYYY-MM-DDTHH:MM"，在卡片上以铃铛图标展示 */
    val reminder: String? = null,
)

/**
 * 日程页面 ViewModel —— 加载指定日期的任务列表和日历标记数据。
 * 数据来源：Rust 端 getTasksForDate / getCalendarDateMarkers。
 *
 * @param app Application 引用，用于发出数据变更通知，使任务列表同步刷新
 * @param core Rust 核心 API 实例
 * @param onError 错误提示回调
 */
@OptIn(FlowPreview::class)
class ScheduleViewModel(
    private val app: Application,
    private val core: NionCore,
    private val onError: (String) -> Unit,
) : ViewModel() {

    /** 当前选中的日期，默认今天 */
    var selectedDate by mutableStateOf(LocalDate.now())
        private set

    /** 选中日期的任务列表（含每日任务完成状态） */
    var tasks by mutableStateOf<List<ScheduleTaskItem>>(emptyList())
        private set

    /** 日历标记数据：当前显示月份 ± 缓冲区的日期统计 */
    var dateMarkers by mutableStateOf<Map<String, CalendarDateMarker>>(emptyMap())
        private set

    /** ViewModel 创建时加载今天的任务和当月日历标记，并订阅数据变更事件 */
    init {
        loadTasksForDate(LocalDate.now())
        loadCalendarMarkers(LocalDate.now().year, LocalDate.now().monthValue)

        // 监听数据变更事件（AI 工具、TaskViewModel 等外部操作），自动刷新当前日期任务和日历标记
        // debounce(300)：合并 AI 连续调用多个工具时的连续事件，只触发一次刷新
        viewModelScope.launch {
            app.dataEvents()
                .debounce(300)
                .collect { event ->
                    if (DataType.TASK_DATA in event.types) {
                        Log.d("ScheduleViewModel", "收到数据变更事件: ${event.types}")
                        loadTasksForDate(selectedDate)
                        loadCalendarMarkers(selectedDate.year, selectedDate.monthValue)
                    }
                }
        }
    }

    /**
     * 选择日期并加载该日期的任务。
     * @param date 新的选中日期
     */
    fun selectDate(date: LocalDate) {
        selectedDate = date
        loadTasksForDate(date)
    }

    /**
     * 计算派生状态：已转为 FlatTaskItem 的待办项列表，供 SharedTaskList 消费。
     * 所有日程任务 depth=0（无子任务层级），isGroupFirst/isGroupLast 始终为 true（每个任务自成一组）。
     */
    val flatTodoItems: List<FlatTaskItem> by derivedStateOf {
        tasks.filter { !it.isDone }.map { it.toFlatItem() }
    }

    val flatDoneItems: List<FlatTaskItem> by derivedStateOf {
        tasks.filter { it.isDone }.map { it.toFlatItem() }
    }

    /**
     * 乐观更新：切换任务完成状态——立即更新内存状态，异步持久化，失败时回退。
     * 新模型：每日任务使用实例化 API（completeDailyTaskInstance / uncompleteDailyTaskInstance）
     */
    fun toggleDone(taskId: String, date: LocalDate, isCompleted: Boolean, isDaily: Boolean) {
        // 乐观更新：立即翻转内存中的 isDone 状态
        tasks = tasks.map { item ->
            if (item.id == taskId) item.copy(isDone = !isCompleted) else item
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isDaily) {
                        // 新模型：实例化完成/取消
                        if (isCompleted) {
                            core.uncompleteDailyTaskInstance(taskId)
                        } else {
                            core.completeDailyTaskInstance(taskId)
                        }
                    } else {
                        val newStatus = if (isCompleted) "todo" else "done"
                        core.updateTask(taskId, null, null, null, newStatus, null, null, null, null, null)
                    }
                }
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("更新任务失败: ${e.message}")
                // 失败时回退：重新从 DB 加载
                loadTasksForDate(date)
            }
        }
    }

    /**
     * 从 Rust 端加载完整 TaskData 并转为 TaskItem，供 TaskDetailOverlay 消费。
     * @param taskId 任务 ID
     * @return 完整 TaskItem，加载失败时返回 null
     */
    fun getFullTask(taskId: String): TaskItem? {
        return try {
            val taskData = core.getTask(taskId)
            taskData.toTaskItem()
        } catch (e: Exception) {
            onError("加载任务详情失败: ${e.message}")
            null
        }
    }

    /** 删除指定任务 */
    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteTask(taskId) }
                loadTasksForDate(selectedDate)
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("删除任务失败: ${e.message}")
            }
        }
    }

    /** 更新任务备注（描述） */
    fun updateTaskDescription(taskId: String, description: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(taskId, null, description, null, null, null, null, null, null, null)
                }
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("更新备注失败: ${e.message}")
            }
        }
    }

    /** 更新任务每日循环设置 */
    fun updateRecurrence(taskId: String, rule: String?, time: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(taskId, null, null, null, null, null, null, null, rule, time)
                }
                loadTasksForDate(selectedDate)
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("更新循环设置失败: ${e.message}")
            }
        }
    }

    /** 移除每日循环 */
    fun removeRecurrence(taskId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(taskId, null, null, null, null, null, null, null, "none", null)
                }
                loadTasksForDate(selectedDate)
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("移除循环失败: ${e.message}")
            }
        }
    }

    /** 更新一次性提醒时间，传 null 时清除提醒 */
    fun updateReminder(taskId: String, reminder: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // null 表示清除提醒，调用 clearTaskReminder 将 reminder 设为 NULL
                    if (reminder == null) {
                        core.clearTaskReminder(taskId)
                    } else {
                        core.updateTask(taskId, null, null, null, null, null, reminder, null, null, null)
                    }
                }
                loadTasksForDate(selectedDate)
                app.notifyDataChanged(setOf(DataType.TASK_DATA))
            } catch (e: Exception) {
                onError("更新提醒失败: ${e.message}")
            }
        }
    }

    /**
     * 拖拽释放后的重排回调 —— 日程中所有任务保持顶层（parent_id=null），仅更新 sort_order。
     * @param draggedId 被拖拽的任务 ID
     * @param newParentId 新父任务 ID（日程中始终忽略，保持 null）
     * @param siblingIds 拖拽后同级任务的 ID 列表（新排序）
     */
    fun reorderTasks(draggedId: String, newParentId: String?, siblingIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.reorderTasks(siblingIds.toList())
                }
            } catch (e: Exception) {
                onError("排序失败: ${e.message}")
            }
        }
    }

    /**
     * 加载指定月份范围的日历标记。
     * 范围：上月第一天 ~ 下月最后一天（确保日历网格四周都有数据）。
     * @param year 年份
     * @param month 月份 (1-12)
     */
    fun loadCalendarMarkers(year: Int, month: Int) {
        viewModelScope.launch {
            try {
                val firstOfMonth = LocalDate.of(year, month, 1)
                // 向前扩展到上周一，确保日历第一行有数据
                val start = firstOfMonth.minusDays(7)
                // 下月最后一天向后扩展
                val nextMonth = firstOfMonth.plusMonths(1)
                val end = nextMonth.plusDays(6)
                val startStr = start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endStr = end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val markers = withContext(Dispatchers.IO) {
                    core.getCalendarDateMarkers(startStr, endStr)
                }
                dateMarkers = markers.associateBy { it.date }
            } catch (e: Exception) {
                onError("加载日历标记失败: ${e.message}")
            }
        }
    }

    /**
     * 加载指定日期的任务列表。
     */
    private fun loadTasksForDate(date: LocalDate) {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    core.getTasksForDate(dateStr).map { it.toScheduleItem() }
                }
                tasks = result
            } catch (e: Exception) {
                onError("加载日程失败: ${e.message}")
            }
        }
    }

    /** 将 Rust 端 DailyTaskStatus 转换为 ScheduleTaskItem */
    private fun DailyTaskStatus.toScheduleItem(): ScheduleTaskItem {
        val isDaily = task.recurrenceRule == "daily"
        return ScheduleTaskItem(
            id = task.id,
            name = task.name,
            description = task.description,
            priority = task.priority,
            isDone = completedForDate,
            isDaily = isDaily,
            reminderTime = task.recurrenceReminderTime,
            reminder = task.reminder,
        )
    }
}

/** 将 ScheduleTaskItem 映射为共享任务卡片数据模型 */
fun ScheduleTaskItem.toCardModel(): TaskCardModel = TaskCardModel(
    id = id,
    name = name,
    description = description,
    priority = priority,
    isDone = isDone,
    isDaily = isDaily,
    reminderTime = reminderTime,
    reminder = reminder,
)

/** 将 ScheduleTaskItem 转为 TaskItem（字段从 DailyTaskStatus 派生得来，缺少 createdAt/focusSeconds） */
fun ScheduleTaskItem.toTaskItem(): TaskItem = TaskItem(
    id = id,
    name = name,
    description = description,
    priority = priority,
    isDone = isDone,
    createdAt = "",
    subtasks = emptyList(),
    recurrenceRule = if (isDaily) "daily" else null,
    recurrenceReminderTime = reminderTime,
    reminder = reminder,
    isDaily = isDaily,
    isCompletedForDate = isDone,
)

/** 将 Rust 端 TaskData 转为 TaskItem */
fun TaskData.toTaskItem(): TaskItem = TaskItem(
    id = id,
    name = name,
    description = description,
    priority = priority,
    isDone = status == "done",
    createdAt = createdAt,
    subtasks = emptyList(),
    focusSeconds = focusSeconds,
    recurrenceRule = recurrenceRule,
    recurrenceReminderTime = recurrenceReminderTime,
    reminder = reminder,
    isDaily = recurrenceRule == "daily",
    isCompletedForDate = status == "done",
)

/** 将 ScheduleTaskItem 转为 FlatTaskItem，所有日程任务 depth=0，自成一组 */
fun ScheduleTaskItem.toFlatItem(): FlatTaskItem = FlatTaskItem(
    task = this.toTaskItem(),
    depth = 0,
    parentId = null,
    isGroupFirst = true,
    isGroupLast = true,
)

@Composable
fun scheduleViewModel(): ScheduleViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ScheduleViewModel(app, app.core(), { msg ->
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }) as T
            }
        }
    )
}
