package com.echonion.nion.ui.companion.sticker

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.echonion.nion.util.BitmapUtils
import java.io.File
import java.util.UUID

/**
 * 表情包服务层 —— 管理表情图片的存储路径、文件复制和位图缓存。
 * 纯 Kotlin 单例，无 Compose / ViewModel 依赖。
 *
 * 设计要点：
 * - 统一的存储目录（nion_data/stickers/），创建时自动确保目录存在
 * - LRU 内存缓存（max 5MB）避免重复解码，聊天列表滚动时不再重复 IO
 * - 按需采样解码：缩略图和聊天渲染均根据目标像素自适应采样率
 *   自适应采样是解决锯齿/模糊问题的关键 —— 确保解码后尺寸 ≥ 目标显示像素
 */
object StickerService {

    /** 内存 LRU 缓存：键为文件路径（或路径@目标像素），值为解码后的 Bitmap */
    private val bitmapCache = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 获取或创建表情包内部存储目录。
     *
     * @param context Application Context
     * @return nion_data/stickers/ 目录的 File 对象
     */
    private fun getStickerDir(context: Context): File {
        val dir = File(context.getDir("nion_data", Context.MODE_PRIVATE), "stickers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 将源文件复制到内部表情目录，使用 UUID 重命名避免冲突。
     *
     * @param context Application Context
     * @param sourcePath 图片源文件的绝对路径
     * @param fileName 原始文件名（仅用于提取扩展名，如 .png）
     * @return 目标文件对象，失败时返回 null
     */
    fun copyToStorage(context: Context, sourcePath: String, fileName: String): File? {
        return try {
            val dir = getStickerDir(context)
            val ext = fileName.substringAfterLast('.', "png")
            val destFile = File(dir, "${UUID.randomUUID()}.$ext")
            File(sourcePath).copyTo(destFile, overwrite = true)
            destFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 加载缩略图 Bitmap —— 自适应采样率，专用于列表卡片展示。
     *
     * 默认目标尺寸 160px（约 40dp @4x 屏幕），通过 BitmapUtils 自适应计算采样率，
     * 确保解码后图片尺寸 ≥ 目标像素，避免小图被过度降采样导致模糊。
     *
     * @param filePath 图片文件路径
     * @param targetSizePx 目标显示像素（宽高相同），默认 160px
     * @return 解码后的 Bitmap，失败时返回 null
     */
    fun loadThumbnail(filePath: String, targetSizePx: Int = 160): Bitmap? {
        val cached = bitmapCache.get(filePath)
        if (cached != null) return cached

        return try {
            val bitmap = BitmapUtils.decodeFileAdaptive(filePath, targetSizePx, targetSizePx)
            if (bitmap != null) bitmapCache.put(filePath, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 加载聊天内联渲染用 Bitmap —— 根据目标像素自适应采样率。
     *
     * 通过 BitmapUtils 两步解码：先 inJustDecodeBounds 获取原图尺寸，
     * 再计算合适的 inSampleSize，使解码后尺寸 ≥ 目标像素。
     * 这样 Compose 只需做小幅缩放，图片清晰不模糊。
     *
     * @param filePath 图片文件路径
     * @param targetSizePx 目标显示的最小像素尺寸
     * @return 解码后的 Bitmap，失败时返回 null
     */
    fun loadForRender(filePath: String, targetSizePx: Int): Bitmap? {
        val cacheKey = "${filePath}@${targetSizePx}"
        val cached = bitmapCache.get(cacheKey)
        if (cached != null) return cached

        return try {
            val bitmap = BitmapUtils.decodeFileAdaptive(filePath, targetSizePx, targetSizePx)
            if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从缓存中移除指定文件的位图（删除表情包时调用，避免显示幽灵缓存）。
     *
     * @param filePath 图片文件路径
     */
    fun evictSticker(filePath: String) {
        bitmapCache.remove(filePath)
    }

    /**
     * 清空所有缓存（内存压力回调时调用）。
     */
    fun clearCache() {
        bitmapCache.evictAll()
    }
}
