package com.echonion.nion.ui.focus

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
 * @property core NionCore 实例，用于持久化专注时长到数据库
 */
class FocusTimerViewModel(private val core: NionCore) : ViewModel() {

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

    /** 已消费的预选任务 ID，防止重复应用同一预选 */
    private var consumedPreselectedId: String? = null

    /** 倒计时协程的 Job，用于暂停/重置时取消 */
    private var countdownJob: Job? = null

    /**
     * 应用从外部（任务详情页）传入的预选任务信息。
     *
     * 通过 consumedPreselectedId 去重，确保同一个预选只应用一次，
     * 即使 FocusScreen 因导航被重建也不会重复触发。
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
        // taskId 为空或已经被消费过 → 跳过
        if (taskId == null || taskId == consumedPreselectedId) return
        consumedPreselectedId = taskId

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
     */
    fun stopEarly() {
        // 不再要求 isRunning，暂停后点停止也要检查 5 分钟规则
        if (elapsedSeconds >= 300 && selectedTaskId != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        core.addFocusTime(selectedTaskId!!, elapsedSeconds.toLong())
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
    }

    /** 清除进度瞬间重置标记（由 Composable 在执行 snapTo 后调用） */
    fun clearProgressSnap() {
        needsProgressSnap = false
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
                    elapsedSeconds = 0
                    break
                }
            }
        }
    }

    /**
     * ViewModel 工厂 —— 注入 NionCore 实例。
     *
     * @property app Application 实例，用于获取 NionCore 单例
     */
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FocusTimerViewModel(app.core()) as T
        }
    }
}
