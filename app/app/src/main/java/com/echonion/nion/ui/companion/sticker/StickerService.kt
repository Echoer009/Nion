package com.echonion.nion.ui.companion.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.UUID

/**
 * 表情包服务层 —— 管理表情图片的存储路径、文件复制和位图缓存。
 * 纯 Kotlin 单例，无 Compose / ViewModel 依赖。
 *
 * 设计要点：
 * - 统一的存储目录（nion_data/stickers/），创建时自动确保目录存在
 * - LRU 内存缓存（max 5MB）避免重复解码，聊天列表滚动时不再重复 IO
 * - 按需采样解码：缩略图固定 4× 降采样，聊天渲染根据目标像素自适应采样率
 *   自适应采样是解决锯齿问题的关键 —— 避免把 2000×2000 原图硬缩到 50×50
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
     * 加载缩略图 Bitmap —— 固定 4 倍降采样，专用于列表卡片展示。
     * 图幅约 40dp（~160px @4x），4× 降采样后约为原始尺寸的 1/16 内存。
     *
     * @param filePath 图片文件路径
     * @return 解码后的 Bitmap，失败时返回 null
     */
    fun loadThumbnail(filePath: String): Bitmap? {
        val cached = bitmapCache.get(filePath)
        if (cached != null) return cached

        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(filePath, options)
            if (bitmap != null) bitmapCache.put(filePath, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 加载聊天内联渲染用 Bitmap —— 根据目标像素自适应采样率。
     *
     * 解决锯齿的核心逻辑：
     * 1. 先用 inJustDecodeBounds 获取原始尺寸
     * 2. 计算合适的 inSampleSize，使解码后尺寸刚好 ≥ 目标像素
     * 3. 再用 computed sampleSize 真正解码
     * 这样 Compose 只需做小幅缩放（而非从全分辨率暴力压缩），边缘自然清晰。
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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            val rawMin = minOf(bounds.outWidth, bounds.outHeight)
            if (rawMin <= 0) return null

            // 采样率 = 原图最小边 / 目标像素，确保解码后最小边 ≥ targetSizePx
            val sampleSize = maxOf(1, rawMin / targetSizePx)

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(filePath, decodeOpts)
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
