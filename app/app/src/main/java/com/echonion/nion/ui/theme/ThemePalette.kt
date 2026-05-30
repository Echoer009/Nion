package com.echonion.nion.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * 完整主题色板数据类 —— 承载一个主题的全部颜色配置（约 30 个槽位）。
 *
 * 用途：
 * - 预设主题通过 [NionColorTheme.palette()] 生成实例
 * - 自定义主题通过 JSON 反序列化生成实例
 * - [com.echonion.nion.ui.theme.NionTheme] 接收此数据类构建 Material 3 ColorScheme
 *
 * 序列化格式：所有颜色以 "#RRGGBB" 字符串存储（不含 alpha），
 * 遵循 CSS hex color 规范，方便 AI 伴伴工具直接读写。
 *
 * @property primary 亮色模式主色（影响按钮、FAB、高亮元素）
 * @property primaryContainer 亮色模式主色容器（影响 chip 背景、浅色填充）
 * @property onPrimaryContainer 亮色模式主色容器上的文字/图标
 * @property darkPrimary 暗色模式主色
 * @property darkOnPrimary 暗色模式主色上的文字/图标
 * @property secondary 亮色模式辅色
 * @property secondaryContainer 亮色模式辅色容器
 * @property onSecondaryContainer 亮色模式辅色容器上的文字
 * @property darkSecondary 暗色模式辅色
 * @property darkOnSecondary 暗色模式辅色上的文字
 * @property darkSecondaryContainer 暗色模式辅色容器
 * @property tertiary 亮色模式第三色
 * @property tertiaryContainer 亮色模式第三色容器
 * @property onTertiaryContainer 亮色模式第三色容器上的文字
 * @property darkTertiary 暗色模式第三色
 * @property darkOnTertiary 暗色模式第三色上的文字
 * @property darkTertiaryContainer 暗色模式第三色容器
 * @property lightBackground 亮色模式背景色
 * @property lightOnBackground 亮色模式背景上的文字
 * @property lightSurfaceVariant 亮色模式表面变体（侧边栏、卡片等）
 * @property lightOnSurfaceVariant 亮色模式表面变体上的文字
 * @property lightSurfaceHigh 亮色模式高强调表面
 * @property lightSurfaceHighest 亮色模式最高强调表面
 * @property lightOutline 亮色模式边框线
 * @property lightOutlineVariant 亮色模式次要边框线
 * @property darkBackground 暗色模式背景色
 * @property darkSurface 暗色模式表面色
 * @property darkSurfaceHigh 暗色模式高强调表面
 * @property darkSurfaceHighest 暗色模式最高强调表面
 * @property darkOnBackground 暗色模式背景上的文字
 * @property darkOnSurfaceVariant 暗色模式表面变体上的文字
 */
data class ThemePalette(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val darkSecondary: Color,
    val darkOnSecondary: Color,
    val darkSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val darkTertiary: Color,
    val darkOnTertiary: Color,
    val darkTertiaryContainer: Color,
    val lightBackground: Color,
    val lightOnBackground: Color,
    val lightSurfaceVariant: Color,
    val lightOnSurfaceVariant: Color,
    val lightSurfaceHigh: Color,
    val lightSurfaceHighest: Color,
    val lightOutline: Color,
    val lightOutlineVariant: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceHigh: Color,
    val darkSurfaceHighest: Color,
    val darkOnBackground: Color,
    val darkOnSurfaceVariant: Color,
) {
    companion object {

        /**
         * 所有颜色槽位的键名列表，按构造参数顺序排列。
         * 用于 JSON 序列化/反序列化以及 SettingsTool 的 schema 定义。
         */
        val COLOR_KEYS: List<String> = listOf(
            "primary", "primaryContainer", "onPrimaryContainer",
            "darkPrimary", "darkOnPrimary",
            "secondary", "secondaryContainer", "onSecondaryContainer",
            "darkSecondary", "darkOnSecondary", "darkSecondaryContainer",
            "tertiary", "tertiaryContainer", "onTertiaryContainer",
            "darkTertiary", "darkOnTertiary", "darkTertiaryContainer",
            "lightBackground", "lightOnBackground",
            "lightSurfaceVariant", "lightOnSurfaceVariant",
            "lightSurfaceHigh", "lightSurfaceHighest",
            "lightOutline", "lightOutlineVariant",
            "darkBackground", "darkSurface", "darkSurfaceHigh", "darkSurfaceHighest",
            "darkOnBackground", "darkOnSurfaceVariant",
        )

        /**
         * 每个颜色槽位的中文描述，供 AI 伴伴理解用途。
         * 顺序与 [COLOR_KEYS] 一一对应。
         */
        val COLOR_DESCRIPTIONS: Map<String, String> = mapOf(
            "primary" to "亮色模式主色调，影响按钮/FAB/高亮元素/选中态",
            "primaryContainer" to "亮色模式主色容器，影响 chip 背景/浅色填充/标签底色",
            "onPrimaryContainer" to "亮色模式主色容器上的文字和图标颜色",
            "darkPrimary" to "暗色模式主色调",
            "darkOnPrimary" to "暗色模式主色上的文字和图标颜色",
            "secondary" to "亮色模式辅色，用于次要操作和装饰元素",
            "secondaryContainer" to "亮色模式辅色容器",
            "onSecondaryContainer" to "亮色模式辅色容器上的文字",
            "darkSecondary" to "暗色模式辅色",
            "darkOnSecondary" to "暗色模式辅色上的文字",
            "darkSecondaryContainer" to "暗色模式辅色容器",
            "tertiary" to "亮色模式第三色，用于对比/点缀",
            "tertiaryContainer" to "亮色模式第三色容器",
            "onTertiaryContainer" to "亮色模式第三色容器上的文字",
            "darkTertiary" to "暗色模式第三色",
            "darkOnTertiary" to "暗色模式第三色上的文字",
            "darkTertiaryContainer" to "暗色模式第三色容器",
            "lightBackground" to "亮色模式全局背景色（页面底色）",
            "lightOnBackground" to "亮色模式背景上的正文颜色",
            "lightSurfaceVariant" to "亮色模式表面变体色（侧边栏/卡片/输入框背景）",
            "lightOnSurfaceVariant" to "亮色模式表面变体上的次要文字颜色",
            "lightSurfaceHigh" to "亮色模式高强调表面色（分割线区域/Tab背景）",
            "lightSurfaceHighest" to "亮色模式最高强调表面色（弹窗/下拉菜单背景）",
            "lightOutline" to "亮色模式边框线颜色",
            "lightOutlineVariant" to "亮色模式次要边框线颜色（分割线/装饰线）",
            "darkBackground" to "暗色模式全局背景色",
            "darkSurface" to "暗色模式表面色（卡片/组件背景）",
            "darkSurfaceHigh" to "暗色模式高强调表面色",
            "darkSurfaceHighest" to "暗色模式最高强调表面色",
            "darkOnBackground" to "暗色模式背景上的正文颜色",
            "darkOnSurfaceVariant" to "暗色模式表面变体上的次要文字颜色",
        )

        /**
         * 从 JSON 反序列化为 ThemePalette。
         *
         * JSON 格式示例：
         * ```json
         * {
         *   "primary": "#D4845A",
         *   "primaryContainer": "#F5D5C0",
         *   ...
         * }
         * ```
         *
         * @param json 包含所有颜色槽位的 JSONObject
         * @return 反序列化后的 ThemePalette 实例
         */
        fun fromJson(json: JSONObject): ThemePalette {
            return ThemePalette(
                primary = parseColor(json, "primary"),
                primaryContainer = parseColor(json, "primaryContainer"),
                onPrimaryContainer = parseColor(json, "onPrimaryContainer"),
                darkPrimary = parseColor(json, "darkPrimary"),
                darkOnPrimary = parseColor(json, "darkOnPrimary"),
                secondary = parseColor(json, "secondary"),
                secondaryContainer = parseColor(json, "secondaryContainer"),
                onSecondaryContainer = parseColor(json, "onSecondaryContainer"),
                darkSecondary = parseColor(json, "darkSecondary"),
                darkOnSecondary = parseColor(json, "darkOnSecondary"),
                darkSecondaryContainer = parseColor(json, "darkSecondaryContainer"),
                tertiary = parseColor(json, "tertiary"),
                tertiaryContainer = parseColor(json, "tertiaryContainer"),
                onTertiaryContainer = parseColor(json, "onTertiaryContainer"),
                darkTertiary = parseColor(json, "darkTertiary"),
                darkOnTertiary = parseColor(json, "darkOnTertiary"),
                darkTertiaryContainer = parseColor(json, "darkTertiaryContainer"),
                lightBackground = parseColor(json, "lightBackground"),
                lightOnBackground = parseColor(json, "lightOnBackground"),
                lightSurfaceVariant = parseColor(json, "lightSurfaceVariant"),
                lightOnSurfaceVariant = parseColor(json, "lightOnSurfaceVariant"),
                lightSurfaceHigh = parseColor(json, "lightSurfaceHigh"),
                lightSurfaceHighest = parseColor(json, "lightSurfaceHighest"),
                lightOutline = parseColor(json, "lightOutline"),
                lightOutlineVariant = parseColor(json, "lightOutlineVariant"),
                darkBackground = parseColor(json, "darkBackground"),
                darkSurface = parseColor(json, "darkSurface"),
                darkSurfaceHigh = parseColor(json, "darkSurfaceHigh"),
                darkSurfaceHighest = parseColor(json, "darkSurfaceHighest"),
                darkOnBackground = parseColor(json, "darkOnBackground"),
                darkOnSurfaceVariant = parseColor(json, "darkOnSurfaceVariant"),
            )
        }

        /**
         * 从 JSONObject 中解析指定键的颜色值。
         * 支持 "#RRGGBB" 和 "#AARRGGBB" 两种格式。
         */
        private fun parseColor(json: JSONObject, key: String): Color {
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
            put("darkPrimary", colorToHex(darkPrimary))
            put("darkOnPrimary", colorToHex(darkOnPrimary))
            put("secondary", colorToHex(secondary))
            put("secondaryContainer", colorToHex(secondaryContainer))
            put("onSecondaryContainer", colorToHex(onSecondaryContainer))
            put("darkSecondary", colorToHex(darkSecondary))
            put("darkOnSecondary", colorToHex(darkOnSecondary))
            put("darkSecondaryContainer", colorToHex(darkSecondaryContainer))
            put("tertiary", colorToHex(tertiary))
            put("tertiaryContainer", colorToHex(tertiaryContainer))
            put("onTertiaryContainer", colorToHex(onTertiaryContainer))
            put("darkTertiary", colorToHex(darkTertiary))
            put("darkOnTertiary", colorToHex(darkOnTertiary))
            put("darkTertiaryContainer", colorToHex(darkTertiaryContainer))
            put("lightBackground", colorToHex(lightBackground))
            put("lightOnBackground", colorToHex(lightOnBackground))
            put("lightSurfaceVariant", colorToHex(lightSurfaceVariant))
            put("lightOnSurfaceVariant", colorToHex(lightOnSurfaceVariant))
            put("lightSurfaceHigh", colorToHex(lightSurfaceHigh))
            put("lightSurfaceHighest", colorToHex(lightSurfaceHighest))
            put("lightOutline", colorToHex(lightOutline))
            put("lightOutlineVariant", colorToHex(lightOutlineVariant))
            put("darkBackground", colorToHex(darkBackground))
            put("darkSurface", colorToHex(darkSurface))
            put("darkSurfaceHigh", colorToHex(darkSurfaceHigh))
            put("darkSurfaceHighest", colorToHex(darkSurfaceHighest))
            put("darkOnBackground", colorToHex(darkOnBackground))
            put("darkOnSurfaceVariant", colorToHex(darkOnSurfaceVariant))
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
