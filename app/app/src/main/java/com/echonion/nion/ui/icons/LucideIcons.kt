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

/** lucide:check */
val Check: ImageVector by lazy { buildLucide("Check", listOf("M20,6L9,17l-5,-5")) }

/** lucide:repeat */
val Repeat: ImageVector by lazy { buildLucide("Repeat", listOf("m17,2l4,4l-4,4", "M3,11v-1a4,4 0 0,1 4,-4h14", "m7,22l-4,-4l4,-4", "M21,13v1a4,4 0 0,1 -4,4H3")) }

/** lucide:file-text */
val FileText: ImageVector by lazy { buildLucide("FileText", listOf("M6,22a2,2 0 0,1 -2,-2V4a2,2 0 0,1 2,-2h8a2.4,2.4 0 0,1 1.704,0.706l3.588,3.588A2.4,2.4 0 0,1 20,8v12a2,2 0 0,1 -2,2Z", "M14,2v5a1,1 0 0,0 1,1h5", "M10,9H8", "M16,13H8", "M16,17H8")) }

/** lucide:alarm-clock */
val AlarmClock: ImageVector by lazy { buildLucide("AlarmClock", listOf("M20,13A8,8 0 0,1 12,21A8,8 0 0,1 4,13A8,8 0 0,1 20,13Z", "M12,9v4l2,2", "M5,3L2,6", "m22,6l-3,-3", "M6.38,18.7L4,21", "M17.64,18.67L20,21")) }

/** lucide:timer */
val Timer: ImageVector by lazy { buildLucide("Timer", listOf("M10,2L14,2", "M12,14L15,11", "M20,14A8,8 0 0,1 12,22A8,8 0 0,1 4,14A8,8 0 0,1 20,14Z")) }

/** lucide:arrow-left */
val ArrowLeft: ImageVector by lazy { buildLucide("ArrowLeft", listOf("m12,19l-7,-7l7,-7", "M19,12H5")) }

/** lucide:chevron-right */
val ChevronRight: ImageVector by lazy { buildLucide("ChevronRight", listOf("m9,18l6,-6l-6,-6")) }

/** lucide:chevron-left */
val ChevronLeft: ImageVector by lazy { buildLucide("ChevronLeft", listOf("m15,18l-6,-6l6,-6")) }

/** lucide:eye */
val Eye: ImageVector by lazy { buildLucide("Eye", listOf("M2.062,12.348a1,1 0 0,1 0,-0.696a10.75,10.75 0 0,1 19.876,0a1,1 0 0,1 0,0.696a10.75,10.75 0 0,1 -19.876,0", "M15,12A3,3 0 0,1 12,15A3,3 0 0,1 9,12A3,3 0 0,1 15,12Z")) }

/** lucide:circle-check */
val CircleCheck: ImageVector by lazy { buildLucide("CircleCheck", listOf("M22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 22,12Z", "m9,12l2,2l4,-4")) }

/** lucide:smile */
val Smile: ImageVector by lazy { buildLucide("Smile", listOf("M22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 22,12Z", "M8,14s1.5,2 4,2s4,-2 4,-2", "M9,9L9.01,9", "M15,9L15.01,9")) }

/** lucide:bookmark */
val Bookmark: ImageVector by lazy { buildLucide("Bookmark", listOf("m19,21l-7,-4l-7,4V5a2,2 0 0,1 2,-2h10a2,2 0 0,1 2,2v16Z")) }

/** lucide:paperclip */
val Paperclip: ImageVector by lazy { buildLucide("Paperclip", listOf("m16,6l-8.414,8.586a2,2 0 0,0 2.829,2.829l8.414,-8.586a4,4 0 1,0 -5.657,-5.657l-8.379,8.551a6,6 0 1,0 8.485,8.485l8.379,-8.551")) }

/** lucide:sun */
val Sun: ImageVector by lazy { buildLucide("Sun", listOf("M16,12A4,4 0 0,1 12,16A4,4 0 0,1 8,12A4,4 0 0,1 16,12Z", "M12,2v2", "M12,20v2", "m4.93,4.93l1.41,1.41", "m17.66,17.66l1.41,1.41", "M2,12h2", "M20,12h2", "m6.34,17.66l-1.41,1.41", "m19.07,4.93l-1.41,1.41")) }

/** lucide:triangle-alert */
val TriangleAlert: ImageVector by lazy { buildLucide("TriangleAlert", listOf("m21.73,18l-8,-14a2,2 0 0,0 -3.48,0l-8,14A2,2 0 0,0 4,21h16a2,2 0 0,0 1.73,-3", "M12,9v4", "M12,17h0.01")) }

/** lucide:eye-off */
val EyeOff: ImageVector by lazy { buildLucide("EyeOff", listOf("M10.733,5.076a10.744,10.744 0 0,1 11.205,6.575a1,1 0 0,1 0,0.696a10.747,10.747 0 0,1 -1.444,2.49", "M14.084,14.158a3,3 0 0,1 -4.242,-4.242", "M17.479,17.499a10.75,10.75 0 0,1 -15.417,-5.151a1,1 0 0,1 0,-0.696a10.75,10.75 0 0,1 4.446,-5.143", "m2,2l20,20")) }

/** lucide:arrow-left-right */
val ArrowLeftRight: ImageVector by lazy { buildLucide("ArrowLeftRight", listOf("M8,3L4,7l4,4", "M4,7h16", "m16,21l4,-4l-4,-4", "M20,17H4")) }

/** lucide:square */
val Square: ImageVector by lazy { buildLucide("Square", listOf("M5,3H19A2,2 0 0,1 21,5V19A2,2 0 0,1 19,21H5A2,2 0 0,1 3,19V5A2,2 0 0,1 5,3Z")) }

/** lucide:map-pin */
val MapPin: ImageVector by lazy { buildLucide("MapPin", listOf("M20,10c0,4.993 -5.539,10.193 -7.399,11.799a1,1 0 0,1 -1.202,0C9.539,20.193 4,14.993 4,10a8,8 0 0,1 16,0", "M15,10A3,3 0 0,1 12,13A3,3 0 0,1 9,10A3,3 0 0,1 15,10Z")) }

/** lucide:chevron-up */
val ChevronUp: ImageVector by lazy { buildLucide("ChevronUp", listOf("m18,15l-6,-6l-6,6")) }

/** lucide:image */
val Image: ImageVector by lazy { buildLucide("Image", listOf("M5,3H19A2,2 0 0,1 21,5V19A2,2 0 0,1 19,21H5A2,2 0 0,1 3,19V5A2,2 0 0,1 5,3Z", "M11,9A2,2 0 0,1 9,11A2,2 0 0,1 7,9A2,2 0 0,1 11,9Z", "m21,15l-3.086,-3.086a2,2 0 0,0 -2.828,0L6,21")) }

/** lucide:history */
val History: ImageVector by lazy { buildLucide("History", listOf("M3,12a9,9 0 1,0 9,-9a9.75,9.75 0 0,0 -6.74,2.74L3,8", "M3,3v5h5", "M12,7v5l4,2")) }

/** lucide:pencil */
val Pencil: ImageVector by lazy { buildLucide("Pencil", listOf("M21.174,6.812a1,1 0 0,0 -3.986,-3.987L3.842,16.174a2,2 0 0,0 -0.5,0.83l-1.321,4.352a0.5,0.5 0 0,0 0.623,0.622l4.353,-1.32a2,2 0 0,0 0.83,-0.497Z", "m15,5l4,4")) }

/** lucide:bell */
val Bell: ImageVector by lazy { buildLucide("Bell", listOf("M10.268,21a2,2 0 0,0 3.464,0", "M3.262,15.326A1,1 0 0,0 4,17h16a1,1 0 0,0 0.74,-1.673C19.41,13.956 18,12.499 18,8A6,6 0 0,0 6,8c0,4.499 -1.411,5.956 -2.738,7.326")) }

/** lucide:calendar */
val Calendar: ImageVector by lazy { buildLucide("Calendar", listOf("M8,2v4", "M16,2v4", "M5,4H19A2,2 0 0,1 21,6V20A2,2 0 0,1 19,22H5A2,2 0 0,1 3,20V6A2,2 0 0,1 5,4Z", "M3,10h18")) }

/** lucide:square-check */
val SquareCheck: ImageVector by lazy { buildLucide("SquareCheck", listOf("M5,3H19A2,2 0 0,1 21,5V19A2,2 0 0,1 19,21H5A2,2 0 0,1 3,19V5A2,2 0 0,1 5,3Z", "m9,12l2,2l4,-4")) }

/** lucide:upload */
val Upload: ImageVector by lazy { buildLucide("Upload", listOf("M12,3v12", "m17,8l-5,-5l-5,5", "M21,15v4a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2v-4")) }

/** lucide:settings */
val Settings: ImageVector by lazy { buildLucide("Settings", listOf("M9.671,4.136a2.34,2.34 0 0,1 4.659,0a2.34,2.34 0 0,0 3.319,1.915a2.34,2.34 0 0,1 2.33,4.033a2.34,2.34 0 0,0 0,3.831a2.34,2.34 0 0,1 -2.33,4.033a2.34,2.34 0 0,0 -3.319,1.915a2.34,2.34 0 0,1 -4.659,0a2.34,2.34 0 0,0 -3.32,-1.915a2.34,2.34 0 0,1 -2.33,-4.033a2.34,2.34 0 0,0 0,-3.831A2.34,2.34 0 0,1 6.35,6.051a2.34,2.34 0 0,0 3.319,-1.915", "M15,12A3,3 0 0,1 12,15A3,3 0 0,1 9,12A3,3 0 0,1 15,12Z")) }

/** lucide:minus */
val Minus: ImageVector by lazy { buildLucide("Minus", listOf("M5,12h14")) }

/** lucide:circle */
val Circle: ImageVector by lazy { buildLucide("Circle", listOf("M22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 22,12Z")) }

/** lucide:locate-fixed */
val LocateFixed: ImageVector by lazy { buildLucide("LocateFixed", listOf("M2,12L5,12", "M19,12L22,12", "M12,2L12,5", "M12,19L12,22", "M19,12A7,7 0 0,1 12,19A7,7 0 0,1 5,12A7,7 0 0,1 19,12Z", "M15,12A3,3 0 0,1 12,15A3,3 0 0,1 9,12A3,3 0 0,1 15,12Z")) }

/** lucide:menu */
val Menu: ImageVector by lazy { buildLucide("Menu", listOf("M4,5h16", "M4,12h16", "M4,19h16")) }

/** lucide:link */
val Link: ImageVector by lazy { buildLucide("Link", listOf("M10,13a5,5 0 0,0 7.54,0.54l3,-3a5,5 0 0,0 -7.07,-7.07l-1.72,1.71", "M14,11a5,5 0 0,0 -7.54,-0.54l-3,3a5,5 0 0,0 7.07,7.07l1.71,-1.71")) }

/** lucide:download */
val Download: ImageVector by lazy { buildLucide("Download", listOf("M12,15V3", "M21,15v4a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2v-4", "m7,10l5,5l5,-5")) }

/** lucide:copy */
val Copy: ImageVector by lazy { buildLucide("Copy", listOf("M10,8H20A2,2 0 0,1 22,10V20A2,2 0 0,1 20,22H10A2,2 0 0,1 8,20V10A2,2 0 0,1 10,8Z", "M4,16c-1.1,0 -2,-0.9 -2,-2V4c0,-1.1 0.9,-2 2,-2h10c1.1,0 2,0.9 2,2")) }

/** lucide:battery-warning */
val BatteryWarning: ImageVector by lazy { buildLucide("BatteryWarning", listOf("M10,17h0.01", "M10,7v6", "M14,6h2a2,2 0 0,1 2,2v8a2,2 0 0,1 -2,2h-2", "M22,14v-4", "M6,18H4a2,2 0 0,1 -2,-2V8a2,2 0 0,1 2,-2h2")) }

/** lucide:clipboard-list */
val ClipboardList: ImageVector by lazy { buildLucide("ClipboardList", listOf("M9,2H15A1,1 0 0,1 16,3V5A1,1 0 0,1 15,6H9A1,1 0 0,1 8,5V3A1,1 0 0,1 9,2Z", "M16,4h2a2,2 0 0,1 2,2v14a2,2 0 0,1 -2,2H6a2,2 0 0,1 -2,-2V6a2,2 0 0,1 2,-2h2", "M12,11h4", "M12,16h4", "M8,11h0.01", "M8,16h0.01")) }

/** lucide:file */
val File: ImageVector by lazy { buildLucide("File", listOf("M6,22a2,2 0 0,1 -2,-2V4a2,2 0 0,1 2,-2h8a2.4,2.4 0 0,1 1.704,0.706l3.588,3.588A2.4,2.4 0 0,1 20,8v12a2,2 0 0,1 -2,2Z", "M14,2v5a1,1 0 0,0 1,1h5")) }

/** lucide:send */
val Send: ImageVector by lazy { buildLucide("Send", listOf("M14.536,21.686a0.5,0.5 0 0,0 0.937,-0.024l6.5,-19a0.496,0.496 0 0,0 -0.635,-0.635l-19,6.5a0.5,0.5 0 0,0 -0.024,0.937l7.93,3.18a2,2 0 0,1 1.112,1.11Z", "m21.854,2.147l-10.94,10.939")) }

/** lucide:external-link */
val ExternalLink: ImageVector by lazy { buildLucide("ExternalLink", listOf("M15,3h6v6", "M10,14L21,3", "M18,13v6a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2V8a2,2 0 0,1 2,-2h6")) }

}
