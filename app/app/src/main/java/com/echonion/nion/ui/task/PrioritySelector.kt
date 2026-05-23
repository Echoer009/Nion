package com.echonion.nion.ui.task

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 优先级选择器组件
 * 提供"高/中/低"三档选择，选中项会高亮显示对应颜色和下划线指示器
 *
 * @param selected 当前选中的优先级 key
 * @param onSelect 用户点击某一项时触发，回调传入优先级 key
 * @param modifier 外部 modifier
 * @param labelColor 未选中项的文字颜色，默认 onSurfaceVariant（适配深/浅背景）
 * @param indicatorColor 选中时底部指示条的颜色，默认跟随优先级色
 */
@Composable
fun PrioritySelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor: Color? = null,
) {
    val items = listOf("high", "medium", "low")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { key ->
            val isSelected = selected == key
            val color = key.priorityColor
            // 指示条颜色：外部传入则用外部颜色，否则用优先级自身颜色
            val effectiveIndicatorColor = indicatorColor ?: color
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(key) },
                    )
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(
                    key.priorityLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) color else labelColor,
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 选中时的底部指示条
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (isSelected) effectiveIndicatorColor else Color.Transparent),
                )
            }
        }
    }
}
