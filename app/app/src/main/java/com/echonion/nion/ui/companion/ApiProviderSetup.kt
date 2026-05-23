package com.echonion.nion.ui.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * API 配置引导页 —— 当用户尚未配置 API key 时显示。
 *
 * 配置流程：
 * 1. 从预置列表中选择 Provider（OpenAI / Anthropic / DeepSeek / 自定义）
 * 2. 输入 API Key，点击"获取模型列表"从 API 拉取可用模型
 * 3. 从下拉列表中选择模型（自定义 provider 可手动输入）
 * 4. 点击"开始对话"保存配置
 *
 * @param onSave 保存按钮回调，传入完整配置信息（含用户选中的模型名）
 */
@Composable
fun ApiProviderSetup(
    companionName: String = "Nion",
    onSave: (provider: ProviderConfig, apiKey: String, model: String, baseUrl: String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // provider 下拉菜单展开状态
    var dropdownExpanded by remember { mutableStateOf(false) }
    // 当前选中的 provider 索引，默认选中第一个（OpenAI）
    var selectedProviderIndex by remember { mutableStateOf(0) }
    // API key 输入值
    var apiKeyInput by remember { mutableStateOf("") }
    // API key 是否明文显示
    var apiKeyVisible by remember { mutableStateOf(false) }

    // 自定义 baseUrl（仅"自定义"provider 时使用）
    var customBaseUrl by remember { mutableStateOf("") }

    // 模型获取相关状态
    var isFetchingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    // 模型下拉菜单展开状态（从获取到的列表中选取）
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    // 用户选中的模型名（从获取列表中选择，或自定义时手动输入）
    var selectedModelName by remember { mutableStateOf("") }
    // 自定义 provider 时手动输入模型名
    var customModelInput by remember { mutableStateOf("") }

    val selectedProvider = builtInProviders[selectedProviderIndex]
    val isCustom = selectedProvider.name == "自定义"

    // 是否已获取到模型列表
    val hasModels = availableModels.isNotEmpty()

    /**
     * 调用 ChatService.fetchModels 获取当前 provider 的可用模型列表。
     */
    fun fetchModels() {
        if (isCustom) return // 自定义 provider 不获取模型
        val key = apiKeyInput.trim()
        if (key.isEmpty() || isFetchingModels) return
        isFetchingModels = true
        fetchError = null
        scope.launch {
            val result = ChatService.fetchModels(selectedProvider, key)
            result.onSuccess { models ->
                availableModels = models
            }.onFailure { e ->
                fetchError = e.message ?: "获取模型失败"
            }
            isFetchingModels = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 标题区
        Text(
            "欢迎使用 $companionName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "选择一个 AI Provider 并输入 API Key，然后获取可用模型即可开始对话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ---- 第 1 步：选择 Provider ----
        Text(
            "1. 选择 Provider",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 展开式圆角卡片选择器 —— 替代丑陋的 DropdownMenu
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)), // clip 裁剪动画溢出，保持圆角
            ) {
                // 头部：展示当前选中的 provider，点击展开/收起
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dropdownExpanded = !dropdownExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selectedProvider.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (selectedProvider.name != "自定义") {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (selectedProvider.apiType) {
                                    ApiType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
                                    ApiType.ANTHROPIC -> "Anthropic"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Icon(
                        if (dropdownExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (dropdownExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 展开区域：所有 provider 选项，带动画
                AnimatedVisibility(
                    visible = dropdownExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        builtInProviders.forEachIndexed { index, provider ->
                            val isSelected = index == selectedProviderIndex
                            // 每个 provider 选项行 —— 选中项以主题色高亮 + 加粗 + 勾号
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProviderIndex = index
                                        dropdownExpanded = false
                                        // 切换 provider 时重置模型列表
                                        availableModels = emptyList()
                                        selectedModelName = ""
                                        fetchError = null
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (provider.name != "自定义") {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            when (provider.apiType) {
                                                ApiType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
                                                ApiType.ANTHROPIC -> "Anthropic"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // 选中标记：主题色勾号
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "已选中",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 自定义 provider 时：显示 baseUrl 输入框 ----
        if (isCustom) {
            OutlinedTextField(
                value = customBaseUrl,
                onValueChange = { customBaseUrl = it },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.example.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }

        // ---- 第 2 步：输入 API Key ----
        Text(
            "2. 输入 API Key",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = {
                apiKeyInput = it
                // API key 变动时重置模型列表（安全起见）
                if (availableModels.isNotEmpty()) {
                    availableModels = emptyList()
                    selectedModelName = ""
                    fetchError = null
                }
            },
            label = { Text("API Key") },
            placeholder = { Text("sk-...（OpenAI）或 sk-ant-...（Anthropic）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // 密码模式：默认隐藏，点击眼睛图标切换明文
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (apiKeyVisible) "隐藏密钥" else "显示密钥",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(),
        )

        // ---- 第 3 步：获取并选择模型 ----
        // 标题行：左侧"3. 选择模型"，右侧放置获取/重试图标（不占额外空间）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "3. 选择模型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isCustom) {
                // 获取/重试图标：加载中转圈，否则显示刷新图标
                if (isFetchingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = { fetchModels() },
                        enabled = apiKeyInput.isNotBlank(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "获取模型列表",
                            modifier = Modifier.size(18.dp),
                            tint = if (apiKeyInput.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }

        // 获取失败时显示错误信息
        if (fetchError != null) {
            Text(
                fetchError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (!isCustom) {
            // 模型选择器 —— 与 provider 选择器同款的展开式圆角卡片
            if (hasModels) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        // 头部：当前选中的模型名 + 展开/收起箭头
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { modelDropdownExpanded = !modelDropdownExpanded }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedModelName.ifEmpty { "请选择一个模型" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedModelName.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                                color = if (selectedModelName.isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                if (modelDropdownExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (modelDropdownExpanded) "收起" else "展开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 展开区域：所有可用模型，带动画
                        AnimatedVisibility(
                            visible = modelDropdownExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                availableModels.forEach { model ->
                                    val isModelSelected = model == selectedModelName
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedModelName = model
                                                modelDropdownExpanded = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            model,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isModelSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isModelSelected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (isModelSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "已选中",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 自定义 provider：手动输入模型名
            OutlinedTextField(
                value = customModelInput,
                onValueChange = {
                    customModelInput = it
                    selectedModelName = it
                },
                label = { Text("模型名称") },
                placeholder = { Text("例如 gpt-4o, claude-sonnet-4-20250514") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 保存按钮 ----
        val isModelSelected = selectedModelName.isNotBlank()
        // 保存条件：API key 非空 + （自定义需填写 baseUrl）+ 已选择模型
        val canSave = apiKeyInput.isNotBlank() &&
            (!isCustom || customBaseUrl.isNotBlank()) &&
            isModelSelected

        Button(
            onClick = {
                focusManager.clearFocus()
                onSave(
                    selectedProvider,
                    apiKeyInput.trim(),
                    if (isCustom) customModelInput.trim() else selectedModelName,
                    if (isCustom) customBaseUrl.trim() else "",
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Text(
                "开始对话",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
