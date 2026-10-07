package com.echonion.nion.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.icons.LucideIcons

/**
 * 音乐音源侧边面板 —— 从音乐界面左滑呼出。
 *
 * 严格复用 DualPanelLayout 左侧面板槽位的接法（与 FocusStatsPanel 相同）：
 * - 接收 onSidebarDrag / onSidebarDragStopped 把拖拽事件转发给 DualPanelLayout，
 *   实现按住面板向左滑回关闭
 * - Surface 使用 RoundedCornerShape(topEnd, bottomEnd) 匹配左侧面板圆角
 * - 内部 LazyColumn 展示三个音源项
 *
 * 内容三项（本地曲目 / 类脑娘服务器 / 自己的服务器），
 * 点击切换主界面音源，选中项高亮。
 *
 * @param onSidebarDrag 拖拽回调，传递给 DualPanelLayout 的 handleDrag
 * @param onSidebarDragStopped 拖拽结束回调，传递给 DualPanelLayout 的 settle
 * @param modifier 由 DualPanelLayout 传入的 modifier（含宽度等约束）
 */
@Composable
fun MusicSourcePanel(
    onSidebarDrag: (Float) -> Unit,
    onSidebarDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Activity 作用域的共享 ViewModel —— 与 MusicScreen 持有同一实例，
    // 此处切换音源后主界面立即生效
    val vm = musicViewModel()

    // 音源项配置列表：顺序固定 本地→类脑娘→自定义，与主界面切换动画的方向计算一致
    val items = listOf(
        SourceItem(MusicSource.LOCAL, "本地曲目", LucideIcons.AudioLines),
        SourceItem(MusicSource.BRAINGIRL, "类脑娘服务器", LucideIcons.Cloud),
        SourceItem(MusicSource.CUSTOM, "自己的服务器", LucideIcons.Server),
    )

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
            // ===== 标题栏（与清单侧边栏同款：仅标题 + 20dp 间距，无副标题）=====
            Text(
                "音乐音源",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ===== 音源列表（间距与清单侧边栏一致：4dp）=====
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items.size) { index ->
                    val item = items[index]
                    // 点击切换音源：主界面 AnimatedContent 按新 source 切换三态
                    SourceRow(
                        item = item,
                        isSelected = vm.source == item.source,
                        onSelect = { vm.selectSource(item.source) },
                    )
                }
            }
        }
    }
}

/**
 * 音源项的数据配置。
 *
 * @property source 对应的音源枚举
 * @property label 显示名称
 * @property icon 左侧图标
 */
private data class SourceItem(
    val source: MusicSource,
    val label: String,
    val icon: ImageVector,
)

/**
 * 单个音源行 —— 与清单侧边栏项（SidebarChecklistItem）完全同款的选中展示：
 *
 * - 选中 = secondaryContainer 背景 + onPrimaryContainer 内容 + 左缘 4dp×32dp
 *   primary 竖条（选中时起始内距 10dp，未选 14dp），标题 SemiBold；
 * - 未选 = surfaceContainer 背景 + onSurfaceVariant 内容；
 * - 12dp 圆角 Surface，颜色 animateColorAsState 200ms 平滑过渡；
 * - 图标 18dp 淡透明（与清单项同款），行内仅保留图标与名称。
 *
 * @param item 音源配置
 * @param isSelected 是否为当前选中音源（高亮显示）
 * @param onSelect 点击回调，切换到该音源
 */
@Composable
private fun SourceRow(
    item: SourceItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    // 与 SidebarChecklistItem 同款配色逻辑（secondaryContainer ≠ 主操作 primary）
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "sourceRowBg",
    )
    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "sourceRowContent",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = if (isSelected) 10.dp else 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选中指示：左缘 4dp×32dp 主色竖条（清单项同款）
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor.copy(alpha = com.echonion.nion.ui.theme.NionAlpha.TEXT_SUBTLE),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

        }
    }
}
