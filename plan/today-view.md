# "今天"视图实施计划

## 目标

替换侧边栏默认的"我的任务"为"今天"，作为 App 启动首屏，跨所有清单聚合今日任务。

## 改动后的显示逻辑

### 侧边栏
- 第一项：**"今天"**（key=`"today"`，不可删除）
- 后面：用户创建的清单列表，不变

### "今天"视图（默认，App 启动首屏）
- **触发条件**：`activeChecklistId == "today"`（特殊常量值）
- **数据来源**：Rust 端新增的 `get_tasks_due_today("2026-05-25")`
- **SQL 逻辑**：
  ```sql
  WHERE parent_id IS NULL
    AND (
      due_date = ?1                              -- 截止日期 = 今天
      OR (
        recurrence_rule = 'daily'                -- 每日循环
        AND (due_date IS NULL OR due_date >= ?1) -- 未过期
      )
    )
  ORDER BY sort_order ASC, created_at DESC
  ```
- **结果**：跨所有清单聚合：
  1. 截止日期 = 今天的顶层任务
  2. 设置了每日循环且未过期（或无截止日期）的顶层任务
- **子任务**：母任务下的子任务递归加载，不单独筛选

### 某个清单视图
- 逻辑不变：`get_tasks_by_category(Some(checklistId), groupId)`

### 顶部标题
- `activeChecklistId == "today"` → 显示 **"今天"**
- `activeChecklistId == 某ID` → 显示该清单的名称

### 与现在的区别

| | 现在 | 改完 |
|---|---|---|
| 侧边栏第一项 | "我的任务" | "今天" |
| 默认视图 | 未分配清单的孤儿任务 | 跨所有清单的今日任务 |
| 跨清单聚合 | 不支持 | 支持（"今天"视图） |
| 每日循环任务 | 无特殊视图 | 自动出现在"今天" |
| `null` 视图（未分配任务） | 默认入口 | 不再作为默认，但逻辑保留 |

---

## 实施步骤

### 第 1 层：Rust Core

**文件：`core/src/nion_core.rs`**

新增 `get_tasks_due_today` 方法，放在现有的查询方法之后。

```rust
/// 获取今日需关注的任务：
/// 1. 截止日期 = 今天的任务
/// 2. 设置了每日循环（recurrence_rule='daily'）且未过期（due_date 为空或 >= 今天）的任务
///
/// 参数 date: "YYYY-MM-DD" 格式的日期字符串
/// 返回：跨所有清单聚合的顶层任务（parent_id IS NULL）
#[uniffi::export]
pub fn get_tasks_due_today(&self, date: String) -> Result<Vec<TaskData>, NionError> {
    let db = self.db.lock().map_err(|e| NionError::DatabaseError {
        msg: e.to_string(),
    })?;

    let sql = "SELECT id, title, description, priority, status, due_date, reminder, parent_id, category_id, group_id, created_at, updated_at, completed_at, focus_seconds, recurrence_rule, recurrence_reminder_time FROM tasks WHERE parent_id IS NULL AND (due_date = ?1 OR (recurrence_rule = 'daily' AND (due_date IS NULL OR due_date >= ?1))) ORDER BY sort_order ASC, created_at DESC";

    let mut stmt = db.prepare(sql)
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

    let rows = stmt
        .query_map(params![date], |row| map_task_row(row))
        .map_err(|e| NionError::DatabaseError { msg: e.to_string() })?;

    let mut result = Vec::new();
    for row in rows {
        result.push(row.map_err(|e| NionError::DatabaseError { msg: e.to_string() })?);
    }
    Ok(result)
}
```

**位置**：放在 `get_tasks_by_category` 方法之后（约第 207 行）。

**测试**：新增单元测试验证 SQL 逻辑。

---

### 第 2 层：UniFFI

重新生成 Kotlin bindings，UniFFI 会自动生成 `getTasksDueToday` 方法到 `nion_core.kt`。

```bash
cargo build -p nion-core --release
cargo run -p uniffi-bindgen-cli -- generate --library target/release/nion_core.dll --language kotlin --out-dir /tmp/uniffi-output
cp /tmp/uniffi-output/uniffi/nion_core/nion_core.kt app/app/src/main/java/uniffi/nion_core/nion_core.kt
```

---

### 第 3 层：Android UI

#### 3.1 `TaskViewModel.kt`

**新增常量**：
```kotlin
companion object {
    const val TODAY_ID = "today"  // "今天"视图的虚拟 ID
}
```

**修改 `activeChecklistName`**：
```kotlin
val activeChecklistName: String
    get() = when (activeChecklistId) {
        TODAY_ID -> "今天"
        null -> "我的任务"  // 保留，但不再作为默认
        else -> checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"
    }
```

**修改 `refresh()`**：
- 当 `activeChecklistId == TODAY_ID` 时，调用 `loadTodayTasks()` 替代 `loadTasksWithSubtasks`
- 当 `activeChecklistId == null` 时，仍用 `loadTasksWithSubtasks(null, null)`（保留）

```kotlin
fun refresh() {
    viewModelScope.launch {
        // ... checklist loading ...
        // ... groups loading ...
        try {
            val loadedTasks = if (activeChecklistId == TODAY_ID) {
                withContext(Dispatchers.IO) { loadTodayTasks() }
            } else {
                withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(activeChecklistId, activeGroupId)
                }
            }
            tasks = loadedTasks
        } catch (e: Exception) {
            onError("加载任务失败: ${e.message}")
        }
        // ... refresh counts ...
    }
}
```

**新增 `loadTodayTasks` 方法**：
```kotlin
/**
 * 加载今日任务：due_date=今天 或 每日循环且未过期。
 * 调用 Rust 端 getTasksDueToday 进行跨清单聚合查询。
 */
private fun loadTodayTasks(): List<TaskItem> {
    val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    fun loadChildren(parentId: String): List<TaskItem> {
        return core.getSubtasks(parentId).map { task ->
            val subs = loadChildren(task.id)
            task.toUi().copy(subtasks = subs)
        }
    }
    return core.getTasksDueToday(todayStr).map { task ->
        val subs = loadChildren(task.id)
        task.toUi().copy(subtasks = subs)
    }
}
```

**修改 `refreshCounts()`**：
- 新增对 `TODAY_ID` 的任务计数

**修改 `setActiveChecklist`**：
- 支持传入 `TODAY_ID` 切换至"今天"视图

**修改 `init`**：
- 初始 `activeChecklistId = TODAY_ID`（App 启动默认"今天"）

---

#### 3.2 `Sidebar.kt`

**将第一个静态 item 从"我的任务"改为"今天"**：

```kotlin
item(key = "today") {
    SidebarChecklistItem(
        name = "今天",
        taskCount = todayCount.first,
        subtaskCount = todayCount.second,
        isActive = activeChecklistId == TaskViewModel.TODAY_ID,
        onClick = { onSelectChecklist(TaskViewModel.TODAY_ID) },
        showDelete = false,
        onDelete = {},
    )
}
```

需要新增 `todayCount: Pair<Int, Int>` 参数或从 ViewModel 的 `checklistCounts` 中用 `TODAY_ID` 键获取。

---

#### 3.3 `TaskScreen.kt`

无需改动 UI 结构。顶部标题通过 `viewModel.activeChecklistName` 自动显示"今天"。

可能需要微调 `activeChecklistId` 初始化为 `TODAY_ID` 而非 `null`。

---

### 涉及文件清单

| 文件 | 改动类型 |
|------|---------|
| `core/src/nion_core.rs` | 新增 `get_tasks_due_today` 方法 + 单元测试 |
| `app/.../uniffi/nion_core/nion_core.kt` | 重新生成（UniFFI 自动） |
| `app/.../ui/task/TaskViewModel.kt` | TODAY_ID 常量、activeChecklistName、refresh 路由、loadTodayTasks、refreshCounts、init |
| `app/.../ui/task/Sidebar.kt` | "我的任务" → "今天" |
| `app/.../ui/task/TaskScreen.kt` | 可能的微调（默认选中） |

### 不涉及的文件

- `TaskCard.kt` / `BottomSheets.kt` / `DatePickerRow.kt` / `RecurrenceSelector.kt` — 不改
- `ScheduleScreen.kt` — 不改
- `backend/` — 不改

---

## 验证

1. `cargo test -p nion-core` — 验证 Rust 单元测试通过
2. 重新生成 UniFFI bindings
3. App 启动 → 默认显示"今天"视图
4. 创建一个 `due_date = 今天` 的任务 → 出现在"今天"
5. 创建一个 `recurrence_rule = "daily"` 的任务 → 出现在"今天"
6. 切换至某个清单 → 显示该清单的任务（不变）
7. 侧边栏"今天"项高亮且不可删除
