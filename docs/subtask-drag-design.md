# 子任务拖拽排序与层级调整 — 设计文档

## 目标

1. 子任务可以长按选择、拖拽排序
2. 子任务可以拖出成为主任务
3. 主任务可以拖入成为另一个任务的子任务

## 现状问题

当前架构：子任务嵌套在 `TaskItem.subtasks: List<TaskItem>` 里，在 `TaskCard` 内部用 `Column + forEachIndexed` 递归渲染。

问题：
- 子任务不在 LazyColumn 中，无法参与 reorderable 拖拽
- 子任务和主任务在不同的渲染层级，无法互相拖拽
- 数据库层面，子任务只是 `parent_id` 指向父任务的任务

## 方案：扁平化列表

### 核心思路

把主任务和子任务展平到一个 LazyColumn 中，用 `depth` 字段控制缩进层级。

### 数据模型变更

```kotlin
/** LazyColumn 中的一行，可以是主任务或任意深度的子任务 */
data class FlatTaskItem(
    val task: TaskItem,
    val depth: Int,        // 0=主任务，1=一级子任务，2=二级...
    val parentId: String?, // 直接父任务 ID
)
```

### ViewModel 变更

新增 `flatTodoTasks: List<FlatTaskItem>` derived state：

```kotlin
val flatTodoTasks: List<FlatTaskItem> by derivedStateOf {
    val result = mutableListOf<FlatTaskItem>()
    for (task in todoTasks) {
        result.add(FlatTaskItem(task, depth = 0, parentId = null))
        flattenSubtasks(task.subtasks, depth = 1, parentId = task.id, result)
    }
    result
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

### UI 变更

LazyColumn 只有一个 `items` 块，所有任务（含子任务）都在同一层级：

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

### TaskRow 组件

替代现有的 `TaskCard`（仅渲染主任务）+ `SubtaskList`（递归渲染子任务）：

```kotlin
@Composable
fun TaskRow(flatItem: FlatTaskItem, ...) {
    Row {
        // 根据 depth 显示缩进 + 树形连接线
        repeat(flatItem.depth) {
            TreeLine()  // 竖线/拐角
        }
        // 复选框 + 标题（复用现有样式）
        TaskContent(flatItem.task)
    }
}
```

### 拖拽排序逻辑

拖拽结束时判断目标位置：

```
源位置: flatTodoTasks[fromIdx]
目标位置: flatTodoTasks[toIdx]

情况1: depth 相同 → 同级排序，更新 reorder order
情况2: 拖到更深层 → 改变 parent_id（成为子任务）
情况3: 拖到 depth=0 → parent_id = null（提升为主任务）
```

需要实现的 ViewModel 方法：

```kotlin
/** 拖拽结束后更新任务的层级关系 */
fun moveTask(taskId: String, newParentId: String?, afterTaskId: String?) {
    // 1. 更新 parent_id
    // 2. 更新 reorder order
    // 3. 重新加载任务列表
}
```

对应的 Rust core 需要新增：

```rust
fn update_task_parent(&self, task_id: &str, new_parent_id: Option<&str>) -> Result<()>;
```

### 视觉规则

| 拖拽目标位置 | 行为 |
|-------------|------|
| 拖到另一个主任务上方 | 排序到它前面 |
| 拖到子任务缩进区域 | 成为该子任务的兄弟（同 parent） |
| 拖到主任务的第一行 | 成为该主任务的子任务 |
| 拖到列表顶部（depth=0 区域） | 提升为主任务 |

### 缩进交互

参考 Things 3 / TickTick 的做法：
- 拖拽时，目标位置会显示水平缩进指示线
- 缩进线对齐到可能的父任务层级
- 松手时根据缩进线深度确定新的 parent_id

## 实现步骤

1. **Rust core**: 新增 `update_task_parent(task_id, new_parent_id)` API
2. **ViewModel**: 新增 `FlatTaskItem`、`flatTodoTasks`、`moveTask()`
3. **UI**: 新建 `TaskRow` 组件（带缩进和树形线），替换现有 TaskCard + SubtaskList
4. **拖拽逻辑**: 修改 `onMove` 和 `onDragStopped` 支持跨层级移动
5. **视觉**: 添加拖拽目标缩进指示线
6. **测试**: 确认子任务→主任务、主任务→子任务、同级排序都正常

## 风险和注意事项

- `reorderable` 库的 `onMove` 只提供 from/to index，不提供横向偏移，缩进指示可能需要自定义实现
- 递归子任务（子任务的子任务）的树形线渲染要正确处理最后一项的拐角
- `reorderTasks` 现在只处理同层排序，跨层需要 `moveTask`
- 扁平化后 `doneTasks` 也需要同样处理（如果也要拖拽已完成任务的子任务）
- 现有的 `reorderableTasks` 同步机制需要改为同步 `flatTodoTasks`
