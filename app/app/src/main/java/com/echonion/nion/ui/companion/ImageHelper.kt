package com.echonion.nion.ui.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 图片处理工具 —— 负责 URI → 压缩 → base64 编码的完整流程。
 *
 * 用于将用户选择的图片转换为 LLM API 可接受的多模态格式。
 * 压缩策略：保持原始画质，仅在图片超大时降采样防止 OOM。
 *
 * 流程：
 * 1. 从 Content URI 读取图片原始尺寸（不加载到内存）
 * 2. 计算采样率（inSampleSize），仅超大图降采样
 * 3. 解码为 Bitmap
 * 4. 以原始格式（JPEG/PNG）压缩输出
 * 5. 转 base64 字符串
 */
object ImageHelper {

    private const val TAG = "ImageHelper"

    /**
     * 图片最大边长限制 —— 超过此尺寸的图片会被降采样。
     * 设置为 2048px，在保留画质和防止 OOM 之间取得平衡。
     */
    private const val MAX_DIMENSION = 2048

    /**
     * JPEG 压缩质量（0-100）。
     * 85 是一个很好的平衡点：肉眼几乎看不出质量损失，文件体积比 100 小很多。
     */
    private const val JPEG_QUALITY = 85

    /**
     * 图片处理结果 —— 包含 base64 编码数据和 MIME 类型。
     *
     * @property base64 图片的 base64 编码字符串（不含 data: 前缀）
     * @property mimeType 图片的 MIME 类型（如 "image/jpeg"、"image/png"）
     */
    data class ImageData(
        val base64: String,
        val mimeType: String,
    )

    /**
     * 从 Content URI 读取图片并编码为 base64。
     *
     * 处理流程：
     * 1. 检测图片原始 MIME 类型
     * 2. 测量原始尺寸，计算采样率
     * 3. 解码并压缩为 JPEG 或 PNG
     * 4. 输出 base64 编码字符串
     *
     * 此方法应在 IO 线程调用（耗时操作）。
     *
     * @param context Android Context，用于解析 Content URI
     * @param uri     图片的 Content URI（来自图片选择器）
     * @return [ImageData] 包含 base64 数据和 MIME 类型，失败时返回 null
     */
    fun loadAndEncode(context: Context, uri: Uri): ImageData? {
        return try {
            // 第一步：检测 MIME 类型，决定输出格式
            val mimeType = detectMimeType(context, uri)
            Log.d(TAG, "原始 MIME: $mimeType")

            // 第二步：只读取尺寸，不加载完整图片到内存
            val (origWidth, origHeight) = decodeBounds(context, uri)
            Log.d(TAG, "原始尺寸: ${origWidth}x${origHeight}")

            // 第三步：计算采样率，超大图降采样防止 OOM
            val sampleSize = calculateSampleSize(origWidth, origHeight)
            Log.d(TAG, "采样率: $sampleSize")

            // 第四步：解码为 Bitmap
            val bitmap = decodeBitmap(context, uri, sampleSize) ?: return null

            // 第五步：压缩并编码为 base64
            val outputMime = if (mimeType.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
            val compressFormat = if (outputMime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (compressFormat == Bitmap.CompressFormat.PNG) 100 else JPEG_QUALITY

            val byteArray = ByteArrayOutputStream().use { baos ->
                bitmap.compress(compressFormat, quality, baos)
                baos.toByteArray()
            }

            // 释放 Bitmap 内存
            if (sampleSize > 1) bitmap.recycle()

            val base64Str = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            Log.d(TAG, "编码完成: ${byteArray.size / 1024}KB → base64 ${base64Str.length / 1024}KB")

            ImageData(base64 = base64Str, mimeType = outputMime)
        } catch (e: Exception) {
            Log.e(TAG, "图片处理失败", e)
            null
        }
    }

    /**
     * 检测图片的 MIME 类型。
     * 优先使用 ContentResolver 获取，失败时回退到文件扩展名猜测。
     *
     * @param context Android Context
     * @param uri     图片 URI
     * @return MIME 类型字符串，如 "image/jpeg"
     */
    private fun detectMimeType(context: Context, uri: Uri): String {
        // ContentResolver 能获取准确的 MIME 类型
        val cr = context.contentResolver
        val mime = cr.getType(uri)
        if (!mime.isNullOrEmpty()) return mime

        // 回退：根据扩展名猜测
        val path = uri.lastPathSegment ?: return "image/jpeg"
        return when {
            path.contains(".png", ignoreCase = true) -> "image/png"
            path.contains(".webp", ignoreCase = true) -> "image/webp"
            path.contains(".gif", ignoreCase = true) -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /**
     * 只读取图片尺寸，不加载完整像素数据到内存。
     * 通过 BitmapFactory.Options.inJustDecodeBounds = true 实现。
     *
     * @param context Android Context
     * @param uri     图片 URI
     * @return (width, height) 图片原始尺寸
     */
    private fun decodeBounds(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return Pair(options.outWidth, options.outHeight)
    }

    /**
     * 计算降采样率（inSampleSize）。
     * 当图片任一边超过 [MAX_DIMENSION] 时，按 2 的幂次降采样。
     * inSampleSize 必须是 2 的幂次（1, 2, 4, 8...），
     * 这是 Android BitmapFactory 的要求。
     *
     * @param width  原始宽度
     * @param height 原始高度
     * @return 采样率，1 表示不降采样
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return 1
        var sampleSize = 1
        while (width / sampleSize > MAX_DIMENSION || height / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 从 URI 解码 Bitmap，应用降采样率。
     *
     * @param context    Android Context
     * @param uri        图片 URI
     * @param sampleSize 降采样率（1 = 原始尺寸）
     * @return 解码后的 Bitmap，失败时返回 null
     */
    private fun decodeBitmap(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }
}
