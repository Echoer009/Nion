package com.echonion.nion.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 图片解码工具类 —— 提供自适应采样率计算和便捷解码方法。
 *
 * 核心思路：先用 inJustDecodeBounds 获取原图尺寸，再根据目标显示尺寸计算
 * 合适的 inSampleSize（2 的幂），使解码后尺寸 ≥ 目标像素，避免过度降采样导致模糊。
 */
object BitmapUtils {

    /**
     * 根据目标宽高计算 BitmapFactory 的采样率（inSampleSize）。
     *
     * 算法：取 2 的幂，确保解码后的图片宽高都 ≥ 目标尺寸。
     * 即在满足目标尺寸的前提下尽可能降低分辨率以节省内存。
     *
     * @param options 已经执行过 inJustDecodeBounds 的 Options 对象
     * @param reqWidth 目标宽度（像素）
     * @param reqHeight 目标高度（像素）
     * @return 采样率（1, 2, 4, 8, ...）
     */
    fun calculateSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 从文件路径自适应解码 Bitmap —— 根据目标尺寸自动计算采样率。
     *
     * 两步解码：先 inJustDecodeBounds 获取尺寸 → 计算采样率 → 真正解码。
     * 确保解码后图片宽高都 ≥ 目标尺寸，避免模糊。
     *
     * @param filePath 图片文件的绝对路径
     * @param targetWidthPx 目标显示宽度（像素），应考虑屏幕密度
     * @param targetHeightPx 目标显示高度（像素），应考虑屏幕密度
     * @return 解码后的 Bitmap，失败时返回 null
     */
    fun decodeFileAdaptive(
        filePath: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = calculateSampleSize(bounds, targetWidthPx, targetHeightPx)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(filePath, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 ContentResolver URI 自适应解码 Bitmap —— 根据目标尺寸自动计算采样率。
     *
     * 用于 content:// URI（如系统相册选择的头像图片）。
     * 两步解码：先 inJustDecodeBounds 获取尺寸 → 计算采样率 → 真正解码。
     *
     * @param contentResolver ContentResolver 实例
     * @param uri 图片 URI
     * @param targetWidthPx 目标显示宽度（像素），应考虑屏幕密度
     * @param targetHeightPx 目标显示高度（像素），应考虑屏幕密度
     * @return 解码后的 Bitmap，失败时返回 null
     */
    fun decodeUriAdaptive(
        contentResolver: ContentResolver,
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? {
        return try {
            // 第一步：获取原图尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // 第二步：计算采样率并解码
            val sampleSize = calculateSampleSize(bounds, targetWidthPx, targetHeightPx)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }
}
