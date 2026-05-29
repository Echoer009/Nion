package com.echonion.nion.preset

import android.content.Context
import android.net.Uri
import android.util.Log
import com.echonion.nion.ui.companion.sticker.StickerService
import java.io.File
import java.io.FileOutputStream

/**
 * 角色预设初始化器 —— 在首次启动时将 CharacterPreset 中的数据写入 DB 和内部存储。
 *
 * 调用时机：CompanionViewModel.loadSettings() 中，DB 数据加载完成后调用。
 * 幂等性：通过 "preset_initialized" 标记位确保只执行一次，重复调用不会重复写入。
 *
 * 初始化流程：
 * 1. 检查 "preset_initialized" 设置，已初始化则跳过
 * 2. 写入伙伴名称（companion_name）
 * 3. 写入所有提示词到 settings 表
 * 4. 复制头像到内部存储并记录 URI
 * 5. 复制表情包到内部存储并注册到 DB
 * 6. 标记初始化完成
 */
object CharacterPresetInitializer {

    private const val TAG = "PresetInit"

    /** 标记位 key —— 值为 preset 的唯一标识（如 "brain_girl"），表示该预设已导入 */
    private const val KEY_PRESET_INITIALIZED = "preset_initialized"

    /**
     * 执行预设初始化。仅在 preset 有数据 且 尚未初始化时执行。
     *
     * @param context Application Context，用于访问 assets 和内部存储
     * @param core NionCore 单例，用于写入 settings 和 stickers 表
     * @param preset 当前 flavor 的角色预设
     */
    fun initializeIfNeeded(
        context: Context,
        core: uniffi.nion_core.NionCore,
        preset: CharacterPreset,
    ) {
        // 空预设（standard flavor）无需初始化
        val name = preset.companionName
        val stickers = preset.stickerAssets
        if (name == null && stickers.isEmpty()) return

        // 检查是否已初始化
        val existingFlag: String? = try {
            core.getSetting(KEY_PRESET_INITIALIZED)
        } catch (_: Exception) {
            null
        }
        if (!existingFlag.isNullOrEmpty()) {
            Log.d(TAG, "预设已初始化 ($existingFlag)，跳过")
            return
        }

        Log.i(TAG, "开始初始化角色预设: $name")
        val presetId = name ?: "unknown"

        try {
            // ── 1. 写入伙伴名称 ──
            if (!name.isNullOrEmpty()) {
                try { core.setSetting("companion_name", name) } catch (_: Exception) {}
            }

            // ── 2. 写入提示词（仅当 DB 中尚未有值时由 PromptDefaults 的迁移逻辑处理，
            //    但这里也显式写入以确保预设值覆盖通用默认值） ──
            val promptMap = mapOf(
                "prompt_persona" to preset.personaPrompt,
                "prompt_format" to preset.formatPrompt,
                "prompt_greeting_morning" to preset.greetingMorning,
                "prompt_greeting_noon" to preset.greetingNoon,
                "prompt_greeting_evening" to preset.greetingEvening,
                "prompt_reminder" to preset.reminderPrompt,
                "prompt_weather_alert" to preset.weatherAlertPrompt,
            )
            for ((key, value) in promptMap) {
                if (!value.isNullOrEmpty()) {
                    try { core.setSetting(key, value) } catch (_: Exception) {}
                }
            }

            // ── 3. 复制头像 ──
            val avatarPath = preset.avatarAssetPath
            if (!avatarPath.isNullOrEmpty()) {
                copyAvatarFromAssets(context, core, avatarPath)
            }

            // ── 4. 复制表情包并注册 ──
            if (stickers.isNotEmpty()) {
                copyStickersFromAssets(context, core, stickers)
            }

            // ── 5. 标记初始化完成 ──
            try { core.setSetting(KEY_PRESET_INITIALIZED, presetId) } catch (_: Exception) {}

            Log.i(TAG, "角色预设初始化完成: $presetId")
        } catch (e: Exception) {
            Log.e(TAG, "角色预设初始化异常", e)
        }
    }

    /**
     * 从 assets 复制头像文件到内部存储，并设置 companion_avatar_uri。
     *
     * @param context Application Context
     * @param core NionCore 单例
     * @param assetPath assets 中的文件路径，如 "avatar.png"
     */
    private fun copyAvatarFromAssets(
        context: Context,
        core: uniffi.nion_core.NionCore,
        assetPath: String,
    ) {
        try {
            val inputStream = context.assets.open(assetPath)
            val avatarDir = File(context.getDir("nion_data", Context.MODE_PRIVATE), "avatar")
            if (!avatarDir.exists()) avatarDir.mkdirs()
            val destFile = File(avatarDir, "avatar.png")

            inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 使用 file:// URI 格式，与用户手动选择头像一致
            val uri = Uri.fromFile(destFile).toString()
            core.setSetting("companion_avatar_uri", uri)
            Log.d(TAG, "头像已复制: $destFile")
        } catch (e: Exception) {
            Log.e(TAG, "复制头像失败: $assetPath", e)
        }
    }

    /**
     * 从 assets 批量复制表情包到内部存储，并注册到 stickers 表。
     *
     * @param context Application Context
     * @param core NionCore 单例
     * @param stickerAssets tag → assets 文件路径 的映射
     */
    private fun copyStickersFromAssets(
        context: Context,
        core: uniffi.nion_core.NionCore,
        stickerAssets: Map<String, String>,
    ) {
        val stickerDir = File(context.getDir("nion_data", Context.MODE_PRIVATE), "stickers")
        if (!stickerDir.exists()) stickerDir.mkdirs()

        for ((tag, assetPath) in stickerAssets) {
            try {
                // 检查是否已存在该 tag 的表情包，避免重复导入
                val existing = core.getStickerByTag(tag)
                if (existing != null) {
                    Log.d(TAG, "表情包已存在，跳过: $tag")
                    continue
                }

                val inputStream = context.assets.open(assetPath)
                val fileName = assetPath.substringAfterLast("/")
                val ext = fileName.substringAfterLast('.', "png")
                val destFile = File(stickerDir, "${java.util.UUID.randomUUID()}.$ext")
                val fileSize: Long

                inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        fileSize = input.copyTo(output)
                    }
                }

                // 注册到 DB
                core.createSticker(tag, fileName, destFile.absolutePath, "image/$ext", fileSize)
                Log.d(TAG, "表情包已导入: $tag → $destFile")
            } catch (e: Exception) {
                Log.e(TAG, "导入表情包失败: $tag ($assetPath)", e)
            }
        }
    }
}
