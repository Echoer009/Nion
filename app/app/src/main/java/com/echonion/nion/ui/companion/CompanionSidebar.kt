package com.echonion.nion.ui.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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

    // 面板当前模式：null=自动根据 API 配置决定，"profile"=编辑伙伴信息页面
    var panelMode by remember { mutableStateOf<String?>(null) }

    // 根据 API 配置和模式决定显示哪个界面
    val actualMode = when {
        panelMode == "profile" -> "profile"
        viewModel.currentProvider != null && viewModel.apiKey != null -> "chat"
        else -> "setup"
    }

    // 头像点击回调：切换到编辑伙伴信息页面
    val onAvatarClick: () -> Unit = { panelMode = "profile" }

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
            "chat" -> ChatContent(
                viewModel = viewModel,
                isVisible = isVisible,
                onAvatarClick = onAvatarClick,
            )
            else -> SetupContent(
                viewModel = viewModel,
                onAvatarClick = onAvatarClick,
            )
        }
    }
}

/**
 * API 配置引导页内容 —— 嵌入在侧边栏内部的设置界面。
 *
 * @param viewModel 伙伴 ViewModel
 * @param onAvatarClick 点击头像时触发，切换到编辑伙伴信息页面
 */
@Composable
private fun SetupContent(
    viewModel: CompanionViewModel,
    onAvatarClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 设置页标题行：头像 + 名字，点击任意一个进入编辑页
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
 */
@Composable
private fun ChatContent(
    viewModel: CompanionViewModel,
    isVisible: Boolean,
    onAvatarClick: () -> Unit,
) {
    val listState = rememberLazyListState()
    val messages = viewModel.messages
    val inputText = viewModel.inputText
    val isLoading = viewModel.isLoading

    // 面板打开时自动滚动到底部（最新消息）
    LaunchedEffect(isVisible, messages.size) {
        if (isVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size) // size = 最后一个 item 的索引 + 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部标题栏：头像（点击编辑） + 伙伴名字 + 切换/重置按钮
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
            // 切换配置按钮
            TextButton(onClick = { viewModel.clearApiConfig() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "切换配置",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                // 消息入场动画：淡入 + 从下方滑入
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(300)) + slideInVertically(
                        initialOffsetY = { it / 4 },
                        animationSpec = tween(350),
                    ),
                ) {
                    MessageBubble(message)
                }
            }
            // 加载指示器：等待 AI 回复时显示转圈
            if (isLoading) {
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
            Text(
                message.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                ),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurface,
            )
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
