package com.echonion.nion.ui.focus

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 统计周期配置 —— 用于 Tab 切换的数据模型。
 *
 * @property days 查询天数：1=今天, 7=近一周, 30=近一月
 * @property label 显示标签（"日"/"周"/"月"）
 */
private data class Period(val days: Int, val label: String)

/**
 * 专注统计侧边面板 —— 从番茄钟界面左滑呼出。
 *
 * 复用 DualPanelLayout 的左侧面板槽位，遵循与 SidebarContent 相同的模式：
 * - 接收 onSidebarDrag / onSidebarDragStopped 实现滑回关闭
 * - Surface 使用 RoundedCornerShape(topEnd, bottomEnd) 匹配左侧面板圆角
 * - 内部 LazyColumn 展示统计内容
 *
 * @param onSidebarDrag 拖拽回调，传递给 DualPanelLayout 的 handleDrag
 * @param onSidebarDragStopped 拖拽结束回调，传递给 DualPanelLayout 的 settle
 * @param modifier 由 DualPanelLayout 传入的 modifier（含宽度等约束）
 */
@Composable
fun FocusStatsPanel(
    onSidebarDrag: (Float) -> Unit,
    onSidebarDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: FocusStatsViewModel = viewModel(
        factory = FocusStatsViewModel.Factory(context.applicationContext as android.app.Application)
    )
    val state = vm.state

    // 周期选项列表，顺序为 日→周→月
    val periods = remember {
        listOf(
            Period(1, "日"),
            Period(7, "周"),
            Period(30, "月"),
        )
    }

    // 当前选中周期的索引，用于计算指示器位置
    val selectedIndex = periods.indexOfFirst { it.days == vm.selectedDays }.coerceAtLeast(0)

    Surface(
        modifier = modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> onSidebarDrag(delta) },
            onDragStopped = { onSidebarDragStopped() },
        ),
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp, horizontal = 16.dp),
        ) {
            // ===== 标题栏 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "专注统计",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = { vm.loadStats() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== 统计周期 Tab（带底部滑动指示器） =====
            PeriodTabRow(
                periods = periods,
                selectedIndex = selectedIndex,
                onSelectPeriod = { vm.selectPeriod(it) },
            )

            Spacer(modifier = Modifier.height(20.dp))

            /**
             * 统计内容区 —— AnimatedContent 按 selectedDays 切换，带水平滑动 + 淡入淡出动画。
             *
             * 切换方向规则：
             * - 新周期天数 > 旧周期天数（日→周、日→月、周→月）：新内容从右滑入，旧内容向左滑出
             * - 新周期天数 < 旧周期天数（月→周、月→日、周→日）：新内容从左滑入，旧内容向右滑出
             *
             * 加载指示器仅在首次加载（无缓存数据）时显示，避免切换时闪白。
             */
            AnimatedContent(
                targetState = vm.selectedDays,
                transitionSpec = {
                    // 根据新旧周期天数的大小关系决定滑动方向
                    // direction > 0 表示向更大的周期切换（日→周→月），新内容从右侧进入
                    // direction < 0 表示向更小的周期切换（月→周→日），新内容从左侧进入
                    val direction = if (targetState > initialState) 1 else -1

                    (
                        // 新内容滑入 + 淡入：initialOffsetX 返回的是"初始偏移量占自身宽度的比例"
                        slideInHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> direction * fullWidth / 3 },
                        ) + fadeIn(tween(200, delayMillis = 50))
                    ) togetherWith (
                        // 旧内容滑出 + 淡出：targetOffsetX 返回的是"目标偏移量占自身宽度的比例"
                        slideOutHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> -direction * fullWidth / 3 },
                        ) + fadeOut(tween(150))
                    ) using SizeTransform(clip = false)
                },
                label = "periodContent",
            ) { targetDays ->
                if (state.isLoading && state.daily.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "加载中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        // ===== 总览卡片 =====
                        item(key = "summary") {
                            SummaryCard(
                                totalSeconds = state.totalSeconds,
                                totalSessions = state.totalSessions,
                                periodLabel = periods.find { it.days == targetDays }?.label ?: "日",
                            )
                        }

                        // ===== 每日分布 =====
                        if (state.daily.isNotEmpty()) {
                            item(key = "daily_header") {
                                SectionHeader("每日分布")
                            }
                            items(state.daily, key = { it.date }) { item ->
                                DailyBarRow(
                                    date = item.date,
                                    totalSeconds = item.totalSeconds,
                                    maxSeconds = state.daily.maxOfOrNull { it.totalSeconds }?.coerceAtLeast(1L) ?: 1L,
                                )
                            }
                        }

                        // ===== 任务分布 =====
                        if (state.taskBreakdown.isNotEmpty()) {
                            item(key = "task_header") {
                                SectionHeader("任务分布")
                            }
                            items(state.taskBreakdown, key = { it.taskId }) { item ->
                                TaskBarRow(
                                    title = item.taskTitle,
                                    seconds = item.seconds,
                                    maxSeconds = state.taskBreakdown.maxOfOrNull { it.seconds }?.coerceAtLeast(1L) ?: 1L,
                                )
                            }
                        }

                        // 空状态
                        if (state.daily.isEmpty() && state.taskBreakdown.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "暂无专注记录",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日/周/月切换 Tab 栏 —— 带底部滑动指示器。
 *
 * 布局结构：
 * - 底层：圆角背景容器（surfaceContainerHighest）
 * - 中层：三个等宽可点击文本按钮（透明背景）
 * - 底层：彩色指示条（primary 色），跟随选中项平滑滑动
 *
 * @param periods 周期选项列表
 * @param selectedIndex 当前选中项索引
 * @param onSelectPeriod 点击回调，传递选中周期的 days 值
 */
@Composable
private fun PeriodTabRow(
    periods: List<Period>,
    selectedIndex: Int,
    onSelectPeriod: (Int) -> Unit,
) {
    val density = LocalDensity.current

    // 记录 Tab 栏的总宽度（像素），用于计算每个 Tab 的宽度和指示器位置
    var tabRowWidthPx by remember { mutableIntStateOf(0) }

    // 单个 Tab 的宽度（dp），三个 Tab 等宽
    val tabWidthDp = with(density) { (tabRowWidthPx / periods.size).toDp() }

    // 指示器偏移量动画：根据 selectedIndex 平滑滑动到对应 Tab 位置
    // 使用 FastOutSlowInEasing 缓动曲线，先快后慢，符合 Material 动画规范
    val indicatorOffsetDp: Dp by animateDpAsState(
        targetValue = tabWidthDp * selectedIndex,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        ),
        label = "indicatorOffset",
    )

    // 指示器宽度，略窄于 Tab 宽度，左右各留 8dp 边距，形成"悬浮"效果
    val indicatorWidthDp = tabWidthDp - 16.dp * 2

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 记录总宽度，用于后续计算
            .onSizeChanged { tabRowWidthPx = it.width },
    ) {
        // ===== 底部滑动指示器 =====
        // 位于 Tab 栏底部，跟随选中项水平移动
        if (tabRowWidthPx > 0) {
            Box(
                modifier = Modifier
                    .offset(x = 16.dp + indicatorOffsetDp)
                    .align(Alignment.BottomStart)
                    .padding(bottom = 4.dp)
                    .width(indicatorWidthDp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        // ===== Tab 按钮行 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            periods.forEachIndexed { index, period ->
                val isSelected = index == selectedIndex

                // 文字颜色动画：选中时用 primary 色，未选中时用 onSurfaceVariant
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(300),
                    label = "tabTextColor",
                )

                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color.Transparent,
                    onClick = { onSelectPeriod(period.days) },
                ) {
                    Text(
                        period.label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = textColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 总览卡片 —— 显示当前周期的总专注时长和会话次数。
 *
 * @param totalSeconds 总专注秒数
 * @param totalSessions 总会话次数
 * @param periodLabel 周期标签（"日"/"周"/"月"）
 */
@Composable
private fun SummaryCard(
    totalSeconds: Long,
    totalSessions: Int,
    periodLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        // 统计摘要卡片使用 secondaryContainer（信息性）
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "本${periodLabel}专注",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                formatDuration(totalSeconds),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${totalSessions} 次专注",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 分组标题 —— 用于分隔"每日分布"和"任务分布"区块。
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * 每日柱状行 —— 显示日期、时长和相对于最大值的进度条。
 *
 * @param date 日期字符串 "YYYY-MM-DD"
 * @param totalSeconds 当天总秒数
 * @param maxSeconds 当前周期中最大的单日秒数，用于计算进度条宽度比例
 */
@Composable
private fun DailyBarRow(
    date: String,
    totalSeconds: Long,
    maxSeconds: Long,
) {
    val fraction = if (maxSeconds > 0) totalSeconds.toFloat() / maxSeconds.toFloat() else 0f
    val isToday = date == java.time.LocalDate.now().toString()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 日期标签
        Text(
            if (isToday) "今天" else date.takeLast(5),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 进度条
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(fraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        // 今日柱状图使用 primary，历史柱状图使用 tertiary（装饰性区分）
                        if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary
                    ),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 时长文字
        Text(
            formatDuration(totalSeconds),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * 任务分布行 —— 显示任务标题、时长和进度条。
 *
 * @param title 任务标题
 * @param seconds 该任务累计秒数
 * @param maxSeconds 当前周期中耗时最多的任务秒数
 */
@Composable
private fun TaskBarRow(
    title: String,
    seconds: Long,
    maxSeconds: Long,
) {
    val fraction = if (maxSeconds > 0) seconds.toFloat() / maxSeconds.toFloat() else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 任务标题
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 进度条
        Box(
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 时长
        Text(
            formatDuration(seconds),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 格式化秒数为可读时长字符串。
 *
 * @param seconds 秒数
 * @return 如 "2h30m"、"45m"、"0m"
 */
private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0m"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
