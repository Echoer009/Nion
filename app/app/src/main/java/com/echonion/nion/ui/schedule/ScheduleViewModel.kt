package com.echonion.nion.ui.schedule

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.CalendarDateMarker
import uniffi.nion_core.DailyTaskStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 日程页面的单日任务 UI 模型，带每日任务完成状态
 */
@Stable
data class ScheduleTaskItem(
    val id: String,
    val title: String,
    val priority: String,
    val isDone: Boolean,
    val isDaily: Boolean,
    val dueDate: String?,
    val reminderTime: String?,
)

/**
 * 日程页面 ViewModel —— 加载指定日期的任务列表和日历标记数据。
 * 数据来源：Rust 端 getTasksForDate / getCalendarDateMarkers。
 */
class ScheduleViewModel(
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

    /**
     * 选择日期并加载该日期的任务。
     * @param date 新的选中日期
     */
    fun selectDate(date: LocalDate) {
        selectedDate = date
        loadTasksForDate(date)
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
     * 切换每日任务在选中日期的完成状态。
     */
    fun toggleDailyDone(taskId: String, date: LocalDate, isCompleted: Boolean) {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isCompleted) {
                        core.uncompleteDailyTask(taskId, dateStr)
                    } else {
                        core.completeDailyTask(taskId, dateStr)
                    }
                }
                // 重新加载该日期的任务
                loadTasksForDate(date)
            } catch (e: Exception) {
                onError("更新任务失败: ${e.message}")
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
            title = task.title,
            priority = task.priority,
            isDone = if (isDaily) completedForDate else (task.status == "done"),
            isDaily = isDaily,
            dueDate = task.dueDate,
            reminderTime = task.recurrenceReminderTime,
        )
    }

    init {
        // 初始化加载今天的任务和当前月的日历标记
        loadTasksForDate(LocalDate.now())
        loadCalendarMarkers(LocalDate.now().year, LocalDate.now().monthValue)
    }
}

@Composable
fun scheduleViewModel(): ScheduleViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ScheduleViewModel(app.core(), { msg ->
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }) as T
            }
        }
    )
}
