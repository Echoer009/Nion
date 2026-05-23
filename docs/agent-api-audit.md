# Agent API 审计报告

> 审计日期：2026-05-23
> 审计范围：nion-core (Rust) 通过 UniFFI 暴露的方法是否可供 AI Agent 使用

---

## 1. 现状总览

### 1.1 核心库方法清单（`nion-core`）

以下方法已通过 `#[uniffi::export]` 暴露给 Kotlin：

| 方法 | 功能 | Agent 可用性 |
|------|------|-------------|
| `create_task` | 创建任务（标题、描述、优先级、截止日期、分类、父任务） | ✅ 方法存在 |
| `update_task` | 更新任务（标题、描述、优先级、状态） | ⚠️ 字段不完整 |
| `delete_task` | 删除任务 | ✅ 方法存在 |
| `get_tasks` | 获取全部任务 | ✅ 方法存在 |
| `get_tasks_by_category` | 按 checklist 获取任务 | ✅ 方法存在 |
| `get_subtasks` | 获取子任务 | ✅ 方法存在 |
| `update_task_parent` | 修改任务父子关系 | ✅ 方法存在 |
| `reorder_tasks` | 任务重排序 | ✅ 方法存在 |
| `add_focus_time` | 累加专注时长 | ✅ 方法存在 |
| `create_checklist` | 创建清单 | ✅ 方法存在 |
| `get_checklists` | 获取清单列表 | ✅ 方法存在 |
| `delete_checklist` | 删除清单 | ✅ 方法存在 |
| `reorder_checklists` | 清单重排序 | ✅ 方法存在 |
| `get_setting` | 读取设置 | ✅ 方法存在 |
| `set_setting` | 写入设置 | ✅ 方法存在 |

### 1.2 结论

**方法层面**：大部分 agent 需要的 CRUD 操作已在 Rust 核心库中实现。

**Agent 调用层面**：**完全不存在**。当前只有 Kotlin UI 代码通过 UniFFI 直接调用这些方法，没有任何机制让 AI Agent（LLM）通过 tool calling 来调用它们。

---

## 2. 缺口分析

### 2.1 核心库方法缺失（Rust 层）

| 缺失方法 | 说明 | Agent 场景 |
|----------|------|-----------|
| `get_task(id)` | 查询单个任务 | Agent 需要查看某个任务的详情 |
| `update_task` 缺 `due_date` 参数 | 截止日期创建后无法修改 | Agent 帮用户调整截止日期 |
| `update_task` 缺 `category_id` 参数 | 任务无法移到其他 checklist | Agent 帮用户重新归类任务 |
| `update_task` 缺 `reminder` 参数 | 提醒时间创建后无法修改 | Agent 帮用户设置/修改提醒 |
| `update_checklist_name` | checklist 无法改名 | Agent 修改清单名称 |

### 2.2 Agent Tool Calling 层完全缺失

当前架构：

```
[Kotlin UI] --UniFFI--> [nion-core (Rust)]  ✅ 已通
[AI Agent]  --???--->   [nion-core (Rust)]  ❌ 不存在
```

要让 AI Agent 调用这些方法，需要新增以下组件：

#### a) 工具 Schema 定义（Tool Definitions）

为每个方法定义 JSON Schema 格式的工具描述，供 LLM 识别可用的工具和参数格式。例如：

```json
{
  "name": "create_task",
  "description": "创建一个新任务",
  "parameters": {
    "type": "object",
    "properties": {
      "title": { "type": "string", "description": "任务标题" },
      "description": { "type": "string", "description": "任务描述" },
      "priority": { "type": "string", "enum": ["low", "medium", "high"] },
      "due_date": { "type": "string", "description": "截止日期 (ISO 8601)" },
      "category_id": { "type": "string", "description": "所属清单 ID" },
      "parent_id": { "type": "string", "description": "父任务 ID，用于创建子任务" }
    },
    "required": ["title", "priority"]
  }
}
```

#### b) 工具路由层（Tool Router）

Kotlin 端需要一个路由机制：
- 解析 LLM 返回的 `tool_call`（函数名 + JSON 参数）
- 分发到对应的 UniFFI 方法调用
- 将 Rust 返回值序列化为 JSON，回传给 LLM

#### c) 工具执行上下文

- 需要处理异步调用（UniFFI 同步方法需要在 `Dispatchers.IO` 执行）
- 需要错误处理和结果格式化
- 需要考虑权限控制（agent 能做哪些操作、不能做哪些）

---

## 3. 建议实施路径

### 阶段一：补齐核心库方法

在 `nion-core` 中补全以下方法：

1. **`get_task(id: String) -> Result<TaskData, NionError>`** — 单任务查询
2. **扩展 `update_task`** — 增加 `due_date`、`category_id`、`reminder` 参数
3. **`update_checklist_name(id: String, name: String) -> Result<ChecklistData, NionError>`** — checklist 改名

### 阶段二：构建 Agent Tool Calling 层

在 Android 端新增：

1. **Tool Schema 定义**（Kotlin data class 或 JSON 文件）— 描述所有 agent 可用的工具
2. **ToolExecutor 接口** — 接收工具名和参数，调用对应 UniFFI 方法，返回 JSON 结果
3. **与 LLM 对话循环集成** — 在 companion 聊天流程中处理 `tool_call`，执行后回传结果

### 阶段三：增强

- 工具调用权限控制（只读 vs 读写）
- 批量操作（一次创建多个子任务）
- 操作审计日志

---

## 4. 关键文件参考

| 文件 | 说明 |
|------|------|
| `core/src/nion_core.rs` | Rust 核心方法实现 |
| `core/src/models.rs` | 数据模型定义 |
| `app/app/src/main/java/uniffi/nion_core/nion_core.kt` | UniFFI 自动生成的 Kotlin 绑定 |
