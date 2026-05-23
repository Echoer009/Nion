package com.echonion.nion.ui.companion

import org.json.JSONObject

/**
 * LLM API 响应模型 —— 封装 LLM 返回的完整响应。
 *
 * LLM 可能返回两种内容：
 * - **纯文本回复**：[text] 不为 null，直接展示给用户
 * - **工具调用请求**：[toolCalls] 不为 null，需要执行工具后继续对话
 *
 * 两种情况互斥：有 toolCalls 时 text 通常为 null，反之亦然。
 * 但某些 LLM（如 Anthropic）可能同时返回文本和工具调用。
 *
 * @property text       LLM 的纯文本回复，无文本回复时为 null
 * @property toolCalls  LLM 请求调用的工具列表，无工具调用时为 null
 * @property rawMessage 原始的 assistant message JSON 对象。
 *                      某些 LLM（如 DeepSeek 推理模型）会在响应中包含 reasoning_content 等额外字段，
 *                      后续请求必须原样回传这些字段，否则 API 返回 400 错误。
 */
data class ChatResponse(
    val text: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val rawMessage: JSONObject? = null,
)

/**
 * 单个工具调用请求 —— LLM 返回的一个 function call。
 *
 * 包含 LLM 分配的调用 ID、要执行的工具名称和 JSON 格式的参数。
 * 执行完工具后，需要将结果与此 ID 关联回传给 LLM。
 *
 * @property id        工具调用的唯一标识符（如 "call_abc123" 或 "toolu_xyz789"）
 * @property name      要调用的工具名称（如 "create_task"）
 * @property arguments 工具参数的 JSON 字符串（如 `{"title":"买菜","priority":"high"}`）
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
