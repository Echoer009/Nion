package com.echonion.nion.ui.task

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch

private val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * Nion 自定义日历 —— 与日程页日历风格一致。
 *
 * 年月选择器使用 SharedTransitionLayout + 双 sharedElement（容器 + 文字），与日程页一致。
 * 容器 morph：日历头部 Surface ↔ 选择器 Surface（仅 header 区域，不含 pager，避免日期闪烁）。
 * 文字 morph：日历头部年月文字 ↔ 选择器头部年份文字。
 * 与 ScheduleScreen 中 MonthHeader ↔ CalendarPickerDialog 的 morph 模式完全一致。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NionCalendar(
    initialYearMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { Int.MAX_VALUE },
    )
    val scope = rememberCoroutineScope()

    // monthOffset: 当前 pager settled 页相对起始页的月份偏移量
    val monthOffset = pagerState.settledPage - startPage
    // displayedYearMonth: 根据偏移量计算当前显示的年月
    val displayedYearMonth = remember(initialYearMonth, monthOffset) {
        initialYearMonth.plusMonths(monthOffset.toLong())
    }

    // showYearMonthPicker: 是否显示年月选择器（覆盖日历网格）
    var showYearMonthPicker by remember { mutableStateOf(false) }
    // pickerYear: 年月选择器中当前选中的年份
    var pickerYear by remember { mutableStateOf(displayedYearMonth.year) }

    // navigateTarget: 选择月份后需要跳转到的目标年月，LaunchedEffect 负责执行 pager 动画
    var navigateTarget by remember { mutableStateOf<YearMonth?>(null) }
    LaunchedEffect(navigateTarget) {
        navigateTarget?.let { target ->
            val diff = ChronoUnit.MONTHS.between(initialYearMonth, target).toInt()
            pagerState.animateScrollToPage(startPage + diff)
            navigateTarget = null
        }
    }

    // 与日程页 ScheduleScreen 完全一致的模式：SharedTransitionLayout + 双 sharedElement
    // 参照 ScheduleScreen 中 MonthHeader ↔ CalendarPickerDialog 的 morph 实现
    SharedTransitionLayout(modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = showYearMonthPicker,
            // 与日程页 ScheduleScreen transitionSpec 完全一致
            transitionSpec = {
                if (targetState) {
                    // 展开：picker 渐入，日历渐出
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收回：日历渐入，picker 渐出
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "yearMonthPicker",
        ) { showPicker ->
            if (showPicker) {
                // —— 年月选择器面板 ——
                // 容器 sharedElement 绑在此 Surface，与日历头部 Surface 对应
                // 参照 ScheduleScreen 的 CalendarPickerDialog Surface
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sharedElement(
                            sharedContentState = rememberSharedContentState("yearMonthContainer"),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ ->
                                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                            },
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 年份导航行：左箭头 + 年份文字 + 右箭头
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { pickerYear = (pickerYear - 1).coerceAtLeast(1900) }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一年")
                            }
                            // 年份文字，不使用 sharedElement（两边文字内容不同 "2026年" vs "2026年 5月"，
                            // morph 时最后一个字会闪烁），由容器 Surface 的 bounds morph 提供动画
                            Text(
                                "${pickerYear}年",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(onClick = { pickerYear = (pickerYear + 1).coerceAtMost(2100) }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // 12 个月份按钮，3 行 × 4 列排列
                        for (row in 0..2) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (col in 0..3) {
                                    val month = row * 4 + col + 1
                                    val ym = YearMonth.of(pickerYear, month)
                                    // isCurrent: 该月是否为当前日历显示的月份，用于高亮
                                    val isCurrent = ym == displayedYearMonth
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(4.dp)
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    // 当前月份高亮使用 tertiary（装饰性标记）
                                                    isCurrent -> MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.BG_HIGHLIGHT)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                // 点击月份：设置跳转目标并关闭选择器
                                                onClick = {
                                                    navigateTarget = ym
                                                    showYearMonthPicker = false
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "${month}月",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // "返回日历" 按钮，点击关闭年月选择器
                        TextButton(onClick = { showYearMonthPicker = false }) {
                            Text("返回日历", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // —— 日历主视图 ——
                // 注意：容器 sharedElement 只绑在头部 Surface 上，不包含 pager，避免日期闪烁
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 月份头部 Surface：容器 sharedElement + 月份切换导航
                    CalendarHeader(
                        displayedYearMonth = displayedYearMonth,
                        animatedContentScope = this@AnimatedContent,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onPreviousMonth = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        },
                        onNextMonth = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        onYearMonthClick = {
                            pickerYear = displayedYearMonth.year
                            showYearMonthPicker = true
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 星期标签行
                    WeekDayLabels()
                    Spacer(modifier = Modifier.height(8.dp))
                    // 月份日期网格，使用 HorizontalPager 支持左右滑动切换月份
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(264.dp),
                    ) { page ->
                        val pageOffset = page - startPage
                        val month = remember(initialYearMonth, pageOffset) {
                            initialYearMonth.plusMonths(pageOffset.toLong())
                        }
                        CalendarMonthGrid(
                            yearMonth = month,
                            today = today,
                            selectedDate = selectedDate,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日历头部导航 —— 包含月份切换箭头和年月文字（可点击打开年月选择器）。
 *
 * 容器 Surface 绑定 sharedElement（"yearMonthContainer"），与年月选择器面板的 Surface 对应，
 * 实现 morph 过渡动画。参照 ScheduleScreen 的 MonthHeader Surface。
 *
 * @param displayedYearMonth 当前显示的年月
 * @param animatedContentScope 外层 AnimatedContent 的作用域，用于 sharedElement 绑定
 * @param onPreviousMonth 点击上月箭头回调
 * @param onNextMonth 点击下月箭头回调
 * @param onYearMonthClick 点击年月文字回调，打开年月选择器
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CalendarHeader(
    displayedYearMonth: YearMonth,
    animatedContentScope: androidx.compose.animation.AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onYearMonthClick: () -> Unit,
) {
    with(sharedTransitionScope) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .sharedElement(
                sharedContentState = rememberSharedContentState("yearMonthContainer"),
                animatedVisibilityScope = animatedContentScope,
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                },
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // 月份切换行：左箭头 + 年月文字（可点击打开选择器）+ 右箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月")
            }
            // 年月文字：不使用 sharedElement（两边文字内容不同会闪烁），由容器 Surface 的 bounds morph 提供动画
            Text(
                displayedYearMonth.format(
                    DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINESE)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onYearMonthClick,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
            }
        }
    }
    }
}

/**
 * 星期标签行 —— 显示"一、二、三、四、五、六、日"七个标签。
 */
@Composable
private fun WeekDayLabels() {
    Row(modifier = Modifier.fillMaxWidth()) {
        dayLabels.forEach { label ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                )
            }
        }
    }
}

/**
 * 单列滚轮选择器 —— 上下滑动选择一项，滚动停止后自动 snap 到最近整数位置。
 *
 * 利用 LazyColumn 实现滚轮效果，前后各加 padding 行让首尾数据项能滚到中心。
 * 选中项有渐变色、缩放和透明度效果，离中心越远越淡越小，提供流畅的视觉反馈。
 * graphicsLayer lambda 在绘制阶段读取滚动位置，驱动 scale/alpha 动画而不触发重组。
 *
 * @param items 显示的文本列表（如 ["00", "01", ..., "23"]）
 * @param initialIndex 初始选中项的索引
 * @param visibleItemCount 可见行数（必须为奇数，中间行为选中行）
 * @param itemHeight 每行的高度
 * @param onSelected 选中项变更回调，传入新的索引
 * @param modifier 外部 modifier，用于控制宽度等布局属性
 */
/**
 * 滚轮选择器 —— 基于 LazyColumn 实现的 wheel picker。
 *
 * 每行显示一个文本项，中心行高亮（放大、加粗、主色），
 * 向两侧渐变缩小、淡化，营造 3D 滚筒效果。
 * 滚动停止后自动 snap 到最近的整数位置。
 *
 * @param items 显示文本列表，如 ["00","01",...,"23"]
 * @param initialIndex 初始选中项索引（在 items 中的位置）
 * @param visibleItemCount 可见行数（推荐奇数，如 5）
 * @param itemHeight 每行高度
 * @param onSelected 选中项变更回调，传入新的索引（在 items 中的位置）
 * @param modifier 外部 modifier
 * @param circular 是否启用循环滚动。true 时首尾衔接（00 上方是 23），false 时到头停止
 */
@Composable
fun WheelSpinner(
    items: List<String>,
    initialIndex: Int,
    visibleItemCount: Int,
    itemHeight: Dp,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否启用循环滚动：true 时 00 上方接 23，滑不到头 */
    circular: Boolean = false,
) {
    val halfVisibleCount = visibleItemCount / 2
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // itemHeight 转换为像素，用于计算连续的中心位置
    val itemHeightPx = with(density) { itemHeight.toPx() }

    // 安全的初始索引，确保在 items 范围内
    val safeInitial = initialIndex.coerceIn(0, items.lastIndex)

    // 循环模式：数据行数量用 100000 模拟无限列表（不能用 Int.MAX_VALUE，LazyColumn 内部 IntervalList 会溢出）；
    // 初始位置设在中间附近并对齐数据周期，确保向两端都能滑很远
    val totalDataItems = if (circular) 100_000 else items.size
    val startVirtualIndex = if (circular) {
        val mid = 50_000
        // 对齐到数据周期起始位置后偏移 safeInitial，确保初始显示正确
        mid - mid % items.size + safeInitial
    } else {
        safeInitial
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startVirtualIndex,
    )

    // 跟踪上一次上报的索引，避免重复回调
    var lastReportedIndex by remember { mutableStateOf(safeInitial) }

    // 连续的中心位置（浮点数），用于计算每个 item 与中心的距离
    // 公式：firstVisibleItemIndex + 偏移比例（offset / itemHeight）
    val continuousCenter by remember {
        derivedStateOf {
            val offset = if (itemHeightPx > 0f) {
                listState.firstVisibleItemScrollOffset.toFloat() / itemHeightPx
            } else {
                0f
            }
            listState.firstVisibleItemIndex + offset
        }
    }

    // 滚动停止后 snap 到最近的整数位置，并回调选中项
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val rawTarget = continuousCenter.roundToInt()
            // 循环模式不限制范围（无限列表），非循环模式限制在数据索引范围内
            val target = if (circular) rawTarget
                else rawTarget.coerceIn(0, items.lastIndex)
            // 如果不在整数位置，执行 snap 动画
            if (listState.firstVisibleItemIndex != target ||
                listState.firstVisibleItemScrollOffset != 0
            ) {
                listState.animateScrollToItem(target)
            }
            // 循环模式：虚拟位置映射回真实索引；非循环模式：直接使用
            val realIndex = if (circular) {
                ((target % items.size) + items.size) % items.size
            } else {
                target
            }
            // snap 目标与上次上报不同时，回调通知外部
            if (realIndex != lastReportedIndex) {
                lastReportedIndex = realIndex
                onSelected(realIndex)
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(
        state = listState,
        modifier = modifier.height(itemHeight * visibleItemCount),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部 padding 行：占 halfVisibleCount 行，让第一个数据项能滚到中心
        items(halfVisibleCount) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                contentAlignment = Alignment.Center,
            ) {}
        }

        // 数据行：循环模式用 Int.MAX_VALUE 模拟无限列表
        items(totalDataItems) { virtualIndex ->
            // 循环模式：虚拟索引映射回真实数据索引
            val realIndex = if (circular) virtualIndex % items.size else virtualIndex
            // 计算与中心的连续距离，驱动渐变色/字重（组合阶段）
            val absDist = abs(virtualIndex - continuousCenter)
            // 渐变色进度：1.0 = 完全选中色，0.0 = 完全未选中色
            val colorProgress = (1f - absDist * 0.6f).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .graphicsLayer {
                        // 在绘制阶段读取 continuousCenter，驱动缩放和透明度动画
                        val distance = virtualIndex - continuousCenter
                        val ad = abs(distance)
                        // 缩放：中心 1.0，每行缩小 6%，最小 0.7
                        val scale = (1f - ad * 0.06f).coerceIn(0.7f, 1f)
                        scaleX = scale
                        scaleY = scale
                        // 透明度：中心 1.0，向边缘线性衰减，最小 0.15
                        alpha = (1f - ad * 0.5f).coerceIn(0.15f, 1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                // 点击后动画滚动到被点击的项
                                listState.animateScrollToItem(virtualIndex)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = items[realIndex],
                    style = MaterialTheme.typography.headlineMedium,
                    // 距中心 < 0.5 行时显示粗体，过渡更自然
                    fontWeight = if (absDist < 0.5f) FontWeight.Bold else FontWeight.Normal,
                    // 颜色在 primary 和 onSurfaceVariant 之间渐变
                    color = lerp(unselectedColor, primaryColor, colorProgress),
                )
            }
        }

        // 底部 padding 行：占 halfVisibleCount 行，让最后一个数据项能滚到中心
        items(halfVisibleCount) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                contentAlignment = Alignment.Center,
            ) {}
        }
    }
}

/**
 * 单个月的日期网格 —— 渲染指定年月的日历格子。
 * 固定渲染 6 行，空位补齐，避免不同月份行数不同导致高度变化。
 */
@Composable
private fun CalendarMonthGrid(
    yearMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val firstDay = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val startDow = firstDay.dayOfWeek.value - 1
    val rows = 6

    Column {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val day = cellIndex - startDow + 1
                    val isValid = day in 1..daysInMonth
                    val date = if (isValid) yearMonth.atDay(day) else null
                    val isToday = date == today
                    val isSelected = date != null && date == selectedDate

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    // "今天"标记使用 tertiary（装饰性区分）
                                    isToday -> MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.BG_HIGHLIGHT)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isValid,
                                onClick = {
                                    if (date != null) onSelect(date)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isValid) {
                            Text(
                                day.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
