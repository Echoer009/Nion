package com.echonion.nion.ui.theme

import androidx.compose.ui.graphics.Color

object NionColors {
    val Orange900 = Color(0xFFBF360C)
    val Orange800 = Color(0xFFD84315)
    val Orange700 = Color(0xFFE64A19)
    val Orange600 = Color(0xFFF4511E)
    val Orange500 = Color(0xFFFF5722)
    val Orange400 = Color(0xFFFF7043)
    val Orange300 = Color(0xFFFF8A65)
    val Orange200 = Color(0xFFFFAB91)
    val Orange100 = Color(0xFFFFCCBC)
    val Orange50 = Color(0xFFFBE9E7)

    val Warm50 = Color(0xFFF5F0E8)
    val Warm100 = Color(0xFFEBE3D5)
    val Warm200 = Color(0xFFE0D5C3)
    val Warm300 = Color(0xFFD3C6B0)
    val Warm400 = Color(0xFFC7B9A5)

    val DarkSurface = Color(0xFF1C1917)
    val DarkSurfaceHigh = Color(0xFF272420)
    val DarkSurfaceHighest = Color(0xFF322E2A)
    val DarkBackground = Color(0xFF141210)
}

enum class NionColorTheme(
    val label: String,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
) {
    BURNT_ORANGE("焦橙", Color(0xFFD84315), Color(0xFFFFCCBC), Color(0xFFBF360C), Color(0xFFFF8A65), Color(0xFF6E2000)),
    TERRACOTTA("赤陶", Color(0xFFC06830), Color(0xFFF5D5BD), Color(0xFF8B4513), Color(0xFFE8A078), Color(0xFF5A2D15)),
    AMBER("琥珀", Color(0xFFCC7A3E), Color(0xFFF5DEB3), Color(0xFF8B5A2B), Color(0xFFE8B080), Color(0xFF5A3520)),
    CORAL("珊瑚", Color(0xFFD4845A), Color(0xFFF5D5C0), Color(0xFF8B5E3C), Color(0xFFF0B090), Color(0xFF5A3520)),
    OCHRE("赭石", Color(0xFFB8703F), Color(0xFFE8D0B8), Color(0xFF7A4A28), Color(0xFFD8A070), Color(0xFF4A2D18));
}
