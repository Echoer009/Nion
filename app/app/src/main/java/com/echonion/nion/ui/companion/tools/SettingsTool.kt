package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.ThemePalette
import org.json.JSONObject

/**
 * 设置管理工具 —— AI 伴伴通过此工具读取和修改应用设置。
 *
 * 当前支持的设置类型：
 * - **主题配色**：切换预设主题、自定义任意颜色槽位、创建全新自定义主题
 *
 * 操作类型（通过 action 参数路由）：
 * - `get_theme` — 获取当前主题的完整颜色配置（所有 30 个颜色槽位）
 * - `switch_theme` — 切换到预设主题（CORAL / AMBER）
 * - `update_colors` — 部分更新颜色槽位（只传要修改的字段，其余保持不变）
 * - `reset_theme` — 重置为当前预设的默认颜色（清除自定义覆盖）
 *
 * 颜色格式：所有颜色值使用 "#RRGGBB" 格式（如 "#D4845A"）。
 *
 * 使用示例：
 * - "换成琥珀主题" → action=switch_theme, theme_name=AMBER
 * - "把主色调换成蓝色" → action=update_colors, colors={"primary":"#2196F3","primaryContainer":"#BBDEFB","darkPrimary":"#64B5F6"}
 * - "现在用的什么配色" → action=get_theme
 * - "恢复默认颜色" → action=reset_theme
 */
object SettingsTool : Tool {

    override val name = "settings"

    override val description = """
管理应用设置。当前支持主题配色控制：
- 获取当前主题的所有颜色配置
- 切换预设主题（珊瑚 CORAL / 琥珀 AMBER）
- 自定义任意颜色槽位（primary、secondary、background 等共 30 个）
- 重置为预设默认颜色

颜色格式统一使用 "#RRGGBB"（如 "#D4845A"）。
修改颜色后应用会立即刷新，用户能实时看到效果。
    """.trimIndent()

    override val affectsData = setOf(DataType.SETTINGS)

    override fun parametersSchema(): JSONObject {
        // 颜色槽位描述（动态拼接，避免特殊字符破坏 JSON 模板）
        val colorSlotList = ThemePalette.COLOR_KEYS.joinToString(", ")
        val colorSlotDesc = buildString {
            append("要修改的颜色槽位键值对，键为槽位名（")
            append(colorSlotList)
            append("），值为 #RRGGBB 格式的颜色。")
            append("只需传入要修改的槽位，未传的保持当前值不变。")
            append("常用槽位：primary=主色调, primaryContainer=主色容器, secondary=辅色, ")
            append("lightBackground=亮色背景, darkPrimary=暗色主色调。")
            append("建议修改主色时同时调整对应容器色和暗色变体以保持和谐。")
        }

        // 动态构建 schema，避免字符串模板中的特殊字符破坏 JSON 结构
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray().apply {
                        put("get_theme")
                        put("switch_theme")
                        put("update_colors")
                        put("reset_theme")
                    })
                    put("description", "操作类型：get_theme=获取当前配色, switch_theme=切换预设, update_colors=修改颜色, reset_theme=恢复预设默认")
                })
                put("theme_name", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray().apply {
                        put("CORAL")
                        put("AMBER")
                    })
                    put("description", "预设主题名称（仅 switch_theme 操作需要）：CORAL=珊瑚暖橘色, AMBER=琥珀暖黄色")
                })
                put("colors", JSONObject().apply {
                    put("type", "object")
                    put("description", colorSlotDesc)
                    put("additionalProperties", JSONObject().apply {
                        put("type", "string")
                    })
                })
            })
            put("required", org.json.JSONArray().apply {
                put("action")
            })
        }
    }

    override suspend fun execute(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val action = params.getString("action")
        return when (action) {
            "get_theme" -> executeGetTheme(core)
            "switch_theme" -> executeSwitchTheme(params, core)
            "update_colors" -> executeUpdateColors(params, core)
            "reset_theme" -> executeResetTheme(core)
            else -> """{"error":"未知的 action: $action"}"""
        }
    }

    /**
     * 获取当前主题的完整颜色配置。
     * 返回所有 30 个颜色槽位的当前值（#RRGGBB 格式）+ 当前模式（preset/custom）。
     */
    private fun executeGetTheme(core: uniffi.nion_core.NionCore): String {
        val mode = try { core.getSetting("theme_mode") ?: "preset" } catch (_: Exception) { "preset" }
        val palette = loadCurrentPalette(core)
        val json = palette.toJson()
        json.put("_mode", mode)
        // 如果是预设模式，附加预设名称
        if (mode == "preset") {
            val presetName = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
            json.put("_preset", presetName)
        }
        return json.toString()
    }

    /**
     * 切换到预设主题。
     * 将 theme_mode 设为 preset，更新 color_theme，清除 custom_theme。
     */
    private fun executeSwitchTheme(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val themeName = params.optString("theme_name", "")
        val preset = NionColorTheme.entries.find { it.name == themeName }
        if (preset == null) {
            return """{"error":"未知的预设主题: $themeName，可用: ${NionColorTheme.entries.joinToString(", ") { it.name }}}"""
        }

        core.setSetting("theme_mode", "preset")
        core.setSetting("color_theme", preset.name)
        core.setSetting("custom_theme", "")

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已切换到${preset.label}主题")
        result.put("theme_name", preset.name)
        return result.toString()
    }

    /**
     * 部分更新颜色槽位。
     * 读取当前色板（预设或自定义），应用覆盖，保存为自定义主题。
     */
    private fun executeUpdateColors(params: JSONObject, core: uniffi.nion_core.NionCore): String {
        val colorsObj = params.optJSONObject("colors")
        if (colorsObj == null || colorsObj.length() == 0) {
            return """{"error":"colors 参数不能为空，至少需要指定一个颜色槽位"}"""
        }

        // 解析并验证颜色值
        val overrides = mutableMapOf<String, String>()
        val keys = colorsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = colorsObj.getString(key)
            // 验证颜色槽位名
            if (key !in ThemePalette.COLOR_KEYS) {
                return """{"error":"未知的颜色槽位: $key，可用槽位: ${ThemePalette.COLOR_KEYS.joinToString(", ")}}"""
            }
            // 验证颜色值格式
            try {
                android.graphics.Color.parseColor(value)
            } catch (_: Exception) {
                return """{"error":"无效的颜色值: $value，请使用 "#RRGGBB" 格式（如 "#D4845A"）"}"""
            }
            overrides[key] = value
        }

        // 加载当前色板，应用覆盖
        val currentPalette = loadCurrentPalette(core)
        val newPalette = currentPalette.withOverrides(overrides)

        // 保存为自定义主题
        core.setSetting("theme_mode", "custom")
        core.setSetting("custom_theme", newPalette.toJson().toString())

        // 只返回实际修改的键值对，不返回完整 palette 以节省上下文
        val updatedColors = JSONObject()
        for ((k, v) in overrides) {
            updatedColors.put(k, v)
        }
        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已更新 ${overrides.size} 个颜色槽位")
        result.put("updated", updatedColors)
        return result.toString()
    }

    /**
     * 重置为当前预设的默认颜色。
     * 将 theme_mode 设回 preset，清除 custom_theme。
     */
    private fun executeResetTheme(core: uniffi.nion_core.NionCore): String {
        val presetName = try { core.getSetting("color_theme") ?: "CORAL" } catch (_: Exception) { "CORAL" }
        val preset = NionColorTheme.entries.find { it.name == presetName } ?: NionColorTheme.CORAL

        core.setSetting("theme_mode", "preset")
        core.setSetting("custom_theme", "")

        val result = JSONObject()
        result.put("success", true)
        result.put("message", "已重置为${preset.label}主题默认配色")
        result.put("theme_name", preset.name)
        return result.toString()
    }

    /**
     * 从 NionCore 设置中加载当前主题色板。
     * 与 NionApp.loadThemePalette 逻辑一致，但在工具端执行。
     */
    private fun loadCurrentPalette(core: uniffi.nion_core.NionCore): ThemePalette {
        return try {
            val mode = core.getSetting("theme_mode") ?: "preset"
            when (mode) {
                "custom" -> {
                    val json = core.getSetting("custom_theme") ?: return NionColorTheme.CORAL.palette()
                    if (json.isBlank()) return NionColorTheme.CORAL.palette()
                    ThemePalette.fromJson(JSONObject(json))
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
}
