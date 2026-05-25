package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.GroupData
import uniffi.nion_core.NionCore

/**
 * 将 [GroupData] 序列化为 JSON 对象。
 *
 * @param group UniFFI 生成的分组数据记录
 * @return 包含完整分组信息的 JSON 对象
 */
fun groupToJson(group: GroupData): JSONObject = JSONObject().apply {
    put("id", group.id)
    put("name", group.name)
    put("checklist_id", group.checklistId)
    put("color", group.color ?: JSONObject.NULL)
    put("sort_order", group.sortOrder)
    put("created_at", group.createdAt)
}

/**
 * 将分组列表序列化为 JSON 数组。
 *
 * @param groups 分组数据列表
 * @return JSON 数组
 */
fun groupListToJson(groups: List<GroupData>): JSONArray = JSONArray().apply {
    for (g in groups) {
        put(groupToJson(g))
    }
}

/**
 * 获取清单下所有分组工具 —— 返回指定清单下的分组列表。
 *
 * Agent 场景：用户问"学习清单下有哪些分组？"时调用。
 */
object GetGroupsTool : Tool {
    override val name = "get_groups"
    override val description = "获取指定清单下的所有分组。分组是清单下的二级分类，例如\"语文\"、\"英语\"。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "checklist_id": {
                "type": "string",
                "description": "清单 ID"
            }
        },
        "required": ["checklist_id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val checklistId = params.getString("checklist_id")
        val groups = core.getGroupsByChecklist(checklistId)
        return JSONObject().apply {
            put("groups", groupListToJson(groups))
            put("count", groups.size)
        }.toString()
    }
}

/**
 * 创建分组工具 —— 在指定清单下新建一个分组。
 *
 * Agent 场景：用户说"帮我在学习清单下建一个语文分组"时调用。
 */
object CreateGroupTool : Tool {
    override val name = "create_group"
    override val description = "在指定清单下创建一个分组。分组是清单下的二级分类，例如\"语文\"、\"英语\"。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "name": {
                "type": "string",
                "description": "分组名称"
            },
            "checklist_id": {
                "type": "string",
                "description": "所属清单 ID"
            },
            "color": {
                "type": "string",
                "description": "分组颜色，十六进制格式如 #FF5722（可选）"
            }
        },
        "required": ["name", "checklist_id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val name = params.getString("name")
        val checklistId = params.getString("checklist_id")
        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
        val group = core.createGroup(name, checklistId, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 更新分组工具 —— 修改分组名称和颜色。
 *
 * Agent 场景：用户说"把语文分组改名为语文必修"时调用。
 */
object UpdateGroupTool : Tool {
    override val name = "update_group"
    override val description = "更新分组的名称和颜色。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "分组 ID"
            },
            "name": {
                "type": "string",
                "description": "新名称"
            },
            "color": {
                "type": "string",
                "description": "新颜色，十六进制格式如 #4CAF50（可选）"
            }
        },
        "required": ["id", "name"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val name = params.getString("name")
        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
        val group = core.updateGroup(id, name, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 删除分组工具 —— 删除分组但保留组内任务。
 *
 * Agent 场景：用户说"删掉语文分组"时调用。
 * 删除后组内任务的 group_id 会被置空，任务移至未分组。
 */
object DeleteGroupTool : Tool {
    override val name = "delete_group"
    override val description = "删除一个分组。组内任务不会被删除，将移至未分组状态。此操作不可撤销。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "要删除的分组 ID"
            }
        },
        "required": ["id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val deleted = core.deleteGroup(id)
        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
        }.toString()
    }
}
