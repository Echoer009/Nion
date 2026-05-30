package com.echonion.nion.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * 主题色派生工具 —— 从少量种子色自动生成完整的 21 槽位色板。
 *
 * 设计原则：
 * - AI 只需传递 7 个种子色（primary / secondary / tertiary / background /
 *   priorityHigh / priorityMedium / priorityLow），系统自动派生全部 21 个槽位
 * - 派生算法基于 HSL 色彩空间，参照现有 CORAL/AMBER 主题的量化规律
 * - 种子色可选，传了哪个就派生哪个色系，未传的保持当前值
 * - 精确覆盖（color_overrides）在种子派生之后应用，可微调任意单个槽位
 *
 * 派生规律（量化自 CORAL 主题）：
 * - Container 变体：L += 26~40%（目标 L 83~92%），S 提升
 * - onContainer 变体：L -= 20~35%（目标 L 16~39%），深色可读文字
 * - Surface 梯度：每级 L 下降 ~6%，S 下降 ~4%
 */
object ThemeDerivation {

    /**
     * 支持的种子色名称列表。
     * AI 传 seed_colors 时，键名必须在此列表中。
     */
    val SEED_KEYS: List<String> = listOf(
        "primary",
        "secondary",
        "tertiary",
        "background",
        "priorityHigh",
        "priorityMedium",
        "priorityLow",
    )

    /**
     * 种子色的中文描述，供 AI 理解用途。
     */
    val SEED_DESCRIPTIONS: Map<String, String> = mapOf(
        "primary" to "主色种子 — 影响按钮/FAB/选中图标/强调元素，自动派生容器色和文字色",
        "secondary" to "辅色种子 — 影响次要装饰元素，自动派生容器色和文字色",
        "tertiary" to "第三色种子 — 影响点缀/对比元素，自动派生容器色和文字色",
        "background" to "背景种子 — 影响页面底色/卡片背景/表面层级/边框线",
        "priorityHigh" to "紧急优先级颜色（默认红色）",
        "priorityMedium" to "中等优先级颜色（默认深橙色）",
        "priorityLow" to "低优先级颜色（默认蓝灰色）",
    )

    /**
     * HSL 色彩数据类，用于派生计算。
     */
    data class HSL(
        val h: Float,  // 色相 [0, 360)
        val s: Float,  // 饱和度 [0, 100]
        val l: Float,  // 明度 [0, 100]
    )

    /**
     * 从 Compose Color 转换为 HSL。
     */
    fun colorToHSL(color: Color): HSL {
        // 提取 RGBA 分量（0.0~1.0）
        val r = color.red
        val g = color.green
        val b = color.blue

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        // 明度
        val l = (max + min) / 2f

        // 饱和度
        val s = if (delta < 0.0001f) {
            0f
        } else {
            if (l < 0.5f) delta / (max + min) else delta / (2f - max - min)
        }

        // 色相
        val h = when {
            delta < 0.0001f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0) it + 360f else it }

        return HSL(h = h, s = s * 100f, l = l * 100f)
    }

    /**
     * 从 HSL 转换回 Compose Color。
     */
    fun hslToColor(hsl: HSL): Color {
        val h = hsl.h / 360f  // 归一化到 [0, 1]
        val s = (hsl.s / 100f).coerceIn(0f, 1f)
        val l = (hsl.l / 100f).coerceIn(0f, 1f)

        if (s < 0.0001f) {
            // 灰色
            return Color(l, l, l)
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        fun hueToRGB(p: Float, q: Float, t: Float): Float {
            var tn = t
            if (tn < 0f) tn += 1f
            if (tn > 1f) tn -= 1f
            return when {
                tn < 1f / 6f -> p + (q - p) * 6f * tn
                tn < 1f / 2f -> q
                tn < 2f / 3f -> p + (q - p) * (2f / 3f - tn) * 6f
                else -> p
            }
        }

        val r = hueToRGB(p, q, h + 1f / 3f)
        val g = hueToRGB(p, q, h)
        val b = hueToRGB(p, q, h - 1f / 3f)

        return Color(r, g, b)
    }

    /**
     * 从种子色派生 Primary 色系的全部变体。
     *
     * 派生规律（量化自 CORAL 主题）：
     * - primaryContainer: L+26%, S+14% → 浅色填充（目标 L~86%）
     * - onPrimaryContainer: L-20%, S-19% → 容器上深色文字（目标 L~39%）
     *
     * @param primaryHex 种子色 "#RRGGBB"
     * @return 派生出的 2 个槽位键值对
     */
    fun derivePrimaryFamily(primaryHex: String): Map<String, String> {
        val hsl = colorToHSL(parseHexColor(primaryHex))
        return mapOf(
            "primaryContainer" to hslToHex(HSL(
                h = (hsl.h + 3f).mod(360f),
                s = (hsl.s + 14f).coerceIn(0f, 100f),
                l = (hsl.l + 26f).coerceIn(0f, 100f),
            )),
            "onPrimaryContainer" to hslToHex(HSL(
                h = (hsl.h + 5f).mod(360f),
                s = (hsl.s - 19f).coerceIn(0f, 100f),
                l = (hsl.l - 20f).coerceIn(0f, 100f),
            )),
        )
    }

    /**
     * 从种子色派生 Secondary 色系的全部变体。
     *
     * 派生规律（量化自 CORAL 主题）：
     * - secondaryContainer: L+40%, S+34% → 非常浅的填充（目标 L~92%）
     * - onSecondaryContainer: L-35%, S+25% → 深色文字（目标 L~16%）
     *
     * @param secondaryHex 种子色 "#RRGGBB"
     * @return 派生出的 2 个槽位键值对
     */
    fun deriveSecondaryFamily(secondaryHex: String): Map<String, String> {
        val hsl = colorToHSL(parseHexColor(secondaryHex))
        return mapOf(
            "secondaryContainer" to hslToHex(HSL(
                h = (hsl.h + 1f).mod(360f),
                s = (hsl.s + 34f).coerceIn(0f, 100f),
                l = (hsl.l + 40f).coerceIn(0f, 100f),
            )),
            "onSecondaryContainer" to hslToHex(HSL(
                h = (hsl.h + 3f).mod(360f),
                s = (hsl.s + 25f).coerceIn(0f, 100f),
                l = (hsl.l - 35f).coerceIn(0f, 100f),
            )),
        )
    }

    /**
     * 从种子色派生 Tertiary 色系的全部变体。
     *
     * @param tertiaryHex 种子色 "#RRGGBB"
     * @return 派生出的 2 个槽位键值对
     */
    fun deriveTertiaryFamily(tertiaryHex: String): Map<String, String> {
        val hsl = colorToHSL(parseHexColor(tertiaryHex))
        return mapOf(
            "tertiaryContainer" to hslToHex(HSL(
                h = (hsl.h - 2f).mod(360f),
                s = (hsl.s + 13f).coerceIn(0f, 100f),
                l = (hsl.l + 51f).coerceIn(0f, 100f),
            )),
            "onTertiaryContainer" to hslToHex(HSL(
                h = (hsl.h - 3f).mod(360f),
                s = (hsl.s + 24f).coerceIn(0f, 100f),
                l = (hsl.l - 18f).coerceIn(0f, 100f),
            )),
        )
    }

    /**
     * 从种子色派生表面/背景层级的全部变体。
     *
     * 派生规律（量化自 CORAL 主题 Warm50~Warm300 梯度）：
     * - 每级 L 下降 ~6%，S 下降 ~4%
     * - 色相保持在种子色附近（±2）
     * - onBackground: L~12%（深色正文）
     * - onSurfaceVariant: L~35%（次要文字）
     * - outline: L~47%（中等边框）
     * - outlineVariant: L~75%（次要边框）
     * - cardBackground: L+2%（比背景略浅）
     *
     * @param backgroundHex 种子色 "#RRGGBB"（通常是浅米色等暖色中性色）
     * @return 派生出的 8 个槽位键值对
     */
    fun deriveBackgroundFamily(backgroundHex: String): Map<String, String> {
        val hsl = colorToHSL(parseHexColor(backgroundHex))
        return mapOf(
            "cardBackground" to hslToHex(HSL(
                h = hsl.h,
                s = (hsl.s + 5f).coerceIn(0f, 100f),
                l = (hsl.l + 2f).coerceIn(0f, 100f),
            )),
            "surfaceVariant" to hslToHex(HSL(
                h = (hsl.h + 1f).mod(360f),
                s = (hsl.s - 4f).coerceIn(0f, 100f),
                l = (hsl.l - 6f).coerceIn(0f, 100f),
            )),
            "surfaceHigh" to hslToHex(HSL(
                h = (hsl.h + 0.3f).mod(360f),
                s = (hsl.s - 8f).coerceIn(0f, 100f),
                l = (hsl.l - 12f).coerceIn(0f, 100f),
            )),
            "surfaceHighest" to hslToHex(HSL(
                h = (hsl.h + 0.8f).mod(360f),
                s = (hsl.s - 11f).coerceIn(0f, 100f),
                l = (hsl.l - 18f).coerceIn(0f, 100f),
            )),
            "onBackground" to hslToHex(HSL(
                h = hsl.h,
                s = (hsl.s * 0.15f).coerceIn(0f, 100f),
                l = 12f,
            )),
            "onSurfaceVariant" to hslToHex(HSL(
                h = hsl.h,
                s = (hsl.s * 0.2f).coerceIn(0f, 100f),
                l = 35f,
            )),
            "outline" to hslToHex(HSL(
                h = hsl.h,
                s = (hsl.s * 0.18f).coerceIn(0f, 100f),
                l = 47f,
            )),
            "outlineVariant" to hslToHex(HSL(
                h = hsl.h,
                s = (hsl.s * 0.22f).coerceIn(0f, 100f),
                l = 75f,
            )),
        )
    }

    /**
     * 从种子色映射生成全部 21 槽位的完整色板。
     *
     * 处理流程：
     * 1. 以 basePalette 作为基础（通常是当前色板或预设色板）
     * 2. 对每个传入的种子色执行对应色系的派生
     * 3. 种子色本身也作为对应槽位的值
     * 4. 最后应用 colorOverrides 进行精确覆盖
     *
     * @param seedColors 种子色键值对，键为 SEED_KEYS 中的名称，值为 "#RRGGBB"
     * @param colorOverrides 精确覆盖键值对，键为 COLOR_KEYS 中的槽位名，值为 "#RRGGBB"
     * @param basePalette 基础色板，未涉及的槽位从此继承
     * @return 派生后的完整 ThemePalette
     */
    fun deriveFromSeeds(
        seedColors: Map<String, String>,
        colorOverrides: Map<String, String> = emptyMap(),
        basePalette: ThemePalette,
    ): ThemePalette {
        // 从基础色板序列化为 JSON，逐步覆盖
        val paletteJson = basePalette.toJson()

        // ── Primary 色系 ──
        seedColors["primary"]?.let { hex ->
            paletteJson.put("primary", hex)
            derivePrimaryFamily(hex).forEach { (k, v) -> paletteJson.put(k, v) }
        }

        // ── Secondary 色系 ──
        seedColors["secondary"]?.let { hex ->
            paletteJson.put("secondary", hex)
            deriveSecondaryFamily(hex).forEach { (k, v) -> paletteJson.put(k, v) }
        }

        // ── Tertiary 色系 ──
        seedColors["tertiary"]?.let { hex ->
            paletteJson.put("tertiary", hex)
            deriveTertiaryFamily(hex).forEach { (k, v) -> paletteJson.put(k, v) }
        }

        // ── 表面/背景 ──
        seedColors["background"]?.let { hex ->
            paletteJson.put("background", hex)
            deriveBackgroundFamily(hex).forEach { (k, v) -> paletteJson.put(k, v) }
        }

        // ── 优先级色（直接使用，不派生） ──
        seedColors["priorityHigh"]?.let { paletteJson.put("priorityHigh", it) }
        seedColors["priorityMedium"]?.let { paletteJson.put("priorityMedium", it) }
        seedColors["priorityLow"]?.let { paletteJson.put("priorityLow", it) }

        // ── 精确覆盖（在种子派生之上 patch） ──
        colorOverrides.forEach { (key, value) ->
            if (key in ThemePalette.COLOR_KEYS) {
                paletteJson.put(key, value)
            }
        }

        return ThemePalette.fromJson(paletteJson)
    }

    /**
     * 将 HSL 转换为 "#RRGGBB" 字符串。
     */
    fun hslToHex(hsl: HSL): String {
        val color = hslToColor(hsl)
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    /**
     * 解析 "#RRGGBB" 字符串为 Compose Color。
     */
    private fun parseHexColor(hex: String): Color {
        val argb = android.graphics.Color.parseColor(hex)
        return Color(argb)
    }
}
