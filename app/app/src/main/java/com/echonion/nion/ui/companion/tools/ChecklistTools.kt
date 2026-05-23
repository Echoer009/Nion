package com.echonion.nion.ui.companion.tools

import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 获取所有清单工具 —— 返回用户创建的所有清单。
 *
 * Agent 场景：用户问"我有哪些清单？"或创建任务时需要选择清单时调用。
 */
object GetChecklistsTool : Tool {
    override val name = "get_checklists"
    override val description = "获取所有清单（分类）列表。每个清单包含 id、名称和创建时间。"

    private val schema = """
    {
        "type": "object",
        "properties": {},
        "required": []
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val checklists = core.getChecklists()
        return JSONObject().apply {
            put("checklists", checklistListToJson(checklists))
            put("count", checklists.size)
        }.toString()
    }
}

/**
 * 创建清单工具 —— 创建一个新的任务清单。
 *
 * Agent 场景：用户说"帮我建一个学习清单"时调用。
 */
object CreateChecklistTool : Tool {
    override val name = "create_checklist"
    override val description = "创建一个新的任务清单（分类）。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "name": {
                "type": "string",
                "description": "清单名称"
            }
        },
        "required": ["name"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val name = params.getString("name")
        val checklist = core.createChecklist(name)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }
}

/**
 * 修改清单名称工具 —— 更新已有清单的名称。
 *
 * Agent 场景：用户说"把'学习'清单改名为'读书'"时调用。
 */
object UpdateChecklistNameTool : Tool {
    override val name = "update_checklist_name"
    override val description = "修改清单的名称。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "清单 ID"
            },
            "name": {
                "type": "string",
                "description": "新名称"
            }
        },
        "required": ["id", "name"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val name = params.getString("name")
        val checklist = core.updateChecklistName(id, name)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }
}

/**
 * 删除清单工具 —— 永久删除一个清单。
 *
 * Agent 场景：用户说"删掉这个清单"时调用。
 * 注意：删除清单不会自动删除其中的任务。
 */
object DeleteChecklistTool : Tool {
    override val name = "delete_checklist"
    override val description = "永久删除一个清单。此操作不可撤销。清单中的任务不会被删除，但会变为未分类状态。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "要删除的清单 ID"
            }
        },
        "required": ["id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val deleted = core.deleteChecklist(id)
        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
        }.toString()
    }
}
