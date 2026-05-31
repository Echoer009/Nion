# Create 工具参数优化方案

## 改动总览

| 维度 | 改动 |
|------|------|
| 参数数量 | 13 → 10 |
| 删除的参数 | `category_id`、`recurrence_rule`、`recurrence_reminder_time` |
| 合并的参数 | `title` + `name` → `name`；`category_id` + `checklist_id` → `checklist_id`；3个提醒参数 → `reminder` |
| 新增 description | 仅 `reminder` 一个参数 |

## 逐项改动

### 1. `title` + `name` → `name`

所有实体统一用 `name` 表示名称。execute 里对 task 映射到 Rust 层的 `title` 字段。

是否同时改数据库（`TaskData.title` → `TaskData.name`）待定，先只改工具层映射。

### 2. `category_id` + `checklist_id` → `checklist_id`

- 原因：`category_id` 语义不清，与 `checklist_id` 并存导致 AI 搞混
- execute 里按 `entity_type` 路由：
  - task → 传给 Rust 层的 `categoryId`
  - group → 传给 Rust 层的 `checklistId`

### 3. `recurrence_rule` + `recurrence_reminder_time` + `reminder` → `reminder`

合并为一个参数，靠格式自动推断类型：

| 值 | 含义 | Rust 层映射 |
|----|------|------------|
| `"08:00"` | 仅时间 → 每日循环 | `recurrenceRule="daily"` + `recurrenceReminderTime="08:00"` |
| `"2026-12-31T09:00"` | 日期时间 → 一次性 | `reminder="2026-12-31T09:00"` |
| 不传 | 无提醒 | 不设置 |

Schema 中加 description：`"仅HH:MM=每日循环, 日期时间=一次性提醒, 不传=无提醒"`

### 4. 分组继承

当传了 `group_id` 但没传 `checklist_id` 时，自动从 group 取 `checklistId`：

```kotlin
if (groupId != null && checklistId == null) {
    val group = core.getGroup(groupId)
    checklistId = group.checklistId
}
```

## 最终参数表

| 参数 | 类型 | 用于 | 说明 |
|------|------|------|------|
| `entity_type` | enum: task/checklist/group | 所有 | 路由键，必填 |
| `name` | string | 所有 | 实体名称 |
| `description` | string | task | 任务描述 |
| `priority` | enum: low/medium/high | task | 优先级 |
| `checklist_id` | string | task + group | 所属清单（传了 group_id 时可不传，自动继承） |
| `parent_id` | string | task | 父任务 ID |
| `group_id` | string | task | 所属分组 |
| `reminder` | string | task | 提醒设置（唯一带 description 的参数） |
| `color` | string | group | 分组颜色 |
| `items` | array of object | 所有 | 批量创建 |

## 涉及文件

| 文件 | 改动 |
|------|------|
| `app/.../tools/UnifiedTools.kt` | CreateTool 的 schema、executeCreateTask、executeBatchCreate |
| `core/src/models.rs` | 可选：TaskData.title → name |
| `core/src/nion_core.rs` | 可选：SQL 和引用 |
