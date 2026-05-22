# 子任务拖拽排序与层级调整 — 设计文档

**日期：** 2026-05-22
**版本：** v2（基于用户需求重写）

---

## 一、用户需求

### 核心交互

所有操作通过**纯上下拖拽**完成，根据松手位置自动判断层级关系：

| 拖拽场景 | 期望行为 |
|---------|---------|
| 子任务拖到列表最上面 | 脱离父任务，成为独立主任务 |
| 子任务拖到其他主任务的下方 | 成为该主任务的子任务 |
| 子任务拖到某主任务的子任务下方 | 成为该子任务的子任务 |
| 子任务拖到某主任务的子任务上方 | 成为该主任务的同级子任务 |

### 视觉要求

1. **拖主任务时** — 子任务视觉上折叠收进主任务卡片中，整体作为一个拖拽单元。这个折叠后的卡片在列表中移动时，与其他 item 之间的"挤开"动画（reorder animation）正常计算和播放。
2. **长按主任务时** — 描边框住整个卡片（主任务 + 所有子任务区域），而不是只框主任务标题那一行。
3. **拖子任务时** — 子任务从父卡片中脱离，作为独立单元在列表中上下移动，根据落点位置自动判断新的层级归属。
4. **松手后** — 所有层级关系更新到数据库，UI 展开显示新的树形结构。

### 约束

- 动画流畅度与现有一致
- 长按描边、多选等交互逻辑保持正常
- 不引入水平拖拽，纯上下方向完成一切

---

## 二、现状分析

### 当前架构

```
LazyColumn（reorderable）
  ├── ReorderableItem → TaskCard A（主任务）
  │     └── Column（普通容器，不可拖拽）
  │           ├── 子任务 A1    ← 在 Column 里，无法拖拽
  │           └── 子任务 A2    ← 在 Column 里，无法拖拽
  └── ReorderableItem → TaskCard B（主任务）
        └── Column
              └── 子任务 B1
```

**问题：**
- 子任务在 `TaskCard` 内部用 `Column + forEach` 渲染（`TaskCard.kt:214`），不是 LazyColumn 的 item
- 无法参与 `reorderable` 的拖拽排序
- 无法跨 TaskCard 移动子任务

### 数据模型

Rust 端（`core/src/nion_core.rs`）：
- `tasks` 表的 `parent_id` 字段标识父子关系，`category_id` 标识所属清单
- 已有 `update_task_parent(task_id, new_parent_id)` API
- 已有 `reorder_tasks(ordered_ids)` API

Kotlin 端（`TaskViewModel.kt`）：
- `TaskItem` 包含 `subtasks: List<TaskItem>` 递归嵌套
- `loadTasksWithSubtasks()` 递归加载整棵任务树

---

## 三、方案：扁平化 LazyColumn

### 核心思路

将树形任务结构**扁平化**为单一列表放入 `LazyColumn`，每个 item 记录它在树中的位置。通过缩进（indent）模拟层级视觉效果。

```
LazyColumn:
  [主任务 A]           depth=0, parentId=null
  [  子任务 A1]        depth=1, parentId=A      ← 缩进 1 级
  [    子任务 A1a]     depth=2, parentId=A1     ← 缩进 2 级
  [  子任务 A2]        depth=1, parentId=A      ← 缩进 1 级
  [主任务 B]           depth=0, parentId=null
```

拖拽松手时，根据目标位置的上下文计算新的 `parent_id` 和排序位置。

### 为什么选这个方案

| 对比项 | 扁平化 LazyColumn | compose-dnd 保留树 | Quire 风格双向拖 |
|-------|------------------|--------------------|-----------------|
| 纯上下拖完成层级调整 | ✅ 自然支持 | ⚠️ 需额外逻辑 | ❌ 需要水平拖 |
| 列表项挤开动画 | ✅ reorderable 原生 | ❌ 需自己实现 | ⚠️ 部分支持 |
| 跨组件拖拽 | ✅ 同一 LazyColumn | ✅ compose-dnd 支持 | ⚠️ 复杂 |
| 现有动画保留 | ⚠️ 需适配 | ✅ 保留 | ✅ 保留 |
| 库依赖 | 无新增（现有 reorderable） | 新增 compose-dnd | 需自定义 |

---

## 四、详细设计

### 4.1 数据模型

新增扁平化数据类：

```kotlin
data class FlatTaskItem(
    val task: TaskItem,
    val depth: Int,        // 0=主任务，1=一级子任务，2=二级子任务...
    val parentId: String?, // 直接父任务 ID
    val isCollapsed: Boolean = false, // 拖拽时是否折叠
)
```

### 4.2 ViewModel 变更

**新增属性：**

```kotlin
val flatTodoTasks: List<FlatTaskItem> by derivedStateOf {
    flattenTree(todoTasks)
}

private fun flattenTree(tasks: List<TaskItem>): List<FlatTaskItem> {
    val result = mutableListOf<FlatTaskItem>()
    for (task in tasks) {
        result.add(FlatTaskItem(task, depth = 0, parentId = null))
        flattenSubtasks(task.subtasks, depth = 1, parentId = task.id, result)
    }
    return result
}

private fun flattenSubtasks(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for (sub in subs) {
        result.add(FlatTaskItem(sub, depth, parentId))
        flattenSubtasks(sub.subtasks, depth + 1, sub.id, result)
    }
}
```

**新增方法：**

```kotlin
fun moveTask(taskId: String, newParentId: String?, afterTaskId: String?) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            core.updateTaskParent(taskId, newParentId)
            // 更新排序
        }
        tasks = withContext(Dispatchers.IO) { loadTasksWithSubtasks(activeChecklistId) }
        scheduleRefreshCounts()
    }
}
```

### 4.3 UI 结构变更

**替换现有渲染：**

将 `TaskCard`（含内部 `SubtaskList`）拆分为两种 item 类型：

| item 类型 | 说明 | 渲染 |
|-----------|------|------|
| **主任务行** | `depth=0` | 带复选框、标题、描述的完整卡片 |
| **子任务行** | `depth>=1` | 缩进 + 简化行（复选框 + 标题） |

**LazyColumn 结构：**

```kotlin
items(flatTodoTasks, key = { it.task.id }, contentType = { "task" }) { flatItem ->
    ReorderableItem(state = reorderableState, key = flatItem.task.id) { isDragging ->
        TaskRow(
            flatItem = flatItem,
            isDragging = isDragging,
            ...
        )
    }
}
```

### 4.4 拖主任务时的折叠行为

当用户开始拖拽一个主任务（depth=0）时：

1. **识别被拖主任务的所有子任务** — 在扁平列表中，从被拖 item 开始，直到遇到下一个 depth=0 的 item（或列表末尾），中间所有 depth>0 的 item 都是该主任务的子任务。

2. **视觉折叠** — 将子任务行的高度动画设为 0（或使用 `AnimatedVisibility`），主任务卡片折叠为紧凑模式。

3. **从 reorderable 列表中移除子任务行** — `reorderableTasks` 只保留被折叠后的主任务行（和其他不受影响的主任务行），这样 reorderable 只对一个 item 计算位移和动画。

4. **拖拽结束** — 恢复 `reorderableTasks` 为完整的扁平列表（在新位置展开子任务）。

```
拖拽前:                         拖拽中:
  [主任务 A]                      [主任务 A] ← 正常
  [  子任务 A1]                    [主任务 B] ← 折叠（子任务隐藏）
  [  子任务 A2]                    [主任务 C] ← 正常
  [主任务 B]
  [  子任务 B1]
  [主任务 C]
```

### 4.5 长按描边行为

**主任务（depth=0）：**

长按时描边应该框住主任务行 + 其下方所有子任务行的整体区域。

实现方式：将主任务和它的子任务包裹在同一个 `Column` 容器中，描边作用在容器上：

```kotlin
// 主任务 + 子任务作为一个视觉组
Column(
    modifier = Modifier
        .then(
            if (isSelected) Modifier.border(
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                cardShape,
            ) else Modifier
        )
) {
    MainTaskRow(task)           // 主任务行
    SubtaskRows(flatItems)      // 子任务行（缩进）
}
```

**子任务（depth>=1）：**

描边只框住子任务自身那一行。

### 4.6 层级判定逻辑

拖拽松手时，根据目标位置计算新的层级关系：

```
目标位置上方的 item:
  if (上方是主任务, depth=0) → newParentId = 上方任务的 id
  if (上方是子任务, depth>当前) → newParentId = 上方任务的 id（成为它的子任务）
  if (上方是子任务, depth==当前) → newParentId = 上方任务的 parentId（兄弟关系）
  if (上方是子任务, depth<当前) → 向上回溯找到 depth 相等的祖先，取其 parentId

目标位置是列表顶部:
  → newParentId = null（提升为主任务）
```

### 4.7 Rust 端变更

无需新增 API。已有：
- `update_task_parent(task_id, new_parent_id)` — 改变父任务
- `reorder_tasks(ordered_ids)` — 更新排序

`moveTask` 方法组合调用这两个 API 即可。

---

## 五、涉及文件

| 文件 | 变更内容 |
|------|---------|
| `TaskViewModel.kt` | 新增 `FlatTaskItem`、`flatTodoTasks`、`moveTask()`、折叠/展开逻辑 |
| `TaskScreen.kt` | LazyColumn 改为渲染扁平列表，修改 reorderable 的 onMove/onDragStopped 逻辑 |
| `TaskCard.kt` | 拆分为 `MainTaskRow` + `SubtaskRow`，删除原有 `SubtaskList` 递归渲染 |
| `nion_core.rs` | 无变更（已有 `update_task_parent`） |
| `models.rs` | 无变更 |

---

## 六、实现步骤

1. **新增 `FlatTaskItem` 和扁平化逻辑** — ViewModel 中实现树 → 扁平列表的转换
2. **重写 LazyColumn 渲染** — 用 `TaskRow` 替代 `TaskCard`，根据 depth 控制缩进
3. **实现拖主任务时的折叠** — onDragStarted 时折叠子任务，onDragStopped 时展开
4. **实现长按描边** — 主任务+子任务整体描边
5. **实现层级判定** — onDragStopped 时根据落点位置计算 newParentId
6. **实现 `moveTask`** — 组合调用 Rust 的 `update_task_parent` + `reorder_tasks`
7. **适配多选模式** — 确保多选删除等逻辑在扁平列表上正常工作
8. **测试** — 各种拖拽场景：子任务→主任务、主任务→子任务、同级排序、跨层级移动

---

## 七、风险和注意事项

- **折叠动画性能** — 主任务拖拽时折叠/展开子任务行需要平滑过渡，建议使用 `animateContentSize` 或 `AnimatedVisibility`
- **reorderable 兼容性** — 拖拽过程中动态移除/添加 item 可能导致 reorderable 状态异常，需要仔细处理 index 映射
- **已完成任务** — `doneTasks` 区域暂不支持拖拽层级调整（保持现状），仅 `todoTasks` 区域支持
- **循环依赖** — 拖拽判定时需检查不能将一个任务拖为其自身后代的子任务
- **长按冲突** — 现有的长按选择 vs 长按拖拽需要统一处理（现有 `wasMoved` 机制可复用）
