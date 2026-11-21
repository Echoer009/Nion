package com.echonion.nion.util

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * 头像文件存储 —— 把系统图片选择器返回的 `content://` 图片落盘成可长期引用的本地文件。
 *
 * 为什么需要它：`PickVisualMedia` 授权只在当前设备/安装内有效（且部分机型不持久化），
 * 重装、换机或清理授权后 `content://media/picker/...` 会失效，头像与备份都会丢掉图片。
 * 因此选中后立即复制字节到外置私有目录，设置里只保存 `file://` URI。
 *
 * 目录规则与历史代码一致：`getExternalFilesDir(null)/avatar`，外置存储不可用时退化为
 * 内部 `nion_data/avatar`。
 */
object AvatarStorage {

    /** 日志 TAG */
    private const val TAG = "AvatarStorage"

    /** 头像目录名（位于外置私有数据目录下） */
    private const val AVATAR_DIR_NAME = "avatar"

    /**
     * 获取头像目录，不存在时创建。
     *
     * @param context 任意 Context
     * @return 头像目录（已 mkdirs）
     */
    fun avatarDir(context: Context): File {
        val dataDir = context.getExternalFilesDir(null)
            ?: context.getDir("nion_data", Context.MODE_PRIVATE)
        val dir = File(dataDir, AVATAR_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 根据 URI 的 MIME 类型推断图片扩展名。
     *
     * 照片选择器不提供文件名，只能靠 MIME；无法识别时按 png 处理
     * （BitmapFactory 通过文件头识别格式，扩展名只影响可读性）。
     *
     * @param context 用于 contentResolver 查询 MIME
     * @param uri 图片 URI
     * @return 不带点的扩展名，如 "jpg"、"png"
     */
    fun imageExtension(context: Context, uri: Uri): String {
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext.isNullOrBlank()) "png" else ext
    }

    /**
     * 把 URI 指向的图片字节复制到指定文件。
     *
     * @param context 用于 contentResolver
     * @param uri 源图片 URI（content:// 或 file://）
     * @param dest 目标文件
     * @return true 复制成功；URI 无法打开或写入失败时返回 false
     */
    fun copyToFile(context: Context, uri: Uri, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "复制头像失败: $uri → $dest", e)
            false
        }
    }
}
