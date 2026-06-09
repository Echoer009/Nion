package com.echonion.nion.ui.companion.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phone Agent 无障碍服务 —— 提供截图、手势执行、全局操作等能力。
 *
 * 以系统级权限运行，通过 Android 无障碍框架获取操控手机的能力。
 * 用户需在"设置 → 无障碍"中手动开启此服务。
 *
 * 通信方式：通过 [PhoneAgentBridge] 单例与上层代码通信。
 */
class PhoneAgentService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAgentService"
    }

    // ── 生命周期 ──────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "PhoneAgentService 已连接")
        PhoneAgentBridge.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "已销毁")
        PhoneAgentBridge.onServiceDisconnected()
    }

    // ── 截图 ──────────────────────────────────────────────────────

    /**
     * 截取当前屏幕，返回 base64 编码的 PNG。
     *
     * 使用 AccessibilityService.takeScreenshot()，需要 Android 9+ (API 28+)。
     * 在低版本设备上返回 null。
     */
    fun takeScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "截图需要 Android 9+")
            return null
        }
        try {
            val latch = CountDownLatch(1)
            var resultBase64: String? = null
            val executor = ContextCompat.getMainExecutor(this)

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            if (bitmap != null) {
                                val os = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                                resultBase64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "截图处理失败", e)
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截图失败, code=$errorCode")
                        latch.countDown()
                    }
                }
            )

            latch.await(5, TimeUnit.SECONDS)
            return resultBase64
        } catch (e: Exception) {
            Log.e(TAG, "截图异常", e)
            return null
        }
    }

    // ── 屏幕尺寸 ──────────────────────────────────────────────────

    /** 获取屏幕尺寸（像素） */
    fun getScreenSize(): Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    // ── 手势派发辅助 ──────────────────────────────────────────────

    /**
     * 派发手势的便捷方法，自动补充 CancellationSignal 和 callback 参数。
     *
     * Android API 33+ 废弃了单参数的 dispatchGesture(GestureDescription)，
     * compileSdk 36 要求必须使用三参数版本。
     */
    private fun dispatchGestureCompat(gesture: GestureDescription): Boolean {
        return dispatchGesture(gesture, null, null)
    }

    // ── 手势执行 ──────────────────────────────────────────────────

    /** 点击 */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGestureCompat(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
        )
    }

    /** 双击，两次点击间隔 100ms */
    fun doubleTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val ok = dispatchGestureCompat(
            GestureDescription.Builder().addStroke(stroke).build()
        )
        if (!ok) return false
        Thread.sleep(100)
        return dispatchGestureCompat(
            GestureDescription.Builder().addStroke(stroke).build()
        )
    }

    /** 长按，持续 3000ms */
    fun longPress(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        return dispatchGestureCompat(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 3000))
                .build()
        )
    }

    /** 滑动，持续时间按距离自动计算 300~1000ms */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val dx = (endX - startX).toDouble()
        val dy = (endY - startY).toDouble()
        val distance = Math.sqrt(dx * dx + dy * dy).toInt()
        val duration = distance.coerceIn(300, 1000).toLong()
        return dispatchGestureCompat(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
        )
    }

    // ── 全局操作 ──────────────────────────────────────────────────

    /** 返回键 */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Home 键 */
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // ── 文本输入 ──────────────────────────────────────────────────

    /** 通过剪贴板写入文本，然后通过无障碍节点执行粘贴操作 */
    fun inputText(text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("phone_agent", text))
        Log.d(TAG, "已写入剪贴板: ${text.take(50)}")

        // 查找获取焦点的可编辑节点并执行粘贴
        val focused = findFocusedEditableNode(rootInActiveWindow)
        if (focused != null) {
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "执行粘贴: $result")
            focused.recycle()
            return result
        }

        // 没找到焦点，查找第一个可编辑节点
        val editable = findFirstEditableNode(rootInActiveWindow)
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(200)
            val result = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "查找可编辑节点并粘贴: $result")
            editable.recycle()
            return result
        }

        Log.w(TAG, "未找到可编辑节点")
        return false
    }

    /** 递归查找获取焦点的可编辑节点 */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /** 递归查找第一个可编辑节点 */
    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    // ── 启动应用 ──────────────────────────────────────────────────

    /** 通过包名启动应用 */
    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "已启动: $packageName")
                true
            } else {
                Log.e(TAG, "未找到应用: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: $packageName", e)
            false
        }
    }
}
