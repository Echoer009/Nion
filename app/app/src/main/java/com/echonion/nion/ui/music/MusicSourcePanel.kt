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
import androidx.compose.material.icons.Icons
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
 * 内容三项（点击切换主界面音源，选中项高亮）：
 * 1. 本地曲目 —— MediaStore 全机音频
 * 2. 类脑娘服务器 —— 预置 https://echonion.org/music
 * 3. 自己的服务器 —— 用户自填 URL + 账密
 * 未配置账密的服务器项右侧显示"未配置"角标，提示用户去主界面补全配置。
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
        SourceItem(MusicSource.LOCAL, "本地曲目", "扫描本机全部音频", LucideIcons.AudioLines),
        SourceItem(MusicSource.BRAINGIRL, "类脑娘服务器", MusicViewModel.BRAINGIRL_SERVER_URL, LucideIcons.Cloud),
        SourceItem(MusicSource.CUSTOM, "自己的服务器", "连接你自己的 Navidrome", LucideIcons.Server),
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
            // ===== 标题栏 =====
            Text(
                "音乐音源",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 副标题说明：选中后主界面立即切换到对应音源
            Text(
                "选择一个音源开始听歌",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ===== 音源列表 =====
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items.size) { index ->
                    val item = items[index]
                    // 引导态标注：服务器账密未填写时该项显示"未配置"角标
                    val unconfigured = when (item.source) {
                        MusicSource.LOCAL -> false
                        MusicSource.BRAINGIRL -> !vm.isBraingirlConfigured
                        MusicSource.CUSTOM -> !vm.isCustomConfigured
                    }
                    SourceRow(
                        item = item,
                        isSelected = vm.source == item.source,
                        unconfigured = unconfigured,
                        // 点击切换音源：主界面 AnimatedContent 按新 source 切换三态
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
 * @property description 副标题说明（类脑娘项显示预置地址）
 * @property icon 左侧图标
 */
private data class SourceItem(
    val source: MusicSource,
    val label: String,
    val description: String,
    val icon: ImageVector,
)

/**
 * 单个音源行 —— 图标 + 名称 + 说明 + "未配置"角标。
 *
 * 选中态视觉：secondaryContainer 圆角背景 + 图标/文字染 primary 色，
 * 与项目其他侧边栏（清单列表）的选中风格一致。
 *
 * @param item 音源配置
 * @param isSelected 是否为当前选中音源（高亮显示）
 * @param unconfigured 账密是否未配置（显示"未配置"角标）
 * @param onSelect 点击回调，切换到该音源
 */
@Composable
private fun SourceRow(
    item: SourceItem,
    isSelected: Boolean,
    unconfigured: Boolean,
    onSelect: () -> Unit,
) {
    // 选中/未选中使用不同容器色；用 animateColorAsState 平滑过渡避免生硬跳变
    val containerColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "sourceRowColor",
    )
    // 图标与标题颜色：选中时用 primary 强调，未选中用中性 onSurfaceVariant
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 先设背景色再响应点击，保证涟漪效果被限制在圆角内
            .background(containerColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = contentColor,
            modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 未配置角标：引导用户进入主界面补全账密/URL
        if (unconfigured) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    "未配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // 选中标记：右侧小圆点，强化当前音源指示
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}
