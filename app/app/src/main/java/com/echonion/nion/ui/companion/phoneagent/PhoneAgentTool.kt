package com.echonion.nion.ui.companion.phoneagent

import android.util.Log
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.tools.DataType
import com.echonion.nion.ui.companion.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * Phone Agent 工具 —— 让主 AI 能够调度 Phone Agent 子循环来操控手机。
 *
 * 实现了 [Tool] 接口，注册到 [ToolRegistry] 后主 AI 可通过标准 function calling 调用。
 *
 * 主 AI → tool call → PhoneAgentTool.execute() → PhoneAgentLoop.run() → 返回结果
 *
 * 参数：task（字符串）—— 在手机上需要完成的任务描述
 */
object PhoneAgentTool : Tool {

    private const val TAG = "PhoneAgentTool"

    override val name = "phone_agent"

    override val description = """
使用手机操控助手来完成需要在其他App中执行的任务。
适用场景：用户需要打开某个App、发送消息、搜索内容、下单购物等需要操控手机界面的操作。
参数 task 应该是一个明确、具体的任务描述，例如"打开微信给张三发消息说今晚一起吃饭"。
注意：此工具会实际操控用户的手机，每个任务可能需要10-60秒完成。
    """.trimIndent()

    /** Phone Agent 不直接影响 Nion 内部数据，返回空集合 */
    override val affectsData: Set<DataType> = emptySet()

    override fun parametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("task", JSONObject().apply {
                    put("type", "string")
                    put("description", "需要在手机上完成的任务描述，应具体明确")
                })
            })
            put("required", org.json.JSONArray().apply {
                put("task")
            })
        }
    }

    override suspend fun execute(params: JSONObject, core: NionCore): String = withContext(Dispatchers.IO) {
        val task = params.optString("task", "").trim()
        if (task.isEmpty()) {
            return@withContext """{"error":"task 参数不能为空"}"""
        }

        val app = NionApp.instance
        if (app == null) {
            return@withContext """{"error":"应用状态异常"}"""
        }

        // 检查无障碍服务
        if (!PhoneAgentBridge.isAccessibilityServiceEnabled(app)) {
            return@withContext """{"error":"Phone Agent 无障碍服务未开启，请在系统设置→无障碍中开启 Nion Phone Agent"}"""
        }

        if (!PhoneAgentBridge.isServiceRunning()) {
            return@withContext """{"error":"Phone Agent 服务未运行，请确认无障碍服务已开启"}"""
        }

        // 读取 Phone Agent API 配置
        val baseUrl = readSetting(core, "phone_agent_base_url")
            ?: "https://open.bigmodel.cn/api/paas/v4"
        val apiKey = readSetting(core, "phone_agent_api_key")
            ?: return@withContext """{"error":"未配置 Phone Agent API Key，请在设置中配置"}"""
        val model = readSetting(core, "phone_agent_model") ?: "autoglm-phone"

        val client = AutoGLMClient(baseUrl = baseUrl, apiKey = apiKey, model = model)

        val stepDetails = mutableListOf<String>()
        val loop = PhoneAgentLoop(
            client = client,
            onStep = { step ->
                stepDetails.add(
                    "[第${step.stepNumber}步] ${step.action} | " +
                    if (step.success) "成功" else "失败"
                )
            }
        )

        val result = loop.run(task)
        Log.d(TAG, "Phone Agent 完成: success=${result.success}, steps=${result.totalSteps}, msg=${result.message}")

        JSONObject().apply {
            put("success", result.success)
            put("message", result.message)
            put("total_steps", result.totalSteps)
            put("steps", org.json.JSONArray().apply {
                for (detail in stepDetails) {
                    put(detail)
                }
            })
        }.toString()
    }

    /**
     * 从 NionCore settings 表中读取字符串配置。
     *
     * getSetting 返回 JSON 格式 {key, value}，从中提取 value 字段。
     */
    private fun readSetting(core: NionCore, key: String): String? {
        return try {
            val value = core.getSetting(key)
            if (value.isNullOrBlank()) null else value
        } catch (_: Exception) {
            null
        }
    }
}
