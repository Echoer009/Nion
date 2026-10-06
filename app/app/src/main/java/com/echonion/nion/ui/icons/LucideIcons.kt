package com.echonion.nion.ui.icons

/**
 * Lucide 线条图标（项目内子集）
 *
 * 来源：Lucide（ISC 许可）24×24 线条图标集，从 com.composables:icons-lucide-android
 * 的矢量 XML 抽取并转换为 Compose ImageVector，仅打包用到的图标，零运行时依赖。
 * 命名与 Lucide 官方一致（PascalCase，如 message-circle → MessageCircle）。
 * 图标为纯描边路径（stroke 2f、圆头圆角），着色靠 Icon() 的 tint 整体染色。
 */
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object LucideIcons {
/** 通用构建器：24×24 视口 + 全部描边路径（Lucide 统一 2f 圆头圆角描边） */
private fun buildLucide(name: String, paths: List<String>, strokeWidth: Float = 2f): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { pd ->
            addPath(
                pathData = PathParser().parsePathString(pd).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()


/** lucide:search */
val Search: ImageVector by lazy { buildLucide("Search", listOf("m21,21l-4.34,-4.34", "M19,11A8,8 0 0,1 11,19A8,8 0 0,1 3,11A8,8 0 0,1 19,11Z")) }


/** lucide:x */
val X: ImageVector by lazy { buildLucide("X", listOf("M18,6L6,18", "m6,6l12,12")) }


/** lucide:arrow-down-a-z */
val ArrowDownAZ: ImageVector by lazy { buildLucide("ArrowDownAZ", listOf("m3,16l4,4l4,-4", "M7,20V4", "M20,8h-5", "M15,10V6.5a2.5,2.5 0 0,1 5,0V10", "M15,14h5l-5,6h5")) }


/** lucide:calendar-clock */
val CalendarClock: ImageVector by lazy { buildLucide("CalendarClock", listOf("M16,14v2.2l1.6,1", "M16,2v4", "M21,7.5V6a2,2 0 0,0 -2,-2H5a2,2 0 0,0 -2,2v14a2,2 0 0,0 2,2h3.5", "M3,10h5", "M8,2v4", "M22,16A6,6 0 0,1 16,22A6,6 0 0,1 10,16A6,6 0 0,1 22,16Z")) }


/** lucide:disc-3 */
val Disc3: ImageVector by lazy { buildLucide("Disc3", listOf("M22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 22,12Z", "M6,12c0,-1.7 0.7,-3.2 1.8,-4.2", "M14,12A2,2 0 0,1 12,14A2,2 0 0,1 10,12A2,2 0 0,1 14,12Z", "M18,12c0,1.7 -0.7,3.2 -1.8,4.2")) }


/** lucide:list-music */
val ListMusic: ImageVector by lazy { buildLucide("ListMusic", listOf("M16,5H3", "M11,12H3", "M11,19H3", "M21,16V5", "M21,16A3,3 0 0,1 18,19A3,3 0 0,1 15,16A3,3 0 0,1 21,16Z")) }


/** lucide:plus */
val Plus: ImageVector by lazy { buildLucide("Plus", listOf("M5,12h14", "M12,5v14")) }


/** lucide:trash-2 */
val Trash2: ImageVector by lazy { buildLucide("Trash2", listOf("M10,11v6", "M14,11v6", "M19,6v14a2,2 0 0,1 -2,2H7a2,2 0 0,1 -2,-2V6", "M3,6h18", "M8,6V4a2,2 0 0,1 2,-2h4a2,2 0 0,1 2,2v2")) }


/** lucide:play */
val Play: ImageVector by lazy { buildLucide("Play", listOf("M5,5a2,2 0 0,1 3.008,-1.728l11.997,6.998a2,2 0 0,1 0.003,3.458l-12,7A2,2 0 0,1 5,19Z")) }


/** lucide:pause */
val Pause: ImageVector by lazy { buildLucide("Pause", listOf("M15,3H18A1,1 0 0,1 19,4V20A1,1 0 0,1 18,21H15A1,1 0 0,1 14,20V4A1,1 0 0,1 15,3Z", "M6,3H9A1,1 0 0,1 10,4V20A1,1 0 0,1 9,21H6A1,1 0 0,1 5,20V4A1,1 0 0,1 6,3Z")) }


/** lucide:skip-forward */
val SkipForward: ImageVector by lazy { buildLucide("SkipForward", listOf("M21,4v16", "M6.029,4.285A2,2 0 0,0 3,6v12a2,2 0 0,0 3.029,1.715l9.997,-5.998a2,2 0 0,0 0.003,-3.432Z")) }


/** lucide:skip-back */
val SkipBack: ImageVector by lazy { buildLucide("SkipBack", listOf("M17.971,4.285A2,2 0 0,1 21,6v12a2,2 0 0,1 -3.029,1.715l-9.997,-5.998a2,2 0 0,1 -0.003,-3.432Z", "M3,20V4")) }


/** lucide:refresh-cw */
val RefreshCw: ImageVector by lazy { buildLucide("RefreshCw", listOf("M3,12a9,9 0 0,1 9,-9a9.75,9.75 0 0,1 6.74,2.74L21,8", "M21,3v5h-5", "M21,12a9,9 0 0,1 -9,9a9.75,9.75 0 0,1 -6.74,-2.74L3,16", "M8,16H3v5")) }


/** lucide:headphones */
val Headphones: ImageVector by lazy { buildLucide("Headphones", listOf("M3,14h3a2,2 0 0,1 2,2v3a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2v-7a9,9 0 0,1 18,0v7a2,2 0 0,1 -2,2h-1a2,2 0 0,1 -2,-2v-3a2,2 0 0,1 2,-2h3")) }


/** lucide:music */
val Music: ImageVector by lazy { buildLucide("Music", listOf("M9,18V5l12,-2v13", "M9,18A3,3 0 0,1 6,21A3,3 0 0,1 3,18A3,3 0 0,1 9,18Z", "M21,16A3,3 0 0,1 18,19A3,3 0 0,1 15,16A3,3 0 0,1 21,16Z")) }


/** lucide:chevron-down */
val ChevronDown: ImageVector by lazy { buildLucide("ChevronDown", listOf("m6,9l6,6l6,-6")) }


/** lucide:server */
val Server: ImageVector by lazy { buildLucide("Server", listOf("M4,2H20A2,2 0 0,1 22,4V8A2,2 0 0,1 20,10H4A2,2 0 0,1 2,8V4A2,2 0 0,1 4,2Z", "M4,14H20A2,2 0 0,1 22,16V20A2,2 0 0,1 20,22H4A2,2 0 0,1 2,20V16A2,2 0 0,1 4,14Z", "M6,6L6.01,6", "M6,18L6.01,18")) }


/** lucide:cloud */
val Cloud: ImageVector by lazy { buildLucide("Cloud", listOf("M17.5,19H9a7,7 0 1,1 6.71,-9h1.79a4.5,4.5 0 1,1 0,9Z")) }


/** lucide:bot */
val Bot: ImageVector by lazy { buildLucide("Bot", listOf("M12,8V4H8", "M6,8H18A2,2 0 0,1 20,10V18A2,2 0 0,1 18,20H6A2,2 0 0,1 4,18V10A2,2 0 0,1 6,8Z", "M2,14h2", "M20,14h2", "M15,13v2", "M9,13v2")) }


/** lucide:brain */
val Brain: ImageVector by lazy { buildLucide("Brain", listOf("M12,18V5", "M15,13a4.17,4.17 0 0,1 -3,-4a4.17,4.17 0 0,1 -3,4", "M17.598,6.5A3,3 0 1,0 12,5a3,3 0 1,0 -5.598,1.5", "M17.997,5.125a4,4 0 0,1 2.526,5.77", "M18,18a4,4 0 0,0 2,-7.464", "M19.967,17.483A4,4 0 1,1 12,18a4,4 0 1,1 -7.967,-0.517", "M6,18a4,4 0 0,1 -2,-7.464", "M6.003,5.125a4,4 0 0,0 -2.526,5.77")) }


/** lucide:message-circle */
val MessageCircle: ImageVector by lazy { buildLucide("MessageCircle", listOf("M2.992,16.342a2,2 0 0,1 0.094,1.167l-1.065,3.29a1,1 0 0,0 1.236,1.168l3.413,-0.998a2,2 0 0,1 1.099,0.092a10,10 0 1,0 -4.777,-4.719")) }


/** lucide:sparkles */
val Sparkles: ImageVector by lazy { buildLucide("Sparkles", listOf("M11.017,2.814a1,1 0 0,1 1.966,0l1.051,5.558a2,2 0 0,0 1.594,1.594l5.558,1.051a1,1 0 0,1 0,1.966l-5.558,1.051a2,2 0 0,0 -1.594,1.594l-1.051,5.558a1,1 0 0,1 -1.966,0l-1.051,-5.558a2,2 0 0,0 -1.594,-1.594l-5.558,-1.051a1,1 0 0,1 0,-1.966l5.558,-1.051a2,2 0 0,0 1.594,-1.594Z", "M20,2v4", "M22,4h-4", "M6,20A2,2 0 0,1 4,22A2,2 0 0,1 2,20A2,2 0 0,1 6,20Z")) }


/** lucide:paw-print */
val PawPrint: ImageVector by lazy { buildLucide("PawPrint", listOf("M13,4A2,2 0 0,1 11,6A2,2 0 0,1 9,4A2,2 0 0,1 13,4Z", "M20,8A2,2 0 0,1 18,10A2,2 0 0,1 16,8A2,2 0 0,1 20,8Z", "M22,16A2,2 0 0,1 20,18A2,2 0 0,1 18,16A2,2 0 0,1 22,16Z", "M9,10a5,5 0 0,1 5,5v3.5a3.5,3.5 0 0,1 -6.84,1.045Q6.52,17.48 4.46,16.84A3.5,3.5 0 0,1 5.5,10Z")) }

/** lucide:audio-lines（音波线条，音乐空态/引导用） */
val AudioLines: ImageVector by lazy { buildLucide("AudioLines", listOf("M2,10v3", "M6,6v11", "M10,3v18", "M14,8v7", "M18,5v13", "M22,10v3")) }

/** lucide:circle-plus（圆形加号，添加类入口） */
val CirclePlus: ImageVector by lazy { buildLucide("CirclePlus", listOf("M22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 22,12Z", "M8,12h8", "M12,8v8")) }
}
