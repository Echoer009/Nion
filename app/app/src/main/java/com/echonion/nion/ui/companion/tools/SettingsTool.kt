package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.ThemeDerivation
import com.echonion.nion.ui.theme.ThemePalette
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 设置管理工具 —— AI 伴伴通过此工具读取和修改应用设置。
 *
 * 当前支持的设置类型：
 * - **主题配色**：切换预设主题、用种子色创建自定义主题、精确微调任意颜色槽位
 *
 * 操作类型（通过 action 参数路由）：
 * - `get_theme` — 获取当前主题的完整颜色配置（21 个颜色槽位 + 种子色信息）
 * - `list_themes` — 列出所有可用主题（预设 + 自定义）
 * - `create_theme` — 用种子色创建全新自定义主题并自动激活
 * - `switch_theme` — 切换到预设主题或已有自定义主题
 * - `update_colors` — 更新当前活跃主题的颜色（支持种子色 + 精确覆盖）
 * - `rename_theme` — 重命名自定义主题
 * - `delete_theme` — 删除自定义主题
 * - `reset_theme` — 重置为当前预设的默认颜色
 *
 * 种子色机制：
 * - 传 7 个种子色（primary / secondary / tertiary / background /
 *   priorityHigh / priorityMedium / priorityLow）即可自动派生完整 21 槽位色板
 * - 种子色可选，传了哪个就派生哪个色系，未传的保持当前值
 * - color_overrides 可在种子派生基础上精确覆盖任意单个槽位
 *
 * 颜色格式：所有颜色值使用 "#RRGGBB" 格式（如 "#D4845A"）。
 */
object SettingsTool : Tool {

    override val name = "settings"

    override val description = """
管理应用主题配色。支持以下操作：

1. **种子色创建主题**：传 7 个核心种子色即可自动生成完整配色方案
   - primary（主色）、secondary（辅色）、tertiary（第三色）
   - background（背景色）
   - priorityHigh/Medium/Low（优先级颜色）
   系统会自动派生容器色、表面梯度等 21 个颜色槽位。

2. **精确微调**：通过 color_overrides 可对任意单个槽位精确赋值（在种子派生之上覆盖）

3. **预设切换**：在珊瑚/琥珀预设之间切换

4. **多主题管理**：支持创建、列出、重命名、删除多个自定义主题

颜色格式统一使用 "#RRGGBB"（如 "#D4845A"）。
修改颜色后应用会立即刷新，用户能实时看到效果。
    """.trimIndent()

    override val affectsData = setOf(DataType.SETTINGS)

    override fun parametersSchema(): JSONObject {
        // 种子色描述
        val seedDesc = buildString {
            append("种子色键值对，传了哪个就派生哪个色系的全部变体。")
            append("可选种子：")
            ThemeDerivation.SEED_KEYS.forEach { key ->
                val desc = ThemeDerivation.SEED_DESCRIPTIONS[key] ?: ""
                append("$key — $desc; ")
            }
        }

        // 精确覆盖描述
        val overrideDesc = buildString {
            append("精确覆盖的槽位键值对（在种子派生之上 patch）。")
            append("可用槽位：")
            append(ThemePalette.COLOR_KEYS.joinToString(", "))
            append("。通常不需要，仅用于微调种子派生不满意的个别颜色。")
        }

        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply {
                        put("get_theme")
                        put("list_themes")
                        put("create_theme")
                        put("switch_theme")
                        put("update_colors")
                        put("rename_theme")
                        put("delete_theme")
                        put("reset_theme")
                    })
                    put("description", "操作类型：get_theme=获取当前配色, list_themes=列出所有主题, create_theme=创建自定义主题, switch_theme=切换主题, update_colors=修改颜色, rename_theme=重命名, delete_theme=删除, reset_theme=恢复预设默认")
                })
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "主题名称（create_theme 时必填，rename_theme 时为新名称）")
                })
                put("theme_name", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply {
                        put("CORAL")
                    })
                    put("description", "预设主题名称（switch_theme 切换预设时使用）：CORAL=珊瑚暖橘色")
                })
                put("theme_id", JSONObject().apply {
                    put("type", "string")
                    put("description", "自定义主题 ID（switch_theme / rename_theme / delete_theme 操作时使用）")
                })
                put("seed_colors", JSONObject().apply {
                    put("type", "object")
                    put("description", seedDesc)
                    put("additionalProperties", JSONObject().apply {
                        put("type", "string")
                    })
                })
                put("color_overrides", JSONObject().apply {
                    put("type", "object")
                    put("description", overrideDesc)
                    put("additionalProperties", JSONObject().apply {
                        put("type", "string")
                    })
                })
            })
            put("required", JSONArray().apply {
                put("action")
            })
        }
    }

    override suspend fun execute(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val action = params.getString("action")
        return when (action) {
            "get_theme" -> executeGetTheme(core)
            "list_themes" -> executeListThemes(core)
            "create_theme" -> executeCreateTheme(params, core)
            "switch_theme" -> executeSwitchTheme(params, core)
            "update_colors" -> executeUpdateColors(params, core)
            "rename_theme" -> executeRenameTheme(params, core)
            "delete_theme" -> executeDeleteTheme(params, core)
            "reset_theme" -> executeResetTheme(core)
            else -> """{"error":"未知的 action: $action"}"""
        }
    }

    /**
     * 获取当前主题的完整颜色配置。
     * 返回所有 36 个颜色槽位 + 当前模式 + 种子色信息（如有）。
     */
    private fun executeGetTheme(core: uniffi.nion_core.NionCore): String {
        val mode = try { core.getSetting("theme_mode") ?: "preset" } catch (_: Exception) { "preset" }
        val palette = loadCurrentPalette(core)
        val result = palette.toJson()
        result.put("_mode", mode)

        if (mode == "preset") {
            val presetName = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
            result.put("_preset", presetName)
        } else {
            // 自定义主题：附加 ID 和名称
            val themeId = try { core.getSetting("active_custom_theme_id") ?: "" } catch (_: Exception) { "" }
            val themes = loadCustomThemes(core)
            val entry = themes.find { it.id == themeId }
            if (entry != null) {
                result.put("_theme_id", entry.id)
                result.put("_theme_name", entry.name)
                entry.seedColors?.let { sc ->
                    val scJson = JSONObject()
                    sc.forEach { (k, v) -> scJson.put(k, v) }
                    result.put("_seed_colors", scJson)
                }
            }
        }

        return result.toString()
    }

    /**
     * 列出所有可用主题（预设 + 自定义），返回摘要信息。
     */
    private fun executeListThemes(core: uniffi.nion_core.NionCore): String {
        val mode = try { core.getSetting("theme_mode") ?: "preset" } catch (_: Exception) { "preset" }
        val activePreset = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
        val activeCustomId = try { core.getSetting("active_custom_theme_id") ?: "" } catch (_: Exception) { "" }

        val result = JSONObject()
        result.put("current_mode", mode)

        // 预设主题列表
        val presetsArr = JSONArray()
        NionColorTheme.entries.forEach { theme ->
            val preset = JSONObject()
            preset.put("name", theme.name)
            preset.put("label", theme.label)
            preset.put("is_active", mode == "preset" && theme.name == activePreset)
            val p = theme.palette()
            preset.put("primary", colorToHex(p.primary))
            preset.put("secondary", colorToHex(p.secondary))
            preset.put("tertiary", colorToHex(p.tertiary))
            presetsArr.put(preset)
        }
        result.put("presets", presetsArr)

        // 自定义主题列表
        val themes = loadCustomThemes(core)
        val customArr = JSONArray()
        themes.forEach { entry ->
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("name", entry.name)
            obj.put("is_active", mode == "custom" && entry.id == activeCustomId)
            obj.put("primary", colorToHex(entry.palette.primary))
            obj.put("secondary", colorToHex(entry.palette.secondary))
            obj.put("tertiary", colorToHex(entry.palette.tertiary))
            obj.put("created_at", entry.createdAt)
            customArr.put(obj)
        }
        result.put("custom_themes", customArr)

        return result.toString()
    }

    /**
     * 用种子色创建全新自定义主题并自动激活。
     * 必须提供 name 参数，至少提供一个 seed_colors 或 color_overrides。
     */
    private fun executeCreateTheme(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val name = params.optString("name", "").trim()
        if (name.isBlank()) {
            return """{"error":"创建主题时 name 参数不能为空"}"""
        }

        // 解析种子色和精确覆盖
        val seedColors = parseColorMap(params.optJSONObject("seed_colors"))
        val colorOverrides = parseColorMap(params.optJSONObject("color_overrides"))

        if (seedColors.isEmpty() && colorOverrides.isEmpty()) {
            return """{"error":"至少需要提供 seed_colors 或 color_overrides 之一"}"""
        }

        // 验证颜色值格式
        val validationError = validateColors(seedColors, colorOverrides)
        if (validationError != null) return validationError

        // 以当前色板为基础，通过种子色派生 + 精确覆盖
        val basePalette = loadCurrentPalette(core)
        val newPalette = ThemeDerivation.deriveFromSeeds(
            seedColors = seedColors,
            colorOverrides = colorOverrides,
            basePalette = basePalette,
        )

        // 创建条目并保存
        val id = UUID.randomUUID().toString()
        val entry = CustomThemeEntry(
            id = id,
            name = name,
            palette = newPalette,
            seedColors = seedColors.ifEmpty { null },
            createdAt = System.currentTimeMillis(),
        )

        val themes = loadCustomThemes(core).toMutableList()
        themes.add(entry)
        saveCustomThemes(core, themes)

        // 激活新主题
        core.setSetting("theme_mode", "custom")
        core.setSetting("active_custom_theme_id", id)

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已创建并激活主题「$name」")
        result.put("theme_id", id)
        result.put("theme_name", name)
        return result.toString()
    }

    /**
     * 切换到预设主题或已有自定义主题。
     */
    private fun executeSwitchTheme(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val themeName = params.optString("theme_name", "")
        val themeId = params.optString("theme_id", "")

        // 切换到预设
        if (themeName.isNotBlank()) {
            val preset = NionColorTheme.entries.find { it.name == themeName }
            if (preset == null) {
                return """{"error":"未知的预设主题: $themeName，可用: ${NionColorTheme.entries.joinToString(", ") { it.name }}}"""
            }
            core.setSetting("theme_mode", "preset")
            core.setSetting("color_theme", preset.name)
            core.setSetting("active_custom_theme_id", "")

            val result = JSONObject()
            result.put("success", true)
            result.put("message", "已切换到${preset.label}主题")
            result.put("theme_name", preset.name)
            return result.toString()
        }

        // 切换到自定义主题
        if (themeId.isNotBlank()) {
            val themes = loadCustomThemes(core)
            val entry = themes.find { it.id == themeId }
            if (entry == null) {
                return """{"error":"未找到自定义主题 ID: $themeId"}"""
            }
            core.setSetting("theme_mode", "custom")
            core.setSetting("active_custom_theme_id", entry.id)

            val result = JSONObject()
            result.put("success", true)
            result.put("message", "已切换到自定义主题「${entry.name}」")
            result.put("theme_id", entry.id)
            result.put("theme_name", entry.name)
            return result.toString()
        }

        return """{"error":"需要提供 theme_name（预设）或 theme_id（自定义）参数"}"""
    }

    /**
     * 更新当前活跃主题的颜色。
     * 支持种子色 + 精确覆盖。
     * - 如果当前是自定义主题：直接更新该条目
     * - 如果当前是预设主题：自动创建新自定义主题（需要提供 name）
     */
    private fun executeUpdateColors(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val seedColors = parseColorMap(params.optJSONObject("seed_colors"))
        val colorOverrides = parseColorMap(params.optJSONObject("color_overrides"))

        if (seedColors.isEmpty() && colorOverrides.isEmpty()) {
            return """{"error":"至少需要提供 seed_colors 或 color_overrides"}"""
        }

        val validationError = validateColors(seedColors, colorOverrides)
        if (validationError != null) return validationError

        val mode = try { core.getSetting("theme_mode") ?: "preset" } catch (_: Exception) { "preset" }

        if (mode == "custom") {
            // 更新当前自定义主题
            val activeId = try { core.getSetting("active_custom_theme_id") ?: "" } catch (_: Exception) { "" }
            val themes = loadCustomThemes(core).toMutableList()
            val idx = themes.indexOfFirst { it.id == activeId }
            if (idx < 0) {
                return """{"error":"当前活跃的自定义主题未找到，请先 switch_theme"}"""
            }

            val entry = themes[idx]
            val newPalette = ThemeDerivation.deriveFromSeeds(
                seedColors = seedColors,
                colorOverrides = colorOverrides,
                basePalette = entry.palette,
            )

            // 合并种子色信息
            val newSeedColors = (entry.seedColors ?: emptyMap()) + seedColors

            themes[idx] = entry.copy(palette = newPalette, seedColors = newSeedColors.ifEmpty { null })
            saveCustomThemes(core, themes)

            val result = JSONObject()
            result.put("success", true)
            result.put("message", "已更新主题「${entry.name}」的颜色")
            result.put("updated_seeds", JSONArray(seedColors.keys.toList()))
            result.put("updated_overrides", JSONArray(colorOverrides.keys.toList()))
            return result.toString()
        }

        // 当前是预设主题，自动创建新自定义主题
        val name = params.optString("name", "").trim().ifBlank {
            val presetName = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
            "${presetName}（自定义）"
        }

        val basePalette = loadCurrentPalette(core)
        val newPalette = ThemeDerivation.deriveFromSeeds(
            seedColors = seedColors,
            colorOverrides = colorOverrides,
            basePalette = basePalette,
        )

        val id = UUID.randomUUID().toString()
        val entry = CustomThemeEntry(
            id = id,
            name = name,
            palette = newPalette,
            seedColors = seedColors.ifEmpty { null },
            createdAt = System.currentTimeMillis(),
        )

        val themes = loadCustomThemes(core).toMutableList()
        themes.add(entry)
        saveCustomThemes(core, themes)

        core.setSetting("theme_mode", "custom")
        core.setSetting("active_custom_theme_id", id)

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已基于当前预设创建并激活自定义主题「$name」")
        result.put("theme_id", id)
        result.put("theme_name", name)
        return result.toString()
    }

    /**
     * 重命名自定义主题。
     */
    private fun executeRenameTheme(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val themeId = params.optString("theme_id", "")
        val newName = params.optString("name", "").trim()

        if (themeId.isBlank()) return """{"error":"theme_id 不能为空"}"""
        if (newName.isBlank()) return """{"error":"name 不能为空"}"""

        val themes = loadCustomThemes(core).toMutableList()
        val idx = themes.indexOfFirst { it.id == themeId }
        if (idx < 0) return """{"error":"未找到自定义主题 ID: $themeId"}"""

        val oldName = themes[idx].name
        themes[idx] = themes[idx].copy(name = newName)
        saveCustomThemes(core, themes)

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已将主题「$oldName」重命名为「$newName」")
        return result.toString()
    }

    /**
     * 删除自定义主题。
     * 如果删除的是当前活跃主题，自动切换到 CORAL 预设。
     */
    private fun executeDeleteTheme(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val themeId = params.optString("theme_id", "")
        if (themeId.isBlank()) return """{"error":"theme_id 不能为空"}"""

        val themes = loadCustomThemes(core).toMutableList()
        val entry = themes.find { it.id == themeId }
            ?: return """{"error":"未找到自定义主题 ID: $themeId"}"""

        themes.remove(entry)
        saveCustomThemes(core, themes)

        // 如果删除的是当前活跃主题，切换到 CORAL 预设
        val activeId = try { core.getSetting("active_custom_theme_id") ?: "" } catch (_: Exception) { "" }
        if (activeId == themeId) {
            core.setSetting("theme_mode", "preset")
            core.setSetting("color_theme", "CORAL")
            core.setSetting("active_custom_theme_id", "")
        }

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已删除主题「${entry.name}」")
        return result.toString()
    }

    /**
     * 重置为当前预设的默认颜色。
     */
    private fun executeResetTheme(core: uniffi.nion_core.NionCore): String {
        val presetName = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
        val preset = NionColorTheme.entries.find { it.name == presetName } ?: NionColorTheme.CORAL

        core.setSetting("theme_mode", "preset")
        core.setSetting("active_custom_theme_id", "")

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已重置为${preset.label}主题默认配色")
        result.put("theme_name", preset.name)
        return result.toString()
    }

    // ── 辅助方法 ──

    /**
     * 从 NionCore 设置中加载当前主题色板。
     */
    private fun loadCurrentPalette(core: uniffi.nion_core.NionCore): ThemePalette {
        return try {
            val mode = core.getSetting("theme_mode") ?: "preset"
            when (mode) {
                "custom" -> {
                    val activeId = core.getSetting("active_custom_theme_id") ?: return NionColorTheme.CORAL.palette()
                    if (activeId.isBlank()) return NionColorTheme.CORAL.palette()
                    val themes = loadCustomThemes(core)
                    val entry = themes.find { it.id == activeId }
                        ?: return NionColorTheme.CORAL.palette()
                    entry.palette
                }
                else -> {
                    val name = core.getSetting("color_theme") ?: "CORAL"
                    val preset = NionColorTheme.entries.find { it.name == name } ?: NionColorTheme.CORAL
                    preset.palette()
                }
            }
        } catch (_: Exception) {
            NionColorTheme.CORAL.palette()
        }
    }

    /**
     * 从设置中加载自定义主题列表。
     */
    private fun loadCustomThemes(core: uniffi.nion_core.NionCore): List<CustomThemeEntry> {
        return try {
            val json = core.getSetting("custom_themes_list") ?: return emptyList()
            if (json.isBlank()) return emptyList()
            CustomThemeEntry.listFromJson(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 保存自定义主题列表到设置。
     */
    private fun saveCustomThemes(core: uniffi.nion_core.NionCore, themes: List<CustomThemeEntry>) {
        core.setSetting("custom_themes_list", CustomThemeEntry.listToJson(themes))
    }

    /**
     * 解析 JSON 颜色映射为 Map。
     */
    private fun parseColorMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.getString(key)
        }
        return map
    }

    /**
     * 验证种子色和精确覆盖的颜色格式。
     */
    private fun validateColors(
        seedColors: Map<String, String>,
        colorOverrides: Map<String, String>,
    ): String? {
        // 验证种子色键名
        for (key in seedColors.keys) {
            if (key !in ThemeDerivation.SEED_KEYS) {
                return """{"error":"未知的种子色: $key，可用: ${ThemeDerivation.SEED_KEYS.joinToString(", ")}}"}"""
            }
        }
        // 验证精确覆盖键名
        for (key in colorOverrides.keys) {
            if (key !in ThemePalette.COLOR_KEYS) {
                return """{"error":"未知的颜色槽位: $key，可用槽位: ${ThemePalette.COLOR_KEYS.joinToString(", ")}}"}"""
            }
        }
        // 验证颜色值格式
        val allColors = seedColors + colorOverrides
        for ((key, value) in allColors) {
            try {
                android.graphics.Color.parseColor(value)
            } catch (_: Exception) {
                return """{"error":"无效的颜色值: $value（$key），请使用 "#RRGGBB" 格式"}"""
            }
        }
        return null
    }

    /**
     * 将 Compose Color 转为 hex 字符串。
     */
    private fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }
}
