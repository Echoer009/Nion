package com.echonion.nion.ui.companion.phoneagent

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/**
 * Phone Agent 桥接单例 —— ViewModel/Loop 层与 AccessibilityService 之间的通信桥梁。
 *
 * 设计原因：AccessibilityService 由系统管理生命周期，不能直接被 Activity/ViewModel 引用。
 * 通过单例模式让 ViewModel 层能够访问 Service 的能力（截图、手势、全局操作）。
 *
 * 生命周期：
 * - Service.onServiceConnected() → 调用 onServiceConnected() 注册实例
 * - Service.onDestroy() → 调用 onServiceDisconnected() 清除实例
 * - 其他组件通过 isServiceRunning 检查 Service 是否可用
 */
object PhoneAgentBridge {

    private const val TAG = "PhoneAgentBridge"

    /** 当前已连接的 AccessibilityService 实例，null 表示未启动 */
    private var service: PhoneAgentService? = null

    // ── Service 生命周期回调 ──────────────────────────────────────

    /**
     * AccessibilityService 连接时调用，注册 Service 实例。
     */
    fun onServiceConnected(service: PhoneAgentService) {
        this.service = service
        Log.d(TAG, "PhoneAgentService 已注册")
    }

    /**
     * AccessibilityService 断开时调用，清除 Service 实例。
     */
    fun onServiceDisconnected() {
        this.service = null
        Log.d(TAG, "PhoneAgentService 已注销")
    }

    // ── 状态查询 ──────────────────────────────────────────────────

    /**
     * 检查 AccessibilityService 是否正在运行。
     *
     * @return true 表示 Service 可用，可以进行截图和手势操作
     */
    fun isServiceRunning(): Boolean = service != null

    // ── 截图 ──────────────────────────────────────────────────────

    /**
     * 截取当前屏幕。
     *
     * @return base64 编码的 PNG 字符串，Service 未运行时返回 null
     */
    fun takeScreenshot(): String? {
        val s = service ?: run {
            Log.w(TAG, "Service 未运行，无法截图")
            return null
        }
        return s.takeScreenshotBase64()
    }

    /**
     * 获取屏幕尺寸（像素）。
     *
     * @return Point(x=宽度, y=高度)，Service 未运行时返回 null
     */
    fun getScreenSize(): Point? {
        return service?.getScreenSize()
    }

    // ── 手势操作 ──────────────────────────────────────────────────

    /**
     * 点击指定坐标。
     * @param x 绝对 X 像素坐标
     * @param y 绝对 Y 像素坐标
     * @return 是否成功，Service 未运行返回 false
     */
    fun tap(x: Int, y: Int): Boolean {
        return service?.tap(x, y) ?: false
    }

    /**
     * 双击指定坐标。
     * @param x 绝对 X 像素坐标
     * @param y 绝对 Y 像素坐标
     * @return 是否成功
     */
    fun doubleTap(x: Int, y: Int): Boolean {
        return service?.doubleTap(x, y) ?: false
    }

    /**
     * 长按指定坐标。
     * @param x 绝对 X 像素坐标
     * @param y 绝对 Y 像素坐标
     * @return 是否成功
     */
    fun longPress(x: Int, y: Int): Boolean {
        return service?.longPress(x, y) ?: false
    }

    /**
     * 从起点滑动到终点。
     * @param startX 起始 X
     * @param startY 起始 Y
     * @param endX   终止 X
     * @param endY   终止 Y
     * @return 是否成功
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int): Boolean {
        return service?.swipe(startX, startY, endX, endY) ?: false
    }

    // ── 全局操作 ──────────────────────────────────────────────────

    /**
     * 按返回键。
     * @return 是否成功
     */
    fun pressBack(): Boolean {
        return service?.pressBack() ?: false
    }

    /**
     * 按 Home 键。
     * @return 是否成功
     */
    fun pressHome(): Boolean {
        return service?.pressHome() ?: false
    }

    // ── 文本输入 ──────────────────────────────────────────────────

    /**
     * 通过剪贴板输入文本（假设焦点已在输入框中）。
     * @param text 要输入的文本
     * @return 是否成功写入剪贴板
     */
    fun inputText(text: String): Boolean {
        return service?.inputText(text) ?: false
    }

    // ── 启动应用 ──────────────────────────────────────────────────

    /**
     * 通过包名启动指定应用。
     * @param packageName 包名（如 com.tencent.mm）
     * @return 是否成功启动
     */
    fun launchApp(packageName: String): Boolean {
        return service?.launchApp(packageName) ?: false
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    /**
     * 将 AutoGLM 的 0-999 相对坐标转换为屏幕绝对像素坐标。
     *
     * AutoGLM 模型输出的坐标范围是 [0, 999]，
     * 映射到实际屏幕尺寸：absolute = relative * screenDim / 1000
     *
     * @param relativeCoords 相对坐标数组 [x, y]，范围 0-999
     * @param screenSize 屏幕尺寸 Point(width, height)
     * @return 绝对像素坐标 IntArray [x, y]
     */
    fun convertToAbsolute(relativeCoords: List<Int>, screenSize: Point): IntArray {
        val x = relativeCoords[0] * screenSize.x / 1000
        val y = relativeCoords[1] * screenSize.y / 1000
        return intArrayOf(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    /**
     * 检查当前 App 的 AccessibilityService 是否已开启。
     *
     * 通过查询 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 系统设置来判断。
     *
     * @param context 上下文
     * @return true 表示已开启
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${PhoneAgentService::class.java.canonicalName}"
        var enabledServices: String? = null
        try {
            enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {}
        if (enabledServices.isNullOrBlank()) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 打开系统无障碍设置页面，引导用户开启 Phone Agent Service。
     *
     * @param context 上下文
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
