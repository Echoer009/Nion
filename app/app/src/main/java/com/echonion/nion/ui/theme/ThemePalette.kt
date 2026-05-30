package com.echonion.nion.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主题色板数据类 —— 承载一个主题的全部颜色配置（21 个槽位）。
 *
 * 用途：
 * - 预设主题通过 [NionColorTheme.palette()] 生成实例
 * - 自定义主题通过 JSON 反序列化生成实例
 * - [com.echonion.nion.ui.theme.NionTheme] 接收此数据类构建 Material 3 ColorScheme
 *
 * 序列化格式：所有颜色以 "#RRGGBB" 字符串存储（不含 alpha），
 * 遵循 CSS hex color 规范，方便 AI 伴伴工具直接读写。
 *
 * @property primary 主色（影响按钮、FAB、高亮元素）
 * @property primaryContainer 主色容器（影响 chip 背景、浅色填充）
 * @property onPrimaryContainer 主色容器上的文字/图标
 * @property secondary 辅色
 * @property secondaryContainer 辅色容器
 * @property onSecondaryContainer 辅色容器上的文字
 * @property tertiary 第三色
 * @property tertiaryContainer 第三色容器
 * @property onTertiaryContainer 第三色容器上的文字
 * @property background 全局背景色（页面底色）
 * @property onBackground 背景上的正文颜色
 * @property surfaceVariant 表面变体色（侧边栏/卡片区域背景）
 * @property onSurfaceVariant 表面变体上的次要文字颜色
 * @property surfaceHigh 高强调表面色（分割线区域/Tab背景）
 * @property surfaceHighest 最高强调表面色（弹窗/下拉菜单背景）
 * @property cardBackground 卡片背景色（任务卡片/设置卡片）
 * @property outline 边框线颜色
 * @property outlineVariant 次要边框线颜色（分割线/装饰线）
 * @property priorityHigh 紧急优先级颜色（默认红色）
 * @property priorityMedium 中等优先级颜色（默认深橙色）
 * @property priorityLow 低优先级颜色（默认蓝灰色）
 */
data class ThemePalette(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val cardBackground: Color,
    val outline: Color,
    val outlineVariant: Color,
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityLow: Color,
) {
    companion object {

        /**
         * 所有颜色槽位的键名列表，按构造参数顺序排列。
         * 用于 JSON 序列化/反序列化以及 SettingsTool 的 schema 定义。
         */
        val COLOR_KEYS: List<String> = listOf(
            "primary", "primaryContainer", "onPrimaryContainer",
            "secondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "tertiaryContainer", "onTertiaryContainer",
            "background", "onBackground",
            "surfaceVariant", "onSurfaceVariant",
            "surfaceHigh", "surfaceHighest",
            "cardBackground",
            "outline", "outlineVariant",
            "priorityHigh", "priorityMedium", "priorityLow",
        )

        /**
         * 每个颜色槽位的中文描述，供 AI 伴伴理解用途。
         * 顺序与 [COLOR_KEYS] 一一对应。
         */
        val COLOR_DESCRIPTIONS: Map<String, String> = mapOf(
            "primary" to "主色调，影响按钮/FAB/高亮元素/选中态",
            "primaryContainer" to "主色容器，影响 chip 背景/浅色填充/标签底色",
            "onPrimaryContainer" to "主色容器上的文字和图标颜色",
            "secondary" to "辅色，用于次要操作和装饰元素",
            "secondaryContainer" to "辅色容器",
            "onSecondaryContainer" to "辅色容器上的文字",
            "tertiary" to "第三色，用于对比/点缀",
            "tertiaryContainer" to "第三色容器",
            "onTertiaryContainer" to "第三色容器上的文字",
            "background" to "全局背景色（页面底色）",
            "onBackground" to "背景上的正文颜色",
            "surfaceVariant" to "表面变体色（侧边栏/卡片区域背景）",
            "onSurfaceVariant" to "表面变体上的次要文字颜色",
            "surfaceHigh" to "高强调表面色（分割线区域/Tab背景）",
            "surfaceHighest" to "最高强调表面色（弹窗/下拉菜单背景）",
            "cardBackground" to "卡片背景色（任务卡片/设置卡片）",
            "outline" to "边框线颜色",
            "outlineVariant" to "次要边框线颜色（分割线/装饰线）",
            "priorityHigh" to "紧急优先级颜色（默认红色，用于任务优先级标记）",
            "priorityMedium" to "中等优先级颜色（默认深橙色，用于任务优先级标记）",
            "priorityLow" to "低优先级颜色（默认蓝灰色，用于任务优先级标记）",
        )

        /**
         * 从 JSON 反序列化为 ThemePalette。
         *
         * @param json 包含所有颜色槽位的 JSONObject
         * @return 反序列化后的 ThemePalette 实例
         */
        fun fromJson(json: JSONObject): ThemePalette {
            return ThemePalette(
                primary = parseColor(json, "primary"),
                primaryContainer = parseColor(json, "primaryContainer"),
                onPrimaryContainer = parseColor(json, "onPrimaryContainer"),
                secondary = parseColor(json, "secondary"),
                secondaryContainer = parseColor(json, "secondaryContainer"),
                onSecondaryContainer = parseColor(json, "onSecondaryContainer"),
                tertiary = parseColor(json, "tertiary"),
                tertiaryContainer = parseColor(json, "tertiaryContainer"),
                onTertiaryContainer = parseColor(json, "onTertiaryContainer"),
                background = parseColor(json, "background"),
                onBackground = parseColor(json, "onBackground"),
                surfaceVariant = parseColor(json, "surfaceVariant"),
                onSurfaceVariant = parseColor(json, "onSurfaceVariant"),
                surfaceHigh = parseColor(json, "surfaceHigh"),
                surfaceHighest = parseColor(json, "surfaceHighest"),
                cardBackground = parseColor(json, "cardBackground"),
                outline = parseColor(json, "outline"),
                outlineVariant = parseColor(json, "outlineVariant"),
                priorityHigh = parseColor(json, "priorityHigh"),
                priorityMedium = parseColor(json, "priorityMedium"),
                priorityLow = parseColor(json, "priorityLow"),
            )
        }

        /**
         * 从 JSONObject 中解析指定键的颜色值。
         * 支持 "#RRGGBB" 和 "#AARRGGBB" 两种格式。
         * 如果键不存在，返回 fallback 颜色。
         */
        private fun parseColor(json: JSONObject, key: String): Color {
            if (!json.has(key)) {
                return when (key) {
                    "cardBackground" -> Color.White
                    "priorityHigh" -> Color(0xFFD32F2F)
                    "priorityMedium" -> Color(0xFFF4511E)
                    "priorityLow" -> Color(0xFF607D8B)
                    else -> Color.White
                }
            }
            val hex = json.getString(key)
            val argb = android.graphics.Color.parseColor(hex)
            return Color(argb)
        }
    }

    /**
     * 序列化为 JSON。
     * 所有颜色以 "#RRGGBB" 格式存储，不含 alpha 通道。
     *
     * @return 包含所有颜色槽位的 JSONObject
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("primary", colorToHex(primary))
            put("primaryContainer", colorToHex(primaryContainer))
            put("onPrimaryContainer", colorToHex(onPrimaryContainer))
            put("secondary", colorToHex(secondary))
            put("secondaryContainer", colorToHex(secondaryContainer))
            put("onSecondaryContainer", colorToHex(onSecondaryContainer))
            put("tertiary", colorToHex(tertiary))
            put("tertiaryContainer", colorToHex(tertiaryContainer))
            put("onTertiaryContainer", colorToHex(onTertiaryContainer))
            put("background", colorToHex(background))
            put("onBackground", colorToHex(onBackground))
            put("surfaceVariant", colorToHex(surfaceVariant))
            put("onSurfaceVariant", colorToHex(onSurfaceVariant))
            put("surfaceHigh", colorToHex(surfaceHigh))
            put("surfaceHighest", colorToHex(surfaceHighest))
            put("cardBackground", colorToHex(cardBackground))
            put("outline", colorToHex(outline))
            put("outlineVariant", colorToHex(outlineVariant))
            put("priorityHigh", colorToHex(priorityHigh))
            put("priorityMedium", colorToHex(priorityMedium))
            put("priorityLow", colorToHex(priorityLow))
        }
    }

    /**
     * 将 Compose Color 转换为 "#RRGGBB" 字符串。
     * 取 RGB 三通道（忽略 alpha），格式化为 6 位十六进制。
     */
    private fun colorToHex(color: Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    /**
     * 对指定颜色槽位进行部分更新，返回新的 ThemePalette 实例。
     * 未指定的槽位保持不变。
     *
     * @param overrides 需要更新的颜色槽位键值对，key 为槽位名，value 为 "#RRGGBB" 格式颜色
     * @return 更新后的新 ThemePalette 实例
     */
    fun withOverrides(overrides: Map<String, String>): ThemePalette {
        val json = toJson()
        for ((key, value) in overrides) {
            if (key in COLOR_KEYS) {
                json.put(key, value)
            }
        }
        return fromJson(json)
    }
}

/**
 * 自定义主题条目 —— 存储在自定义主题列表中的一个条目。
 *
 * 每个条目包含唯一 ID、名称、完整色板、创建时使用的种子色（方便后续微调）、
 * 以及创建时间戳。
 *
 * 序列化为 JSON 存储在 settings key `custom_themes_list` 中。
 *
 * @property id 唯一标识（UUID 字符串）
 * @property name 主题名称（如"樱花粉"、"深海蓝"）
 * @property palette 完整的 21 槽位色板
 * @property seedColors 创建时使用的种子色映射，null 表示旧数据迁移（无种子信息）
 * @property createdAt 创建时间戳（毫秒）
 */
data class CustomThemeEntry(
    val id: String,
    val name: String,
    val palette: ThemePalette,
    val seedColors: Map<String, String>?,
    val createdAt: Long,
) {
    companion object {

        /**
         * 从 JSON 反序列化为 CustomThemeEntry。
         *
         * @param json 包含 id / name / palette / seedColors / createdAt 的 JSONObject
         * @return 反序列化后的 CustomThemeEntry 实例
         */
        fun fromJson(json: JSONObject): CustomThemeEntry {
            // 解析种子色（可选字段，旧数据迁移时可能不存在）
            val seedColorsMap = if (json.has("seedColors")) {
                val sc = json.getJSONObject("seedColors")
                val map = mutableMapOf<String, String>()
                val keys = sc.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = sc.getString(key)
                }
                map.ifEmpty { null }
            } else null

            return CustomThemeEntry(
                id = json.getString("id"),
                name = json.getString("name"),
                palette = ThemePalette.fromJson(json.getJSONObject("palette")),
                seedColors = seedColorsMap,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }

        /**
         * 从 JSON 字符串反序列化为自定义主题列表。
         *
         * @param jsonStr JSON Array 字符串
         * @return 自定义主题条目列表
         */
        fun listFromJson(jsonStr: String): List<CustomThemeEntry> {
            if (jsonStr.isBlank()) return emptyList()
            val arr = JSONArray(jsonStr)
            return (0 until arr.length()).map { i ->
                fromJson(arr.getJSONObject(i))
            }
        }

        /**
         * 将自定义主题列表序列化为 JSON 字符串。
         *
         * @param themes 自定义主题条目列表
         * @return JSON Array 字符串
         */
        fun listToJson(themes: List<CustomThemeEntry>): String {
            val arr = JSONArray()
            themes.forEach { entry -> arr.put(entry.toJson()) }
            return arr.toString()
        }
    }

    /**
     * 序列化为 JSON。
     *
     * @return 包含所有字段的 JSONObject
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("palette", palette.toJson())
            put("createdAt", createdAt)
            // 种子色可选保存（方便 AI 后续微调时知道原来的种子）
            seedColors?.let { sc ->
                val scJson = JSONObject()
                sc.forEach { (k, v) -> scJson.put(k, v) }
                put("seedColors", scJson)
            }
        }
    }
}
