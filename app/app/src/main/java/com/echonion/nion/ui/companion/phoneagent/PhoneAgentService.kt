package com.echonion.nion.ui.companion.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
            Log.d(TAG, "takeScreenshotBase64: 开始截图...")
            val latch = CountDownLatch(1)
            var resultBase64: String? = null
            val executor = ContextCompat.getMainExecutor(this)

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        Log.d(TAG, "takeScreenshotBase64: onSuccess")
                        try {
                            val rawBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            if (rawBitmap != null) {
                                // 将截图缩放到最大 2048x2048 以内，兼容 ModelScope 等平台的图片尺寸限制
                                val maxDim = 2048
                                val bitmap = if (rawBitmap.width > maxDim || rawBitmap.height > maxDim) {
                                    val scale = minOf(
                                        maxDim.toFloat() / rawBitmap.width,
                                        maxDim.toFloat() / rawBitmap.height,
                                    )
                                    val newW = (rawBitmap.width * scale).toInt()
                                    val newH = (rawBitmap.height * scale).toInt()
                                    Log.d(TAG, "takeScreenshotBase64: 缩放 ${rawBitmap.width}x${rawBitmap.height} -> ${newW}x${newH}")
                                    Bitmap.createScaledBitmap(rawBitmap, newW, newH, true).also {
                                        rawBitmap.recycle()
                                    }
                                } else {
                                    rawBitmap
                                }
                                Log.d(TAG, "takeScreenshotBase64: bitmap=${bitmap.width}x${bitmap.height}")
                                // 使用 JPEG 压缩，减小 base64 体积（PNG 体积过大）
                                val os = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, os)
                                resultBase64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
                                Log.d(TAG, "takeScreenshotBase64: base64长度=${resultBase64?.length}")
                                bitmap.recycle()
                            } else {
                                Log.e(TAG, "takeScreenshotBase64: bitmap为null")
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

    /**
     * 获取当前前台 App 的名称。
     *
     * 从 rootInActiveWindow 获取包名，再通过 AppPackages 反查中文名。
     * 如果找不到中文名则直接返回包名。
     */
    fun getCurrentAppName(): String {
        val window = rootInActiveWindow ?: return "Unknown"
        val packageName = window.packageName?.toString() ?: return "Unknown"
        val chineseName = AppPackages.getAppName(packageName)
        return chineseName
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

    /**
     * 在当前焦点输入框中输入文本。
     *
     * 三层策略：
     * 1. 查找可输入节点 → ACTION_SET_TEXT 或剪贴板粘贴
     * 2. 查找焦点节点（即使不满足 isInputNode）→ 尝试剪贴板粘贴
     * 3. 终极 fallback: 剪贴板 + 点击当前焦点位置 → 粘贴
     */
    fun inputText(text: String): Boolean {
        // 策略1: 查找标准可输入节点
        var node = findFocusedEditableNode(rootInActiveWindow)
        if (node == null) {
            node = findFirstEditableNode(rootInActiveWindow)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(200)
                node.recycle()
                node = findFocusedEditableNode(rootInActiveWindow)
            }
        }

        if (node != null) {
            // 尝试 ACTION_SET_TEXT
            val setTextResult = trySetTextNode(node, text)
            if (setTextResult) {
                node.recycle()
                return true
            }
            // Fallback: 剪贴板粘贴
            Log.d(TAG, "ACTION_SET_TEXT 不支持，fallback 到剪贴板粘贴")
            val pasteResult = tryClipboardPaste(node, text)
            node.recycle()
            if (pasteResult) return true
        }

        // 策略2: 查找任何获得焦点的节点（微信等 App 的自定义输入框可能没有 isEditable 标记）
        Log.d(TAG, "标准可输入节点未找到，尝试查找任意焦点节点")
        val focusedAny = findFocusedNode(rootInActiveWindow)
        if (focusedAny != null) {
            Log.d(TAG, "找到焦点节点: ${focusedAny.className}, text=${focusedAny.text}, hintText=${focusedAny.hintText}")
            // 先把文本写入剪贴板
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("phone_agent", text))
            Thread.sleep(100)
            // 尝试 ACTION_PASTE
            val pasteResult = focusedAny.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "焦点节点粘贴: $pasteResult")
            focusedAny.recycle()
            if (pasteResult) return true
        }

        Log.w(TAG, "未找到可编辑节点，输入失败")
        return false
    }

    /**
     * 尝试用 ACTION_SET_TEXT 直接设置文本。
     * 先清空（SET_TEXT("")），再写入目标文本。
     * 返回 true 表示成功，false 表示节点不支持此操作。
     */
    private fun trySetTextNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // 检查节点是否支持 SET_TEXT（API 21+）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

        // 先清空已有内容
        val clearArgs = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        Thread.sleep(100)

        // 写入目标文本
        val setArgs = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        Log.d(TAG, "ACTION_SET_TEXT: $result, text=${text.take(50)}")
        return result
    }

    /**
     * 剪贴板粘贴 fallback。
     * 先全选(ACTION_SET_SELECTION 全选) + 删除，再写入剪贴板并粘贴。
     */
    private fun tryClipboardPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 尝试先清空已有内容：全选 + 删除
        try {
            // 全选
            val selectArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
            Thread.sleep(50)
            // 删除选中内容
            node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            Thread.sleep(50)
        } catch (_: Exception) {}

        // 写入剪贴板并粘贴
        clipboard.setPrimaryClip(ClipData.newPlainText("phone_agent", text))
        val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "剪贴板粘贴 fallback: $result, text=${text.take(50)}")
        return result
    }

    /** 递归查找获取焦点的可编辑节点。放宽判断：isEditable 或是常见输入框类名或支持 SET_TEXT */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && isInputNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /** 递归查找第一个可编辑节点。放宽判断：isEditable 或是常见输入框类名或支持 SET_TEXT */
    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isInputNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /**
     * 递归查找当前获得焦点的任意节点（不要求 isEditable）。
     * 用于微信等 App 的自定义输入框：焦点在一个非标准 Editable 节点上，
     * 但实际上可以通过 ACTION_PASTE 粘贴文本。
     */
    private fun findFocusedNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNode(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /**
     * 判断节点是否为可输入节点。多级放宽条件，覆盖各种自定义控件：
     * - isEditable = true（标准条件）
     * - 类名包含 Edit / Input / TextView 等输入框关键词（覆盖微信、淘宝等自定义控件）
     * - 支持 ACTION_SET_TEXT（通过 actionList 检查）
     * - inputType != 0（设置了输入类型的控件）
     * - 有 hintText 且可点击（搜索框等场景）
     */
    private fun isInputNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        // 匹配各类输入框类名：EditText, EasyEditText(微信), MMEditText(微信), Input等
        if (className.contains("Edit", ignoreCase = true)) return true
        if (className.contains("Input", ignoreCase = true) && !className.contains("Layout", ignoreCase = true)) return true
        if (className.contains("AutoCompleteTextView", ignoreCase = true)) return true
        if (className.contains("TextView", ignoreCase = true) && node.isEditable) return true
        if (node.inputType != 0 && node.inputType != InputType.TYPE_NULL) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (node.actionList?.contains(AccessibilityNodeInfo.ACTION_SET_TEXT as Any) == true) return true
        }
        // 微信等 App 的搜索框：有 hintText（提示文字）且可点击
        if (node.hintText?.isNotEmpty() == true && node.isClickable) return true
        return false
    }

    // ── 启动应用 ──────────────────────────────────────────────────

    /** 通过包名启动应用 */
    fun launchApp(packageName: String): Boolean {
        return try {
            // 先尝试标准方式
            var launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "已启动(getLaunchIntent): $packageName")
                return true
            }

            // fallback: 查询 LAUNCHER category 的 Activity 并直接构造 ComponentName
            Log.w(TAG, "getLaunchIntentForPackage 返回 null，尝试 LAUNCHER 查询")
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            // 使用 MATCH_ALL 避免 system app 被过滤
            val resolveInfos = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            if (resolveInfos.isNotEmpty()) {
                val activity = resolveInfos[0].activityInfo
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activity.packageName, activity.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "已启动(queryIntent): $packageName/${activity.name}")
                true
            } else {
                Log.e(TAG, "未找到应用: $packageName (queryIntentActivities 也为空)")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: $packageName", e)
            false
        }
    }
}
