package com.echonion.nion.reminder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 震动工具类 —— 为悬浮窗提醒和专注完成提供统一的震动反馈。
 *
 * 震动模式分为四级，由弱到强：
 * - **轻震** (LIGHT)：200ms 单次，用于问候、专注完成、低优先级/低次数任务提醒
 * - **中震** (MEDIUM)：[300, 100, 300]，用于天气 warning、任务 level 3-4
 * - **强震** (HEAVY)：[500, 100, 500, 100, 500]，用于天气 urgent、任务 level 5-6
 * - **超强震** (ULTRA)：[500, 100, 500, 100, 500, 100, 500]，用于任务 level 7（高优先级 + 多次提醒）
 *
 * 任务提醒的 level 计算方式：
 *   level = priorityWeight (low=0, medium=1, high=2) + triggerCount (1~5)
 *   范围 1~7，映射到不同震动强度。
 *
 * 需要在 AndroidManifest.xml 中声明 android.permission.VIBRATE 权限。
 */
object VibrationHelper {

    private const val TAG = "VibrationHelper"

    /**
     * 轻震 —— 200ms 单次震动。
     * 用于：问候、专注完成、任务 level 1-2。
     */
    fun vibrateLight(context: Context) {
        vibrate(context, longArrayOf(0, 200))
    }

    /**
     * 中震 —— 300ms 震动，停 100ms，再 300ms 震动。
     * 用于：天气 warning、任务 level 3-4。
     */
    fun vibrateMedium(context: Context) {
        vibrate(context, longArrayOf(0, 300, 100, 300))
    }

    /**
     * 强震 —— 三次 500ms 震动，间隔 100ms。
     * 用于：天气 urgent、任务 level 5-6。
     */
    fun vibrateHeavy(context: Context) {
        vibrate(context, longArrayOf(0, 500, 100, 500, 100, 500))
    }

    /**
     * 超强震 —— 四次 500ms 震动，间隔 100ms。
     * 用于：任务 level 7（高优先级 + 第 5 次提醒）。
     */
    fun vibrateUltra(context: Context) {
        vibrate(context, longArrayOf(0, 500, 100, 500, 100, 500, 100, 500))
    }

    /**
     * 任务提醒震动 —— 根据任务优先级和提醒次数综合决定震动强度。
     *
     * level = priorityWeight + triggerCount：
     * - level 1-2：轻震（如 low+1, medium+1, low+2）
     * - level 3-4：中震（如 high+1, low+3, medium+2）
     * - level 5-6：强震（如 high+3, medium+4）
     * - level 7：  超强震（high+5）
     *
     * @param context 上下文，用于获取 Vibrator 系统服务
     * @param priority 任务优先级："low" / "medium" / "high"
     * @param triggerCount 当前提醒次数（1-5）
     */
    fun vibrateForReminder(context: Context, priority: String, triggerCount: Int) {
        val priorityWeight = when (priority) {
            "high" -> 2
            "medium" -> 1
            else -> 0
        }
        val level = priorityWeight + triggerCount

        Log.d(TAG, "任务提醒震动: priority=$priority, triggerCount=$triggerCount, level=$level")

        when {
            level >= 7 -> vibrateUltra(context)
            level >= 5 -> vibrateHeavy(context)
            level >= 3 -> vibrateMedium(context)
            else -> vibrateLight(context)
        }
    }

    /**
     * 天气预警震动 —— 根据严重程度决定震动强度。
     *
     * - info：不震动（仅提示信息）
     * - warning：中震
     * - urgent：强震
     *
     * @param context 上下文
     * @param severity 严重程度："info" / "warning" / "urgent"
     */
    fun vibrateForWeatherAlert(context: Context, severity: String) {
        Log.d(TAG, "天气预警震动: severity=$severity")
        when (severity) {
            "urgent" -> vibrateHeavy(context)
            "warning" -> vibrateMedium(context)
            // info 级别不震动
        }
    }

    /**
     * 执行震动 —— 兼容 Android 12+ (VibratorManager) 和旧版 (Vibrator)。
     *
     * @param context 上下文
     * @param pattern 震动模式数组，格式：[等待, 震动, 等待, 震动, ...]
     */
    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 通过 VibratorManager 获取 Vibrator
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                // Android 11 及以下直接获取 Vibrator
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "设备无震动器，跳过震动")
                return
            }

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "震动执行失败", e)
        }
    }
}
