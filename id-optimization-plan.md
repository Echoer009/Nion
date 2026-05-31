# ID 优化方案

## 背景

所有 SQLite 表使用 UUID v4 作为主键（36 字符），对单机个人应用过度设计。UUID 在 LLM 工具调用中浪费大量 token。

## 现状

| 表 | ID 列 | 当前格式 | 生成位置 |
|---|-------|---------|---------|
| checklists | `id TEXT PK` | `f47ac10b-58cc-41d4-...` (36字符) | Rust L185 |
| tasks | `id TEXT PK` | 同上 | Rust L325, L1298 |
| task_groups | `id TEXT PK` | 同上 | Rust L930 |
| attachments | `id TEXT PK` | 同上 | Rust L532 |
| stickers | `id TEXT PK` | 同上 | Rust L620 |
| focus_sessions | `id TEXT PK` | 同上 | Rust L776 |
| chat_conversations | `id TEXT PK` | 同上 | Kotlin `UUID.randomUUID()` |
| daily_completions | `(task_id, date)` 复合主键 | 无独立 ID | — |
| settings | `key TEXT PK` | 字符串键名 | — |

## 目标

纯数字 ID：`1`, `2`, `42`, `100`。每张表独立计数。1 token/个。

## Token 节省测算

以查询 30 条任务为例（每条含 id + category_id + group_id = 3 个 ID）：

| | UUID (36字符) | 纯数字 |
|--|--------------|-------|
| 单个 ID | ~9 tokens | ~1 token |
| 90 个 ID | ~810 tokens | ~90 tokens |
| **节省** | | **~720 tokens** |

## 方案：TEXT 列存纯数字字符串

### 原则
- schema 保持 `TEXT PRIMARY KEY` 不变
- Rust 模型字段保持 `String` 不变
- Kotlin 侧零改动
- 生成 `"1"`, `"42"` 而非 UUID

### ID 生成函数

```rust
/// 查询表中最大数字 ID，返回 +1 的字符串。
/// 旧 UUID 记录的 CAST 结果为 0，不影响新 ID 计数。
/// 在 Mutex 锁内调用，线程安全。
fn next_id(db: &Connection, table: &str) -> Result<String, NionError> {
    let sql = format!(
        "SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0) + 1 FROM {}",
        table
    );
    let next: i64 = db.query_row(&sql, [], |row| row.get(0))
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;
    Ok(next.to_string())
}
```

### Rust 改动清单（nion_core.rs）

| 行号 | 函数 | 当前 | 改为 |
|------|------|------|------|
| L185 | `create_checklist` | `Uuid::new_v4().to_string()` | `next_id(&db, "checklists")?` |
| L325 | `create_task` | 同上 | `next_id(&db, "tasks")?` |
| L532 | `create_attachment` | 同上 | `next_id(&db, "attachments")?` |
| L620 | `create_sticker` | 同上 | `next_id(&db, "stickers")?` |
| L776 | `add_focus_time` | 同上 | `next_id(&db, "focus_sessions")?` |
| L930 | `create_group` | 同上 | `next_id(&db, "task_groups")?` |
| L1298 | 循环任务实例化 | 同上 | `next_id(&db, "tasks")?` |

### 依赖清理

- `Cargo.toml`: 移除 `uuid = { version = "1", features = ["v4"] }`
- `nion_core.rs` L4: 移除 `use uuid::Uuid;`

### chat_conversations 特殊处理

conversation ID 当前在 Kotlin 侧生成（`CompanionViewModel.kt:1330`）：
```kotlin
currentConversationId = UUID.randomUUID().toString()
```
改为简单递增。两种方式：
1. 在 Rust `save_conversation` 内部生成（推荐）
2. Kotlin 侧用 AtomicLong 计数器

## 不需要数据迁移

- TEXT 列兼容 `"1"` 和旧 UUID
- `CAST("f47ac10b-..." AS INTEGER)` = 0，旧记录不干扰新 ID 计数
- 新旧 ID 共存无冲突

## 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 并发 ID 碰撞 | 无 | 已在 Mutex 锁内执行 |
| 文本排序 ≠ 数值排序 | 无影响 | 所有排序使用 sort_order 列 |
| 删除记录后 ID 复用 | 无 | MAX+1 只增不减，已删除的 ID 不会被复用 |

## 涉及文件

| 文件 | 改动 |
|------|------|
| `core/src/nion_core.rs` | 新增 `next_id()`，替换 7 处 UUID，移除 use |
| `core/Cargo.toml` | 移除 uuid 依赖 |
| `app/.../CompanionViewModel.kt` | conversation ID 生成方式 |
