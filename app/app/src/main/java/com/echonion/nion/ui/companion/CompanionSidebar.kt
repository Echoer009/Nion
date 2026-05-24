package com.echonion.nion.ui.companion

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import android.graphics.BitmapFactory
import android.net.Uri

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
    // 初始化完成后才决定显示哪个页面，避免启动闪烁
    if (!viewModel.isInitialized) {
        // 初始化中显示空白，实际只是瞬间
        return
    }

    // 面板当前模式：null=自动根据 API 配置决定，其余为手动切换的面板
    var panelMode by remember { mutableStateOf<String?>(null) }

    // 根据 API 配置和模式决定显示哪个界面
    val actualMode = when {
        panelMode == "profile" -> "profile"
        panelMode == "setup" -> "setup"
        panelMode == "history" -> "history"
        panelMode == "switch" -> "switch"
        viewModel.currentProvider != null && viewModel.apiKey != null -> "chat"
        else -> "setup"
    }

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
        when (actualMode) {
            "profile" -> ProfileContent(
                viewModel = viewModel,
                onBack = { panelMode = null },
            )
            "setup" -> SetupContent(
                viewModel = viewModel,
                onBack = if (hasConfigured) { { panelMode = null } } else null,
                onAvatarClick = onAvatarClick,
            )
            "history" -> HistoryPanel(
                messages = viewModel.loadArchivedChat(),
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
            else -> ChatContent(
                viewModel = viewModel,
                isVisible = isVisible,
                onAvatarClick = onAvatarClick,
                onHistoryClick = { panelMode = "history" },
                onSwitchClick = { panelMode = "switch" },
            )
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
    val bitmap = remember(avatarUri) {
        if (avatarUri != null) {
            try {
                val uri = Uri.parse(avatarUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
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
 * 伙伴信息编辑页 —— 在侧边栏内全屏展示，替代聊天/设置界面。
 * 返回即自动保存：名字和提示词的修改在点击返回时持久化。
 *
 * @param viewModel 伙伴 ViewModel
 * @param onBack 返回上一页的回调，调用前会自动保存当前编辑内容
 */
@Composable
private fun ProfileContent(
    viewModel: CompanionViewModel,
    onBack: () -> Unit,
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
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = backAndSave) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "返回并保存",
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "编辑伙伴信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            // 右侧占位 Spacer 保持标题视觉居中
            Spacer(modifier = Modifier.size(40.dp))
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
            supportingText = {
                Text(
                    "定义 AI 伴侣的回复风格和语气。",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
    }
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
    onHistoryClick: () -> Unit,
    onSwitchClick: () -> Unit,
) {
    val listState = rememberLazyListState()
    val messages = viewModel.messages
    val inputText = viewModel.inputText
    val isLoading = viewModel.isLoading
    val streamingText = viewModel.streamingAssistantText
    val streamingTimestamp = viewModel.streamingMessageTimestamp

    // 面板打开时自动滚动到底部（最新消息）
    LaunchedEffect(isVisible, messages.size, streamingText) {
        if (isVisible) {
            // 有流式文本时，item 数量 = 消息数 + 1（流式气泡），滚动到那个位置
            val targetIndex = if (!streamingText.isNullOrEmpty()) messages.size + 1 else messages.size
            if (targetIndex > 0) {
                listState.animateScrollToItem(targetIndex)
            }
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
            // 右侧按钮组：历史记录 + 切换配置
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 历史记录按钮：查看归档的聊天记录
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
                            "${viewModel.companionName} 正在思考...",
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
 * 历史记录面板 —— 展示上次归档的聊天记录（只读），风格对齐设置页。
 *
 * @param messages 归档的消息列表
 * @param onClose  关闭面板的回调
 */
@Composable
private fun HistoryPanel(
    messages: List<ChatMessage>,
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

        // 消息列表（只读）
        if (messages.isEmpty()) {
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
                        "切换 API 配置时，当前聊天记录会自动归档到这里。",
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    HistoryMessageItem(message)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

/**
 * 历史记录中的单条消息项 —— 简化版气泡，只读不可交互。
 */
@Composable
private fun HistoryMessageItem(message: ChatMessage) {
    val isUser = message.isFromUser
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 3.dp,
                        bottomEnd = if (isUser) 3.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.text.take(200) + if (message.text.length > 200) "…" else "",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 2.dp),
        )
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
