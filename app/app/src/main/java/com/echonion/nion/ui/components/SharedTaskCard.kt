package com.echonion.nion.ui.components

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.task.formatReminder
import com.echonion.nion.ui.task.formatReminderDate
import com.echonion.nion.ui.task.isReminderOverdue
import com.echonion.nion.ui.task.isReminderToday
import com.echonion.nion.ui.task.priorityColor
import com.echonion.nion.ui.theme.LocalPriorityColors

/**
 * 共享任务卡片 UI 数据模型 —— 统一任务列表和日程页面的卡片数据。
 *
 * 两个页面各自的 ViewModel 数据模型（TaskItem / ScheduleTaskItem）
 * 通过 toCardModel() 扩展函数映射到此类型，供 SharedTaskCard 消费。
 */
data class TaskCardModel(
    val id: String,
    val name: String,
    val description: String? = null,
    val priority: String,
    val isDone: Boolean,
    val isDaily: Boolean = false,
    val reminderTime: String? = null,
    /** 一次性提醒时间，格式 "YYYY-MM-DDTHH:MM"，由卡片上的铃铛图标 + 完整日期时间展示 */
    val reminder: String? = null,
)

/**
 * 共享任务卡片 —— 任务列表和日程页面复用的任务行渲染组件。
 *
 * 视觉规范：
 * - 背景色随完成状态动画切换（surfaceContainerLowest / surfaceContainerLow）
 * - 勾选框带弹跳动画，未完成时显示 RadioButtonUnchecked 图标（颜色跟随优先级）
 * - 标题完成时带删除线，每日任务末尾显示循环图标
 * - 可选显示描述（单行省略）和截止日期/提醒时间行
 * - 可选显示折叠/展开箭头图标（有子任务的主任务使用）
 *
 * @param model 任务卡片数据
 * @param onToggleDone 勾选框点击回调，切换任务完成状态
 * @param onClick 卡片整体点击回调，null 表示不可点击（日程页面不需要点击打开详情）
 * @param shape 卡片圆角形状，用于背景裁切；任务列表在有分组时需要特殊 shape
 * @param modifier 在 background() 之前注入的 modifier，用于任务列表添加 group border / selection / shared element
 * @param compact 紧凑模式，true 时缩小内边距（日程页面使用）
 * @param showCollapseArrow 是否显示折叠/展开箭头图标
 * @param arrowRotation 箭头旋转角度（展开=0°朝下，折叠=-90°朝右）
 * @param onToggleCollapse 点击箭头图标时触发，切换折叠状态
 */
@Composable
fun SharedTaskCard(
    model: TaskCardModel,
    onToggleDone: () -> Unit,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showCollapseArrow: Boolean = false,
    arrowRotation: Float = 0f,
    onToggleCollapse: (() -> Unit)? = null,
) {
    // 背景色动画：已完成 → surfaceContainerLow，未完成 → surfaceContainerLowest
    val cardColor by animateColorAsState(
        targetValue = if (model.isDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

    // 勾选框弹跳动画：从 false → true 时播放 scale 1.0 → 1.3 → 1.0
    val checkScale = remember { Animatable(1f) }
    // wasDone 记录上一次 isDone，区分首次 composition 和真正状态变化
    var wasDone by remember { mutableStateOf(model.isDone) }
    LaunchedEffect(model.isDone) {
        if (model.isDone && !wasDone) {
            checkScale.animateTo(1.3f, tween(120))
            checkScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
        }
        wasDone = model.isDone
    }

    // 勾选框背景色动画：完成时 primary，未完成时透明
    val checkBgColor by animateColorAsState(
        targetValue = if (model.isDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    // 优先级颜色，通过 LocalPriorityColors 获取跟随主题变化的颜色
    val priorityColors = LocalPriorityColors.current
    val priorityColor = model.priority.priorityColor(priorityColors)

    // 紧凑模式下缩小内边距
    val horizontalPadding = if (compact) 14.dp else 16.dp
    val verticalPadding = if (compact) 10.dp else 14.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .background(cardColor, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 勾选框：带弹跳动画的圆形 checkbox
        TaskCheckbox(
            isDone = model.isDone,
            checkScale = checkScale.value,
            checkBgColor = checkBgColor,
            priorityColor = priorityColor,
            onToggleDone = onToggleDone,
        )
        Spacer(modifier = Modifier.width(14.dp))
        // 文字信息列：标题 + 描述 + 提醒时间
        TaskTextContent(model = model, modifier = Modifier.weight(1f))
        // 折叠/展开箭头图标，仅在有子任务时显示
        if (showCollapseArrow && onToggleCollapse != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (arrowRotation == -90f) "展开子任务" else "折叠子任务",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleCollapse,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 任务勾选框 —— 带弹跳动画的圆形 checkbox。
 *
 * 完成时显示勾选图标（primary 背景），未完成时显示空心圆图标（颜色跟随优先级）。
 * 弹跳动画由 checkScale 驱动，在外层通过 Animatable 控制。
 *
 * @param isDone 是否已完成
 * @param checkScale 弹跳缩放值，由外层 Animatable 驱动
 * @param checkBgColor 勾选框背景色，完成时 primary，未完成时透明
 * @param priorityColor 优先级对应的颜色
 * @param onToggleDone 点击切换完成状态回调
 */
@Composable
private fun TaskCheckbox(
    isDone: Boolean,
    checkScale: Float,
    checkBgColor: Color,
    priorityColor: Color,
    onToggleDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .scale(checkScale)
            .clip(CircleShape)
            .background(checkBgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleDone,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isDone) {
            Icon(
                Icons.Default.Check,
                contentDescription = "已完成",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = "未完成",
                tint = priorityColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * 任务文字内容区 —— 标题 + 描述 + 提醒时间行的纵向排列。
 *
 * 标题行包含任务名称（完成时带删除线）和每日循环图标（如果有）。
 * 描述单行省略显示。提醒时间行根据任务类型显示不同样式。
 *
 * @param model 任务卡片数据
 */
@Composable
private fun TaskTextContent(
    model: TaskCardModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 标题行：任务名 + 每日循环图标
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textDecoration = if (model.isDone) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 每日任务：卡片末尾只显示循环图标，使用 tertiary 装饰色
            if (model.isDaily) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = "每天",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                )
            }
        }
        // 描述：单行省略，仅在有内容时显示
        if (!model.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 提醒时间显示行
        TaskReminderInfoRow(model = model)
    }
}

/**
 * 任务提醒信息行 —— 显示一次性提醒和/或每日循环提醒时间。
 *
 * 显示规则：
 * - 每日循环任务：只显示时钟图标 + HH:MM，逾期时变红
 * - 一次性提醒任务：显示铃铛图标 + 完整日期时间，逾期时变红
 * - 两者可同时显示（每日循环任务也可设置一次性提醒）
 *
 * @param model 任务卡片数据
 */
@Composable
private fun TaskReminderInfoRow(
    model: TaskCardModel,
) {
    val hasReminderTime = !model.reminderTime.isNullOrBlank()
    // 非每日任务：有 reminder 就显示铃铛；每日任务：reminder 日期不是今天时也显示日期
    val hasReminder = !model.reminder.isNullOrBlank() && (!model.isDaily || !model.reminder.isReminderToday())
    // 每日任务的逾期判断：通过 reminder 字段（自动设为今天日期+时间）检测是否已过时
    val isDailyOverdue = model.isDaily && model.reminder.isReminderOverdue() && !model.isDone
    if (hasReminder || hasReminderTime) {
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 一次性提醒部分：铃铛图标 + 格式化的完整日期时间，逾期变红
            if (hasReminder) {
                val isOverdue = model.reminder.isReminderOverdue() && !model.isDone
                Icon(
                    if (model.isDaily) Icons.Outlined.CalendarToday else Icons.Outlined.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                )
                Spacer(modifier = Modifier.width(4.dp))
                // 每日循环任务只显示日期（如"6月3日"），非每日任务显示完整日期时间
                val displayText = if (model.isDaily) {
                    model.reminder.formatReminderDate() ?: ""
                } else {
                    model.reminder.formatReminder() ?: ""
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 每日循环提醒时间部分：时钟图标 + HH:MM 时间，逾期时图标和文字变红
            if (hasReminderTime) {
                if (hasReminder) Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (isDailyOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = model.reminderTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDailyOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
