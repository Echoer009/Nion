package com.echonion.nion.ui.focus

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.echonion.nion.NionApp
import com.echonion.nion.core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore

/**
 * 专注计时器 ViewModel —— Activity 作用域，导航切换不丢失状态。
 *
 * 持有计时器的全部运行状态（时长、剩余秒数、运行标志、关联任务等），
 * 以及倒计时协程。协程运行在 viewModelScope 中，即使 FocusScreen
 * 因用户切换导航页而被销毁，计时器仍持续工作，用户切回专注页时
 * 自动恢复显示。
 *
 * 专注完成/中断（≥5分钟）时，检查 focus_completion_enabled 开关，
 * 收集上下文数据（任务名、时长、今日统计等），通过 NionApp 事件总线
 * 发出 CompletionEvent，由 CompletionOverlay 接收并展示鼓励文案。
 *
 * @param app Application 实例，用于获取 NionCore 和发送事件
 * @param core NionCore 实例，用于持久化专注时长到数据库
 */
class FocusTimerViewModel(private val app: Application, private val core: NionCore) : ViewModel() {

    /** 计时器是否正在运行 */
    var isRunning by mutableStateOf(false)
        private set

    /** 剩余秒数 */
    var remainingSeconds by mutableIntStateOf(25 * 60)
        private set

    /** 用户设置的专注时长（分钟） */
    var focusMinutes by mutableIntStateOf(25)
        private set

    /** 本次专注已进行的秒数，用于中断时 5 分钟规则判断 */
    var elapsedSeconds by mutableIntStateOf(0)
        private set

    /** 已完成的专注会话次数 */
    var completedSessions by mutableIntStateOf(0)
        private set

    /** 当前关联的任务 ID，null 表示未关联 */
    var selectedTaskId by mutableStateOf<String?>(null)
        private set

    /** 当前关联的任务标题 */
    var selectedTaskTitle by mutableStateOf<String?>(null)
        private set

    /**
     * 进度条是否需要瞬间重置（而非动画过渡）。
     * Composable 读取此标记后执行 snapTo 并调用 clearProgressSnap()。
     */
    var needsProgressSnap by mutableStateOf(false)
        private set

    /** 设定总时长（秒） */
    val totalSeconds: Int get() = focusMinutes * 60

    /** 剩余时间占总时间的比例 (0~1) */
    val progress: Float
        get() = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds.toFloat() else 1f

    /** 倒计时协程的 Job，用于暂停/重置时取消 */
    private var countdownJob: Job? = null

    /**
     * 应用从外部（任务详情页）传入的预选任务信息。
     *
     * 由 FocusScreen 的 LaunchedEffect(preselectedTaskId) 调用，
     * LaunchedEffect 的 key 机制保证同一个 preselectedTaskId 不会重复触发，
     * 因此不再需要额外的去重字段。
     *
     * @param taskId 预选任务 ID，null 则跳过
     * @param taskTitle 预选任务标题
     * @param duration 预选专注时长（分钟），null 保持当前设置
     * @param autoStart 是否自动启动计时
     */
    fun applyPreselection(
        taskId: String?,
        taskTitle: String?,
        duration: Int?,
        autoStart: Boolean,
    ) {
        if (taskId == null) return

        selectedTaskId = taskId
        selectedTaskTitle = taskTitle
        if (duration != null) {
            focusMinutes = duration
        }
        remainingSeconds = focusMinutes * 60
        needsProgressSnap = true
        if (autoStart) {
            start()
        }
    }

    /**
     * 设置专注时长（分钟），同时重置剩余秒数。
     * 仅在计时器未运行时生效。
     *
     * @param minutes 目标时长（分钟）
     */
    fun setDuration(minutes: Int) {
        if (isRunning) return
        focusMinutes = minutes
        remainingSeconds = minutes * 60
        needsProgressSnap = true
    }

    /**
     * 选择要关联的任务。
     *
     * @param taskId 任务 ID，null 表示不关联
     * @param taskTitle 任务标题
     */
    fun selectTask(taskId: String?, taskTitle: String?) {
        selectedTaskId = taskId
        selectedTaskTitle = taskTitle
    }

    /** 切换运行/暂停状态 */
    fun toggleRunning() {
        if (isRunning) pause() else start()
    }

    /** 启动计时器（若已在运行则跳过） */
    fun start() {
        if (isRunning) return
        isRunning = true
        startCountdown()
    }

    /** 暂停计时器，取消倒计时协程 */
    fun pause() {
        isRunning = false
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * 重置计时器到初始状态。
     *
     * 停止计时，恢复剩余秒数为设定时长，清零已用秒数。
     * 设置 needsProgressSnap 让 Composable 瞬间重置进度动画。
     */
    fun reset() {
        isRunning = false
        countdownJob?.cancel()
        countdownJob = null
        remainingSeconds = focusMinutes * 60
        elapsedSeconds = 0
        needsProgressSnap = true
    }

    /**
     * 提前结束专注。
     *
     * 5 分钟规则：
     * - 已专注 < 5 分钟（300 秒）→ 不记录
     * - 已专注 >= 5 分钟 → 按实际耗时累加到关联任务的 focus_seconds
     *
     * 设置 needsProgressSnap 让 Composable 瞬间重置进度动画。
     * 符合条件时发出 CompletionEvent（isEarlyStop=true）。
     */
    fun stopEarly() {
        // reset 前必须先取值，否则协程执行时 state 已被重置为 0
        val capturedElapsed = elapsedSeconds
        val elapsedMin = capturedElapsed / 60
        val shouldEmit = capturedElapsed >= 300

        // 不再要求 isRunning，暂停后点停止也要检查 5 分钟规则
        if (capturedElapsed >= 300 && selectedTaskId != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        core.addFocusTime(selectedTaskId!!, capturedElapsed.toLong())
                    } catch (_: Exception) {
                    }
                }
            }
        }
        isRunning = false
        countdownJob?.cancel()
        countdownJob = null
        remainingSeconds = focusMinutes * 60
        elapsedSeconds = 0
        needsProgressSnap = true

        // 中断但 ≥5 分钟，发出完成事件
        if (shouldEmit) {
            emitCompletionEvent(elapsedMin, isEarlyStop = true)
        }
    }

    /** 清除进度瞬间重置标记（由 Composable 在执行 snapTo 后调用） */
    fun clearProgressSnap() {
        needsProgressSnap = false
    }

    /**
     * 专注完成/中断后收集上下文数据并发出 CompletionEvent。
     *
     * 工作流程：
     * 1. 检查 focus_completion_enabled 开关，关闭则跳过
     * 2. 在 IO 线程收集数据：今日专注统计、任务累计时长
     * 3. 构建 CompletionEvent 并通过 NionApp.postCompletionEvent 发出
     *
     * @param elapsedMinutes 本次实际专注分钟数
     * @param isEarlyStop 是否提前结束
     */
    private fun emitCompletionEvent(elapsedMinutes: Int, isEarlyStop: Boolean) {
        viewModelScope.launch {
            try {
                // 检查专注鼓励开关，关闭则不发事件
                val enabled = core.getSetting("focus_completion_enabled")
                if (enabled == "false") return@launch

                val nionApp = app as? NionApp ?: return@launch

                // 在 IO 线程收集数据
                val data = withContext(Dispatchers.IO) {
                    // 查询今日专注统计（最近 1 天）
                    val stats = core.getFocusStats(1)
                    val today = java.time.LocalDate.now().toString()
                    val todayStat = stats.daily.find { it.date == today }
                    val todaySessions = todayStat?.sessionCount?.toInt() ?: 0
                    val todayMinutes = (todayStat?.totalSeconds?.toInt() ?: 0) / 60

                    // 查询任务累计专注时长
                    val totalMinutes = if (selectedTaskId != null) {
                        try {
                            val task = core.getTask(selectedTaskId!!)
                            (task.focusSeconds / 60).toInt()
                        } catch (_: Exception) {
                            elapsedMinutes
                        }
                    } else {
                        elapsedMinutes
                    }

                    Triple(todaySessions, todayMinutes, totalMinutes)
                }

                val (todaySessions, todayMinutes, totalMinutes) = data

                nionApp.postCompletionEvent(
                    CompletionEvent(
                        taskName = selectedTaskTitle,
                        sessionMinutes = elapsedMinutes,
                        plannedMinutes = focusMinutes,
                        totalMinutes = totalMinutes,
                        todaySessions = todaySessions,
                        todayMinutes = todayMinutes,
                        isEarlyStop = isEarlyStop,
                    )
                )
            } catch (e: Exception) {
                Log.w("FocusTimerViewModel", "发送专注完成事件失败", e)
            }
        }
    }

    /**
     * 启动倒计时协程。
     *
     * 每秒递减 remainingSeconds 并递增 elapsedSeconds。
     * 倒计时结束时自动停止、+1 session、累加完整时长到关联任务。
     * 使用 Job 管理生命周期，确保暂停/重置时能正确取消已有协程，
     * 避免快速暂停→再启动导致多个协程同时递减。
     */
    private fun startCountdown() {
        // 先取消已有协程，防止重复
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isRunning) {
                if (remainingSeconds > 0) {
                    delay(1000)
                    remainingSeconds--
                    elapsedSeconds++
                } else {
                    // 倒计时完成
                    isRunning = false
                    completedSessions++
                    // 累加完整设定时长到关联任务
                    if (selectedTaskId != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                core.addFocusTime(selectedTaskId!!, totalSeconds.toLong())
                            } catch (_: Exception) {
                            }
                        }
                    }
                    // 自然完成，发出鼓励事件
                    emitCompletionEvent(focusMinutes, isEarlyStop = false)
                    // 重置计时器回到就绪状态，等待下一轮
                    elapsedSeconds = 0
                    remainingSeconds = totalSeconds
                    needsProgressSnap = true
                    break
                }
            }
        }
    }

    /**
     * ViewModel 工厂 —— 注入 NionCore 实例。
     *
     * @param app Application 实例，用于获取 NionCore 单例
     */
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FocusTimerViewModel(app, app.core()) as T
        }
    }
}
