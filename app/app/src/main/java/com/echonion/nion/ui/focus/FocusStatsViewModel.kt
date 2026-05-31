package com.echonion.nion.ui.focus

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 每日专注统计数据。
 *
 * @property date 日期字符串 "YYYY-MM-DD"
 * @property totalSeconds 当天累计专注秒数
 * @property sessionCount 当天专注会话次数
 */
data class DailyStat(
    val date: String,
    val totalSeconds: Long,
    val sessionCount: Int,
)

/**
 * 按任务分布的专注统计。
 *
 * @property taskId 任务 ID
 * @property taskTitle 任务标题（已删除的任务显示为"(已删除)"）
 * @property seconds 该任务累计专注秒数
 */
data class TaskStat(
    val taskId: String,
    val taskTitle: String,
    val seconds: Long,
)

/**
 * 专注统计面板状态。
 *
 * @property daily 每日专注分布列表
 * @property taskBreakdown 按任务分布的专注统计
 * @property totalSeconds 当前周期总专注秒数
 * @property totalSessions 当前周期总专注会话数
 * @property isLoading 是否正在加载数据
 */
data class FocusStatsState(
    val daily: List<DailyStat> = emptyList(),
    val taskBreakdown: List<TaskStat> = emptyList(),
    val totalSeconds: Long = 0,
    val totalSessions: Int = 0,
    val isLoading: Boolean = false,
)

/**
 * 专注统计 ViewModel —— 直接查询 SQLite focus_sessions 表获取数据。
 *
 * 不使用 UniFFI 绑定以避免需要重新生成绑定代码。
 * 查询只读，不会与 Rust 端的写操作冲突。
 */
class FocusStatsViewModel(
    private val dbPath: String,
) : ViewModel() {

    /** 当前面板状态 */
    var state by mutableStateOf(FocusStatsState())
        private set

    /** 当前选中的统计周期（天数）：1=今天, 7=本周, 30=本月 */
    var selectedDays by mutableIntStateOf(1)
        private set

    /** 首次加载数据 */
    init {
        loadStats()
    }

    /**
     * 切换统计周期并重新加载数据。
     *
     * @param days 查询天数：1/7/30
     */
    fun selectPeriod(days: Int) {
        selectedDays = days
        loadStats()
    }

    /** 加载（或刷新）当前周期的专注统计数据 */
    fun loadStats() {
        viewModelScope.launch {
            // 仅在无缓存数据时才显示加载指示器，避免切换周期时闪白
            if (state.daily.isEmpty()) {
                state = state.copy(isLoading = true)
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    queryStats(dbPath, selectedDays)
                }
                state = result.copy(isLoading = false)
            } catch (_: Exception) {
                state = state.copy(isLoading = false)
            }
        }
    }

    companion object {
        /**
         * 查询 focus_sessions 表获取专注统计。
         *
         * @param dbPath SQLite 数据库文件路径
         * @param days 查询天数
         * @return FocusStatsState 统计结果
         */
        fun queryStats(dbPath: String, days: Int): FocusStatsState {
            val db = SQLiteDatabase.openDatabase(
                dbPath, null, SQLiteDatabase.OPEN_READONLY
            )

            return try {
                val daily = mutableListOf<DailyStat>()
                val taskBreakdown = mutableListOf<TaskStat>()

                // 每日汇总
                db.rawQuery(
                    """SELECT substr(created_at, 1, 10) as d, SUM(seconds) as total, COUNT(*) as cnt
                       FROM focus_sessions
                       WHERE created_at >= date('now', '-' || ? || ' days')
                       GROUP BY d
                       ORDER BY d DESC""",
                    arrayOf(days.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        daily.add(
                            DailyStat(
                                date = cursor.getString(0),
                                totalSeconds = cursor.getLong(1),
                                sessionCount = cursor.getInt(2),
                            )
                        )
                    }
                }

                // 任务分布
                db.rawQuery(
                    """SELECT s.task_id, COALESCE(t.name, '(已删除)') as name, SUM(s.seconds) as total
                       FROM focus_sessions s
                       LEFT JOIN tasks t ON t.id = s.task_id
                       WHERE s.created_at >= date('now', '-' || ? || ' days')
                       GROUP BY s.task_id
                       ORDER BY total DESC""",
                    arrayOf(days.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        taskBreakdown.add(
                            TaskStat(
                                taskId = cursor.getString(0),
                                taskTitle = cursor.getString(1),
                                seconds = cursor.getLong(2),
                            )
                        )
                    }
                }

                // 汇总
                var totalSeconds = 0L
                var totalSessions = 0
                db.rawQuery(
                    """SELECT COALESCE(SUM(seconds), 0), COUNT(*)
                       FROM focus_sessions
                       WHERE created_at >= date('now', '-' || ? || ' days')""",
                    arrayOf(days.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        totalSeconds = cursor.getLong(0)
                        totalSessions = cursor.getInt(1)
                    }
                }

                FocusStatsState(
                    daily = daily,
                    taskBreakdown = taskBreakdown,
                    totalSeconds = totalSeconds,
                    totalSessions = totalSessions,
                )
            } finally {
                db.close()
            }
        }
    }

    /** ViewModel 工厂 */
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val dataDir = app.getExternalFilesDir(null) ?: app.getDir("nion_data", 0)
            val dbPath = File(dataDir, "nion.db").absolutePath
            return FocusStatsViewModel(dbPath) as T
        }
    }
}
