@file:Suppress("DEPRECATION")
package com.echonion.nion.ui.companion

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.launch
import uniffi.nion_core.ConversationData
import org.json.JSONArray
import com.echonion.nion.ui.companion.tools.MemoryTool

/**
 * 聊天消息数据类 —— 单条消息的展示模型。
 *
 * @param id 唯一标识，用于 LazyColumn 的 key
 * @param text 消息正文
 * @param isFromUser true=用户消息（右侧主题色气泡），false=AI 回复（左侧灰色气泡）
 * @param timestamp 显示时间，格式 HH:mm
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: String,
    /** 是否为工具执行消息（显示为小型状态行而非完整气泡） */
    val isToolMessage: Boolean = false,
    /** 工具是否已执行完成（用对勾替换加载图标） */
    val toolDone: Boolean = false,
)

/**
 * 伙伴侧边栏 —— 从右侧滑入的 AI 对话面板。
 *
 * 根据 ViewModel 状态展示不同内容：
 * - 未配置 API key 时 → 显示 [ApiProviderSetup] 引导页
 * - 已配置 API key 时 → 显示聊天界面
 *
 * @param onSidebarDrag 水平拖拽回调，用于控制面板打开/关闭
 * @param onSidebarDragStopped 拖拽结束回调
 * @param isVisible 面板是否可见，用于自动滚动到最新消息
 * @param viewModel 伙伴 ViewModel，管理消息和配置状态
 * @param modifier 由面板容器传入的修饰符（含 draggable 手势）
 */
@Composable
fun CompanionSidebar(
    onSidebarDrag: (Float) -> Unit,
    onSidebarDragStopped: () -> Unit,
    isVisible: Boolean = false,
    viewModel: CompanionViewModel = viewModel(factory = CompanionViewModel.Factory),
    modifier: Modifier = Modifier,
) {

    // 面板当前模式：null=自动根据 API 配置决定，其余为手动切换的面板
    var panelMode by remember { mutableStateOf<String?>(null) }

    /**
     * 根据 API 配置和模式决定显示哪个界面。
     *
     * 关键：loadSettings 改为异步后，首次组合时 isInitialized=false，
     * 此时 actualMode = "loading"，AnimatedContent 始终在组合树中。
     * 当 IO 线程完成后 isInitialized 变为 true，actualMode 切换到
     * 真实模式（"chat"/"setup"），AnimatedContent 自身的 targetState 变化
     * 会驱动内部重组合——不依赖外部 DualPanelLayout 的重组合传播。
     */
    val actualMode = when {
        // 初始化未完成 → 显示空白占位，确保 AnimatedContent 始终在组合树中
        !viewModel.isInitialized -> "loading"
        panelMode == "profile" -> "profile"
        panelMode == "setup" -> "setup"
        panelMode == "history" -> "history"
        panelMode == "switch" -> "switch"
        panelMode == "memories" -> "memories"
        panelMode == "preferences" -> "preferences"
        viewModel.currentProvider != null && viewModel.apiKey != null -> "chat"
        // 兜底：API 未配置时显示设置引导页
        else -> "setup"
    }

    // 调试日志：追踪面板为何停在 loading 而不切换到实际内容
    android.util.Log.d("NionCompanion", "[Sidebar] actualMode=$actualMode, isInitialized=${viewModel.isInitialized}, isDataLoading=${viewModel.isDataLoading}, panelMode=$panelMode, provider=${viewModel.currentProvider?.name}")

    // 当从设置页保存配置后（currentProvider 从 null 变为非 null），自动回到聊天页
    LaunchedEffect(viewModel.currentProvider) {
        if (viewModel.currentProvider != null && panelMode == "setup") {
            panelMode = null
        }
    }

    // 头像点击回调：切换到编辑伙伴信息页面
    val onAvatarClick: () -> Unit = { panelMode = "profile" }

    // 是否有已保存的配置（用于 SetupContent 决定是否显示返回按钮）
    val hasConfigured = viewModel.currentProvider != null && viewModel.apiKey != null

    Surface(
        modifier = modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> onSidebarDrag(delta) },
            onDragStopped = { onSidebarDragStopped() },
        ),
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        /**
         * AnimatedContent 始终在组合树中（无条件守卫），通过 targetState 的
         * "loading" 状态处理异步初始化阶段。
         *
         * targetState 变化链：
         *   启动 → "loading" → isInitialized=true → "chat"/"setup"
         *
         * 所有面板模式（chat/setup/profile/history/switch/preferences/memories）
         * 之间用同一套 crossfade 动画过渡。loading 状态不参与动画。
         */
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = actualMode,
            transitionSpec = {
                // loading 进出不应用动画（duration=0），主面板/子面板之间才应用淡入淡出
                if (targetState == "loading" || initialState == "loading") {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else if (targetState != "chat" && targetState != "setup" && targetState != "profile") {
                    // 展开子面板（history/switch/memories/preferences）：子面板淡入(300ms)，主面板淡出(180ms)
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 回到主面板：主面板淡入(250ms)，子面板淡出(400ms)
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "panelMode",
        ) { mode ->
            when (mode) {
                /**
                 * 异步初始化未完成时的占位：透明的空白面板。
                 * isInitialized 变为 true 后，targetState 切换到真实模式，
                 * AnimatedContent 自身驱动内部重组，不依赖外层 DualPanelLayout。
                 */
                "loading" -> Box(modifier = Modifier.fillMaxSize())
                "history" -> HistoryPanel(
                    conversations = viewModel.conversationList,
                    currentConversationId = viewModel.currentConversationId,
                    onSelect = { id ->
                        viewModel.loadConversation(id)
                        panelMode = null
                    },
                    onDelete = { id -> viewModel.deleteConversation(id) },
                    onClose = { panelMode = null },
                )
                "switch" -> SwitchProviderPanel(
                    configs = viewModel.savedConfigs,
                    activeConfigId = viewModel.savedConfigs.find {
                        it.provider == viewModel.currentProvider?.name && it.model == viewModel.modelName
                    }?.id,
                    onSelect = { configId ->
                        viewModel.switchToConfig(configId)
                        panelMode = null
                    },
                    onDelete = { configId -> viewModel.deleteConfig(configId) },
                    onAddNew = { panelMode = "setup" },
                    onClose = { panelMode = null },
                )
                "profile" -> ProfileContent(
                    viewModel = viewModel,
                    onBack = { panelMode = null },
                    onMemoriesClick = { panelMode = "memories" },
                    onPreferencesClick = { panelMode = "preferences" },
                )
                "preferences" -> PreferencesPanel(
                    viewModel = viewModel,
                    onClose = { panelMode = "profile" },
                )
                "memories" -> MemoriesPanel(
                    viewModel = viewModel,
                    onClose = { panelMode = null },
                )
                "setup" -> SetupContent(
                    viewModel = viewModel,
                    onBack = if (hasConfigured) { { panelMode = "switch" } } else null,
                    onAvatarClick = onAvatarClick,
                )
                else -> ChatContent(
                    viewModel = viewModel,
                    isVisible = isVisible,
                    onAvatarClick = onAvatarClick,
                    onNewChat = {
                        viewModel.startNewConversation()
                    },
                    onHistoryClick = {
                        // 打开历史面板前刷新列表，确保显示最新的对话记录
                        // loadConversationList 是 suspend 函数，需要在协程中调用
                        viewModel.viewModelScope.launch {
                            viewModel.loadConversationList()
                        }
                        panelMode = "history"
                    },
                    onSwitchClick = { panelMode = "switch" },
                )
            }
        }
    }
}

/**
 * API 配置引导页内容 —— 嵌入在侧边栏内部的设置界面。
 *
 * @param viewModel 伙伴 ViewModel
 * @param onBack 可选的返回按钮回调（从切换面板进入时提供），null 时不显示返回按钮
 * @param onAvatarClick 点击头像时触发，切换到编辑伙伴信息页面
 */
@Composable
private fun SetupContent(
    viewModel: CompanionViewModel,
    onBack: (() -> Unit)? = null,
    onAvatarClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 标题行：返回按钮（可选） + 头像 + 名字
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮 —— 仅在从切换面板进入时显示
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            // 头像 + 名字，点击进入编辑页
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onAvatarClick() },
            ) {
                CompanionAvatar(
                    name = viewModel.companionName,
                    avatarUri = viewModel.companionAvatarUri,
                    size = 36.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    viewModel.companionName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // Provider 选择 + API Key 输入表单
        ApiProviderSetup(
            companionName = viewModel.companionName,
            onSave = { provider, apiKey, model, baseUrl ->
                viewModel.saveApiConfig(provider, apiKey, model, baseUrl)
            },
        )
    }
}

/**
 * 伙伴头像 —— 圆形头像。
 * 如果设置了头像图片则显示图片，否则显示伙伴名字的首字母作为占位符。
 *
 * @param name 伙伴名称，取首字母显示（无图片时使用）
 * @param avatarUri 头像图片的 URI 字符串，null 则使用首字母占位符
 * @param size 头像尺寸
 * @param modifier 修饰符（外部传入 clickable 等）
 */
@Composable
private fun CompanionAvatar(
    name: String,
    avatarUri: String? = null,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 从 URI 加载 Bitmap，avatarUri 变化时重新加载
    // 使用 inSampleSize=4 降采样，避免大图全尺寸解码导致内存暴涨和主线程阻塞
    val bitmap = remember(avatarUri) {
        if (avatarUri != null) {
            try {
                val uri = Uri.parse(avatarUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (_: Exception) { null }
        } else null
    }

    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (bitmap == null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        if (bitmap != null) {
            // 显示用户选择的头像图片
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 无图片时显示首字母占位符
            val initial = name.firstOrNull()?.uppercase() ?: "N"
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initial,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 伙伴资料编辑页面 —— 编辑名字、系统提示词、头像。
 *
 * @param viewModel 伙伴 ViewModel
 * @param onBack 点击返回按钮时触发，保存编辑内容并返回上一级
 * @param onMemoriesClick 点击记忆按钮时触发，打开记忆面板
 * @param onPreferencesClick 点击"关于你"按钮时触发，打开用户偏好面板
 */
@Composable
private fun ProfileContent(
    viewModel: CompanionViewModel,
    onBack: () -> Unit,
    onMemoriesClick: () -> Unit,
    onPreferencesClick: () -> Unit,
) {
    var name by remember { mutableStateOf(viewModel.companionName) }
    var prompt by remember { mutableStateOf(viewModel.companionPrompt) }

    // 系统图片选择器：选取头像图片
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // 尝试获取持久化读取权限，以便下次启动仍能加载
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {}
            viewModel.updateAvatarUri(uri.toString())
        }
    }

    // 返回并保存：将当前编辑的内容写回 ViewModel 后返回
    val backAndSave: () -> Unit = {
        viewModel.updateCompanionInfo(name.trim(), prompt.trim())
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 居中占位 + 右侧功能按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：返回按钮
            IconButton(onClick = backAndSave) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回并保存",
                    modifier = Modifier.size(20.dp),
                )
            }
            // 右侧按钮组：关于你 + 记忆
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "关于你"按钮：打开用户偏好面板，查看和编辑 Nion 了解的用户偏好
                IconButton(onClick = onPreferencesClick) {
                    Icon(
                        Icons.Outlined.Bookmarks,
                        contentDescription = "关于你",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 记忆按钮：打开 Nion 的笔记本，查看 AI 自动记录的关于用户的信息
                IconButton(onClick = onMemoriesClick) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = "记忆",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 头像区域 —— 居中展示，点击可更换头像
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompanionAvatar(
                name = name.ifEmpty { viewModel.companionName },
                avatarUri = viewModel.companionAvatarUri,
                size = 72.dp,
                modifier = Modifier.clickable { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "点击更换头像",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 名字输入框
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 系统提示词输入框 —— 多行，定义 AI 的角色和回复风格
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("系统提示词") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 单条偏好卡片 —— 显示分类标签 + 偏好内容 + 删除按钮。
 *
 * @param pref 偏好 JSON 对象，包含 id/content/category/created_at
 * @param onDelete 点击删除按钮时触发
 */
@Composable
private fun PreferenceItem(
    pref: org.json.JSONObject,
    onDelete: () -> Unit,
) {
    // 分类 → (中文标签, 颜色) 映射
    val categoryInfo = when (pref.optString("category", "other")) {
        "style" -> "风格" to Color(0xFF6750A4)
        "behavior" -> "行为" to Color(0xFF0061A4)
        "format" -> "格式" to Color(0xFF006E1C)
        else -> "其他" to Color(0xFF757575)
    }
    val (label, color) = categoryInfo

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类标签 —— 圆角小色块
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 偏好内容
            Text(
                pref.getString("content"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除偏好",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * 添加偏好的对话框 —— 输入偏好内容并选择分类。
 *
 * @param onDismiss 点击取消或外部区域时触发
 * @param onConfirm 确认添加时触发，传入 (content, category)
 */
@Composable
private fun AddPreferenceDialog(
    onDismiss: () -> Unit,
    onConfirm: (content: String, category: String) -> Unit,
) {
    // 偏好内容输入
    var content by remember { mutableStateOf("") }
    // 选中的分类，默认"行为"
    var selectedCategory by remember { mutableStateOf("behavior") }

    // 分类选项列表
    val categories = listOf(
        "style" to "风格",
        "behavior" to "行为",
        "format" to "格式",
        "other" to "其他",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加偏好") },
        text = {
            Column {
                // 偏好内容输入框
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("偏好内容") },
                    placeholder = { Text("例如：不要使用 emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 分类选择 —— 横向排列的单选标签
                Text(
                    "分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { (key, label) ->
                        val isSelected = selectedCategory == key
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { selectedCategory = key },
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (content.isNotBlank()) onConfirm(content.trim(), selectedCategory) },
                enabled = content.isNotBlank(),
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 聊天界面内容 —— 消息列表 + 输入框。
 *
 * @param viewModel 伙伴 ViewModel
 * @param isVisible 面板可见性，用于自动滚动
 * @param onAvatarClick 点击头像时触发，切换到编辑伙伴信息页面
 * @param onHistoryClick 点击历史图标时触发，切换到历史记录面板
 * @param onSwitchClick 点击切换图标时触发，切换到多配置选择面板
 */
@Composable
private fun ChatContent(
    viewModel: CompanionViewModel,
    isVisible: Boolean,
    onAvatarClick: () -> Unit,
    onNewChat: () -> Unit,
    onHistoryClick: () -> Unit,
    onSwitchClick: () -> Unit,
) {
    // 用 ViewModel 保存的滚动位置初始化 LazyListState
    // 当面板因打开左侧清单被移出组合再重新进入时，自动恢复到上次位置
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.savedScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedScrollOffset,
    )
    val messages = viewModel.messages
    val inputText = viewModel.inputText
    val isLoading = viewModel.isLoading
    val streamingText = viewModel.displayedStreamingText
    val streamingTimestamp = viewModel.streamingMessageTimestamp

    // 面板被移出组合时保存滚动位置（打开左侧清单、切换子面板等场景）
    // DisposableEffect.onDispose 在 composable 被移除时必定调用，比 LaunchedEffect 更可靠
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                messages.size,
            )
        }
    }

    // 面板打开时：如果面板关闭期间有新消息，滚到最底部；否则位置已由 rememberLazyListState 恢复
    LaunchedEffect(isVisible) {
        if (isVisible && messages.isNotEmpty()) {
            val hasNewMessages = messages.size > viewModel.lastSeenMessageCount
            if (hasNewMessages) {
                // 有新消息 → 滚到最后一个 item（底部 Spacer），确保看到最底部
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                listState.scrollToItem(lastIndex)
            }
        }
    }

    // 新消息到达时自动滚动到底部（仅面板打开时）
    // 只在 messages.size 变化时触发，不监听 streamingText（避免流式输出时和手指滑动打架）
    // 用户发消息或 AI 回复完成后，无条件滚到底部
    LaunchedEffect(messages.size) {
        if (messages.size > 0 && isVisible) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部标题栏：头像（点击编辑） + 伙伴名字 + 历史/切换按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：头像 + 名字 —— 点击任意一个进入编辑伙伴信息页面
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onAvatarClick() },
            ) {
                CompanionAvatar(
                    name = viewModel.companionName,
                    avatarUri = viewModel.companionAvatarUri,
                    size = 36.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    viewModel.companionName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // 右侧按钮组：新对话 + 历史记录 + 切换配置
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 新对话按钮：保存当前对话并开始新对话
                IconButton(onClick = onNewChat) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新对话",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 历史记录按钮：查看历史对话列表
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "历史记录",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 切换配置按钮：打开多配置选择面板
                IconButton(onClick = onSwitchClick) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "切换配置",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 消息列表 —— 为空时显示占位提示
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "和 ${viewModel.companionName} 说点什么吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                // 消息入场动画：淡入 + 缩放
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(300)) + scaleIn(
                        initialScale = 0.9f,
                        animationSpec = tween(350),
                    ),
                ) {
                    MessageBubble(message)
                }
            }
            // 流式输出气泡 —— 有流式文本时实时展示正在生成的 AI 回复
            if (!streamingText.isNullOrEmpty()) {
                item {
                    StreamingMessageBubble(
                        text = streamingText,
                        timestamp = streamingTimestamp,
                    )
                }
            }
            // 加载指示器：仅在等待 AI 回复且尚未收到任何文本时显示转圈
            else if (isLoading) {
                item {
                    // 工具执行时显示具体操作（如"正在创建任务..."），否则显示通用"正在思考"
                    val statusText = viewModel.toolExecutionStatus
                        ?: "${viewModel.companionName} 正在思考..."
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // 输入框容器 —— 无焦点时灰色，有焦点时切换为主题色
        val focusState = remember { mutableStateOf(false) }
        val isFocused = focusState.value
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(
                alpha = if (isFocused) 0.12f else 0f,
            ),
            border = androidx.compose.foundation.BorderStroke(
                2.dp,
                if (isFocused)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { viewModel.inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp)
                        .onFocusChanged { focusState.value = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 3,
                    // 启用状态跟随 isLoading：加载中时不可输入
                    enabled = !isLoading,
                    decorationBox = { innerTextField ->
                        // 占位符：输入为空时显示提示文字，文字和输入框叠加
                        Box(Modifier.fillMaxWidth()) {
                            if (inputText.isEmpty()) {
                                Text(
                                    "说点什么...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                // 发送按钮 —— 有内容时主题色高亮，无内容时灰色淡化
                IconButton(
                    onClick = { viewModel.sendMessage() },
                    enabled = inputText.isNotBlank() && !isLoading,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (inputText.isNotBlank() && !isLoading)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 单条消息气泡 —— 用户消息靠右（主题色），AI 消息靠左（灰色）。
 *
 * AI 消息使用 [MarkdownText] 渲染 Markdown 格式（粗体/斜体/代码/列表/标题等）。
 * 用户消息使用纯文本渲染。
 *
 * @param message 要渲染的消息数据
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.isFromUser
    // 工具消息：简洁状态行（小字、灰色、左侧图标），不渲染气泡
    if (message.isToolMessage) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message.toolDone) {
                // 已完成：显示右箭头
                Text(
                    "→",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            } else {
                // 执行中：显示加载指示器
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                message.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        return
    }

    // 用户消息右对齐，AI 消息左对齐
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Surface(
            // 气泡圆角：用户右下直角，AI 左下直角（模仿微信气泡风格）
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp,
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (!isUser) 1.dp else 0.dp,
        ) {
            val bubbleModifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            val textColor = if (isUser)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurface

            if (isUser) {
                // 用户消息：纯文本
                Text(
                    message.text,
                    modifier = bubbleModifier,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    ),
                    color = textColor,
                )
            } else {
                // AI 消息：使用 MarkdownText 渲染 Markdown 格式
                Log.d("MarkdownText", "MessageBubble AI msg(${message.text.take(50)}...) length=${message.text.length}")
                Box(modifier = bubbleModifier) {
                    MarkdownText(
                        content = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = textColor,
                        ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 时间戳
        Text(
            message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * 流式输出气泡 —— 实时展示正在生成的 AI 回复，显示时间戳和闪烁的光标感。
 *
 * 与 [MessageBubble] 的区别：
 * - 不使用入场动画（文本持续更新，动画会重复触发）
 * - 使用 [MarkdownText] 渲染已累积的流式文本
 * - 底部显示一个微弱的闪烁点，给用户"sending"的视觉反馈
 *
 * @param text      当前已累积的流式文本
 * @param timestamp 流式开始时间
 */
@Composable
private fun StreamingMessageBubble(
    text: String,
    timestamp: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 4.dp,
                bottomEnd = 20.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                MarkdownText(
                    content = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 时间戳 + 流式指示点
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            // 打字指示点：一个微小的圆点
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * 历史记录面板 —— 展示所有历史对话列表，点击恢复对话。
 *
 * @param conversations 对话列表（来自数据库，按更新时间倒序）
 * @param currentConversationId 当前活跃的对话 ID（用于高亮标记）
 * @param onSelect 点击某个对话时触发，传入对话 ID，用于恢复该对话
 * @param onDelete 删除某个对话时触发，传入对话 ID
 * @param onClose  关闭面板的回调
 */
@Composable
private fun HistoryPanel(
    conversations: List<ConversationData>,
    currentConversationId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部栏：图标 + 标题 + 关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "历史记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // 对话列表
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "暂无历史记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击聊天界面的 + 按钮开始新对话，\n当前对话会自动保存到历史记录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conversations, key = { it.id }) { conv ->
                    ConversationItem(
                        conversation = conv,
                        isActive = conv.id == currentConversationId,
                        onSelect = { onSelect(conv.id) },
                        onDelete = { onDelete(conv.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

/**
 * 对话列表中的单条对话项 —— 显示标题、时间和消息预览。
 */
@Composable
private fun ConversationItem(
    conversation: ConversationData,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 显示对话消息数量和更新时间的简短摘要
            val msgCount = try {
                JSONArray(conversation.messages).length()
            } catch (_: Exception) { 0 }
            Text(
                "$msgCount 条消息 · ${conversation.updatedAt.take(10)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前对话",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除对话",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * 多配置切换面板 —— 展示已保存的所有 Provider 配置，风格对齐设置页。
 *
 * @param configs        所有已保存的配置
 * @param activeConfigId 当前激活的配置 ID（用于高亮），null 表示无激活配置
 * @param onSelect       选择某个配置时触发，传入该配置的 ID
 * @param onDelete       删除某个配置时触发，传入该配置的 ID
 * @param onAddNew       点击"新增配置"时触发
 * @param onClose        关闭面板时触发
 */
@Composable
private fun SwitchProviderPanel(
    configs: List<SavedConfig>,
    activeConfigId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddNew: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部栏：图标 + 标题 + 关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "切换配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // 配置列表
        if (configs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "暂无已保存配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(configs, key = { it.id }) { config ->
                    ConfigItem(
                        config = config,
                        isActive = config.id == activeConfigId,
                        onSelect = { onSelect(config.id) },
                        onDelete = { onDelete(config.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // 底部新增按钮 —— 全宽，分割线上方
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddNew() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "新增配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 单条配置项 —— 显示 Provider 名称 + 模型名，激活状态有对勾标识。
 */
@Composable
private fun ConfigItem(
    config: SavedConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                config.provider,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                config.model,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前使用",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除配置",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * 用户偏好面板（"关于你"）—— 展示和管理用户的所有偏好。
 * 支持按分类筛选、手动添加偏好、删除偏好。
 *
 * @param viewModel 伙伴 ViewModel
 * @param onClose 点击返回按钮时触发，返回伙伴资料编辑页面
 */
@Composable
private fun PreferencesPanel(
    viewModel: CompanionViewModel,
    onClose: () -> Unit,
) {
    // 当前筛选的分类，null 表示显示全部
    var filterCategory by remember { mutableStateOf<String?>(null) }
    // 添加偏好对话框的状态：false 表示隐藏
    var showAddPrefDialog by remember { mutableStateOf(false) }

    // 偏好分类选项列表
    val categories = listOf(
        "style" to "风格",
        "behavior" to "行为",
        "format" to "格式",
        "other" to "其他",
    )

    // 偏好数量统计
    val prefs = viewModel.userPreferences
    val totalCount = prefs.length()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 标题 + 添加按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 标题 + 偏好数量副标题
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "关于你",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (totalCount == 0) "暂无偏好" else "共 $totalCount 条偏好",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            // 右上角添加偏好按钮
            IconButton(onClick = { showAddPrefDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "添加偏好",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 说明文本
        Text(
            "AI 会自动记住你表达的偏好，你也可以手动添加",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 分类筛选标签 —— 横向可滚动的分类标签行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "全部"标签
            val isAll = filterCategory == null
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isAll) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { filterCategory = null },
            ) {
                Text(
                    "全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAll) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // 各分类标签 —— 仅显示有偏好的分类
            categories.forEach { (key, label) ->
                val count = countPreferencesByCategory(prefs, key)
                if (count > 0) {
                    val isSelected = filterCategory == key
                    // 分类 → 颜色映射：风格=紫，行为=蓝，格式=绿，其他=灰
                    val color = when (key) {
                        "style" -> Color(0xFF6750A4)
                        "behavior" -> Color(0xFF0061A4)
                        "format" -> Color(0xFF006E1C)
                        else -> Color(0xFF757575)
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) color
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { filterCategory = key },
                    ) {
                        Text(
                            "$label ($count)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 偏好列表
        if (totalCount == 0) {
            // 空状态：居中提示，引导用户与 AI 对话产生偏好
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "还没有任何偏好",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "和 ${viewModel.companionName} 聊天时，AI 会自动记住你的偏好",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        } else {
            // 按筛选条件过滤后的偏好列表
            val filteredPrefs = filterPreferences(prefs, filterCategory)
            if (filteredPrefs.isEmpty()) {
                // 该分类下无偏好
                Text(
                    "该分类下暂无偏好",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredPrefs.size) { index ->
                        val pref = filteredPrefs[index]
                        PreferenceItem(
                            pref = pref,
                            onDelete = { viewModel.removePreference(pref.getString("id")) },
                        )
                    }
                }
            }
        }
    }

    // 添加偏好的弹窗
    if (showAddPrefDialog) {
        AddPreferenceDialog(
            onDismiss = { showAddPrefDialog = false },
            onConfirm = { content, category ->
                viewModel.addPreference(content, category)
                showAddPrefDialog = false
            },
        )
    }
}

/**
 * 记忆管理面板 —— 展示 Nion 记住的关于用户的所有事实性信息。
 *
 * 以分类标签 + 内容列表的形式展示记忆，支持按分类筛选和逐条删除。
 * 记忆由 AI 在对话中主动调用 memory 工具记录，用户可在此查看和管理。
 *
 * @param viewModel 伙伴 ViewModel，提供 userMemories 数据和 removeMemory 方法
 * @param onClose 关闭面板的回调，返回聊天界面
 */
@Composable
private fun MemoriesPanel(
    viewModel: CompanionViewModel,
    onClose: () -> Unit,
) {
    // 当前筛选的分类，null 表示显示全部
    var filterCategory by remember { mutableStateOf<String?>(null) }

    // 所有有效的记忆分类列表，用于生成筛选标签
    val allCategories = MemoryTool.categoryLabels

    // 记忆数量统计文本
    val memories = viewModel.userMemories
    val totalCount = memories.length()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "${viewModel.companionName}的笔记本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (totalCount == 0) "暂无记忆" else "共 $totalCount 条记忆",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 说明文本
        Text(
            "AI 会在对话中主动记录关于你的信息，你也可以在这里管理这些记忆",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 分类筛选标签 —— 横向可滚动的分类标签行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "全部"标签
            val isAll = filterCategory == null
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isAll) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { filterCategory = null },
            ) {
                Text(
                    "全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAll) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // 各分类标签 —— 仅显示有记忆的分类
            allCategories.forEach { (key, label) ->
                val count = countMemoriesByCategory(memories, key)
                if (count > 0) {
                    val isSelected = filterCategory == key
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MemoryItemColors[key] ?: MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { filterCategory = key },
                    ) {
                        Text(
                            "$label ($count)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 记忆列表
        if (totalCount == 0) {
            // 空状态：居中提示
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "还没有任何记忆",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "和 ${viewModel.companionName} 聊天时，AI 会自动记住关于你的信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        } else {
            // 按筛选条件过滤后的记忆列表
            val filteredMemories = filterMemories(memories, filterCategory)
            if (filteredMemories.isEmpty()) {
                // 该分类下无记忆
                Text(
                    "该分类下暂无记忆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredMemories.size) { index ->
                        val mem = filteredMemories[index]
                        MemoryItem(
                            mem = mem,
                            onDelete = { viewModel.removeMemory(mem.getString("id")) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 记忆分类 → 显示颜色的映射，用于标签和卡片的视觉区分。
 */
private val MemoryItemColors = mapOf(
    "identity" to Color(0xFF6750A4),
    "study" to Color(0xFF1565C0),
    "work" to Color(0xFF2E7D32),
    "hobby" to Color(0xFFE65100),
    "habit" to Color(0xFF6A1B9A),
    "health" to Color(0xFFC62828),
    "emotion" to Color(0xFFAD1457),
    "goal" to Color(0xFF00838F),
    "schedule" to Color(0xFF4527A0),
    "social" to Color(0xFF1B5E20),
    "location" to Color(0xFF37474F),
    "pet" to Color(0xFF4E342E),
    "context" to Color(0xFFEF6C00),
    "other" to Color(0xFF757575),
)

/**
 * 单条记忆卡片 —— 显示分类标签 + 记忆内容 + 删除按钮。
 *
 * @param mem 记忆 JSON 对象，包含 id/content/category/created_at/updated_at/expires_hint?
 * @param onDelete 点击删除按钮时触发
 */
@Composable
private fun MemoryItem(
    mem: org.json.JSONObject,
    onDelete: () -> Unit,
) {
    val category = mem.optString("category", "other")
    val label = MemoryTool.categoryLabels[category] ?: "其他"
    val color = MemoryItemColors[category] ?: Color(0xFF757575)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类标签 —— 圆角小色块
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 记忆内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mem.getString("content"),
                    style = MaterialTheme.typography.bodySmall,
                )
                // 如果有过期提示，显示日期
                val expiresHint = mem.optString("expires_hint", "")
                if (expiresHint.isNotEmpty()) {
                    Text(
                        "预期至 $expiresHint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除记忆",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * 统计指定分类下的记忆数量。
 *
 * @param memories 记忆 JSONArray
 * @param category 要统计的分类键
 * @return 该分类下的记忆条数
 */
private fun countMemoriesByCategory(memories: JSONArray, category: String): Int {
    var count = 0
    for (i in 0 until memories.length()) {
        if (memories.getJSONObject(i).optString("category", "other") == category) {
            count++
        }
    }
    return count
}

/**
 * 按分类筛选记忆列表，返回过滤后的列表。
 *
 * @param memories 完整的记忆 JSONArray
 * @param category 要筛选的分类键，null 表示返回全部
 * @return 过滤后的 JSONObject 列表
 */
private fun filterMemories(memories: JSONArray, category: String?): List<org.json.JSONObject> {
    val result = mutableListOf<org.json.JSONObject>()
    for (i in 0 until memories.length()) {
        val mem = memories.getJSONObject(i)
        if (category == null || mem.optString("category", "other") == category) {
            result.add(mem)
        }
    }
    return result
}

/**
 * 统计指定分类下的偏好数量。
 *
 * @param prefs 偏好 JSONArray
 * @param category 要统计的分类键
 * @return 该分类下的偏好条数
 */
private fun countPreferencesByCategory(prefs: JSONArray, category: String): Int {
    var count = 0
    for (i in 0 until prefs.length()) {
        if (prefs.getJSONObject(i).optString("category", "other") == category) {
            count++
        }
    }
    return count
}

/**
 * 按分类筛选偏好列表，返回过滤后的列表。
 *
 * @param prefs 完整的偏好 JSONArray
 * @param category 要筛选的分类键，null 表示返回全部
 * @return 过滤后的 JSONObject 列表
 */
private fun filterPreferences(prefs: JSONArray, category: String?): List<org.json.JSONObject> {
    val result = mutableListOf<org.json.JSONObject>()
    for (i in 0 until prefs.length()) {
        val pref = prefs.getJSONObject(i)
        if (category == null || pref.optString("category", "other") == category) {
            result.add(pref)
        }
    }
    return result
}
