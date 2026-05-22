# Jetpack Compose 动画与列表性能最佳实践

## 核心原则

Compose 的渲染管线分三个阶段：**Composition → Layout → Draw**。优化的核心目标是让每一帧的工作量控制在 16ms 以内（60Hz），尽可能跳过不必要的阶段。

---

## 1. State 管理：减少 Recomposition

### 1.1 批量写入 State

多个 `mutableStateOf` 连续赋值会触发多轮 recomposition：

```kotlin
// ❌ 触发 3 轮 recomposition
activeChecklistId = id
tasks = loadedTasks
checklistCounts = newCounts

// ✅ 用 Snapshot 批量写入，只触发 1 轮
Snapshot.withMutableSnapshot {
    activeChecklistId = id
    tasks = loadedTasks
    checklistCounts = newCounts
}
```

### 1.2 用 derivedStateOf 隔离计算

```kotlin
// ❌ 每次 tasks 变化都重新 filter，所有读取方都重组
val todoTasks = tasks.filter { !it.isDone }

// ✅ derivedStateOf 只在结果实际变化时通知读取方
val todoTasks: List<TaskItem> by derivedStateOf { tasks.filter { !it.isDone } }
```

### 1.3 防抖高频 State 更新

切换清单后立即查询所有清单计数会叠加 recomposition：

```kotlin
// ✅ 300ms 防抖，让主列表先渲染完
private var countsJob: Job? = null
private fun scheduleRefreshCounts() {
    countsJob?.cancel()
    countsJob = viewModelScope.launch {
        delay(300)
        refreshCounts()
    }
}
```

### 1.4 @Stable 注解

Compose 编译器需要确认参数 "stable" 才能跳过 recomposition。data class 默认被推断为 stable，但 `List<T>` 等集合类型不是。显式标注：

```kotlin
@Stable
data class TaskItem(
    val id: String,
    val title: String,
    // ...
)
```

---

## 2. LazyColumn 性能

### 2.1 必须提供 key

没有 key 时 Compose 用 index 标识 item。增删 item 会导致后续所有 item 重组：

```kotlin
items(tasks, key = { it.id }) { task -> ... }
```

### 2.2 添加 contentType

让 Compose 复用相同类型的 composition slot（类似 RecyclerView 的 ViewHolder 复用）：

```kotlin
items(
    items = tasks,
    key = { it.id },
    contentType = { "todo_task" },  // 同类型 item 共享 slot
) { task -> ... }
```

### 2.3 给所有 item 加稳定 key（包括 header/spacer）

```kotlin
item(key = "todo_header", contentType = "header") { SectionHeader(...) }
item(key = "bottom_spacer", contentType = "spacer") { Spacer(...) }
```

### 2.4 避免在 item 内创建新 lambda

`LazyListScope` 不是 composable scope，strong skipping 不自动缓存 lambda：

```kotlin
// ✅ 把 callback 提升到 LazyColumn 外层
val onToggleDone = remember(viewModel) { { task: TaskItem -> viewModel.toggleDone(task) } }

LazyColumn {
    items(tasks, key = { it.id }) { task ->
        TaskCard(task = task, onToggleDone = onToggleDone)
    }
}
```

### 2.5 用 remember 缓存中间计算结果

```kotlin
// ❌ 每次 recomposition 都重新计算 map
customCounts = checklistCounts.mapNotNull { ... }.toMap()

// ✅ 只在 source 变化时重算
val customCounts = remember(checklistCounts) {
    checklistCounts.mapNotNull { (k, v) -> k?.let { it to v } }.toMap()
}
```

---

## 3. 颜色动画：永远不要动画到 Transparent

### 3.1 黑闪问题

`Color.Transparent` 是 `(0, 0, 0, 0)`。从任何颜色插值到它，中间帧的 RGB 会趋近黑色：

```kotlin
// ❌ 中间帧经过暗色 → 闪黑
animateColorAsState(
    targetValue = if (isActive) primaryContainer else Color.Transparent,
)

// ✅ 动画到父容器的背景色，中间帧颜色自然过渡
animateColorAsState(
    targetValue = if (isActive) primaryContainer else surfaceContainer,
)
```

### 3.2 规则

| 动画目标 | 是否安全 | 原因 |
|----------|---------|------|
| `Color.Transparent` | ❌ | RGB 插值经过黑色 |
| 父容器背景色 | ✅ | 颜色同色系过渡 |
| `Color.White.copy(alpha = 0f)` | ❌ | 同上，RGB 不对 |
| 相邻色阶 | ✅ | 最平滑 |

---

## 4. 列表同步用 snapshotFlow 而非 LaunchedEffect(key)

```kotlin
// ❌ LaunchedEffect 每次新 list 实例都触发，即使内容相同
LaunchedEffect(todoTasks) {
    reorderableTasks.clear()
    reorderableTasks.addAll(todoTasks)
}

// ✅ snapshotFlow + distinctUntilChanged 只在值实际变化时触发
LaunchedEffect(Unit) {
    snapshotFlow { todoTasks }
        .distinctUntilChanged()
        .collect { newTasks ->
            reorderableTasks.clear()
            reorderableTasks.addAll(newTasks)
        }
}
```

---

## 5. 延迟读取 State（Defer Reads）

把 state 读取推迟到 modifier lambda 里，可以让 Compose 跳过 Composition 阶段：

```kotlin
// ❌ 在 Composition 阶段读取 → offset 变化触发整个 composable 重组
Modifier.offset(x = scrollState.value.dp)

// ✅ 在 Layout 阶段读取 → 跳过 Composition
Modifier.offset { IntOffset(scrollState.value, 0) }
```

同样的原则适用于 `graphicsLayer`、`drawBehind` 等 lambda 版本的 modifier。

---

## 6. 性能排查工具

| 工具 | 用途 |
|------|------|
| Layout Inspector (Android Studio) | 查看每个 composable 的 recomposition 次数 |
| `adb logcat` + 自定义耗时日志 | 测量 ViewModel 操作耗时 |
| Macrobenchmark | 自动化帧率测试 |
| JankStats | 生产环境掉帧检测 |
| Compose Compiler Reports | 查看 skip/non-skip 函数报告 |

---

## 7. SharedTransition + AnimatedContent：元素变形弹出动画

### 7.1 效果描述

点击一个小组件（如月份标题栏），它从原位"膨胀"变形为一个大的覆盖弹窗。关闭时反向收缩回原位。

### 7.2 核心结构

```
SharedTransitionLayout {
    AnimatedContent(showCalendarPicker) { showing ->
        if (showing) {
            CalendarPickerDialog(modifier = sharedElement("calendar"))
        } else {
            ScheduleContent(modifier = sharedElement("calendar"))
        }
    }
}
```

三要素：
1. **`SharedTransitionLayout`** — 最外层容器，提供 shared element 的协调作用域
2. **`AnimatedContent`** — 控制两个状态的切换动画（fade + size transform）
3. **`Modifier.sharedElement()`** — 标记参与变形的两个元素，用同一个 key 关联

### 7.3 关键代码

```kotlin
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ScheduleScreen() {
    var showCalendarPicker by remember { mutableStateOf(false) }

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showCalendarPicker,
            transitionSpec = {
                // 展开：快速淡入 + 慢淡出，SizeTransform 做尺寸动画
                if (targetState) {
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收回：慢淡入 + 快淡出
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "calendar",
        ) { showingCalendar ->
            if (showingCalendar) {
                CalendarPickerOverlay(
                    // 收缩状态的 Modifier — 和展开状态用同一个 key
                    sharedBoundsModifier = Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("calendar"),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = { _, _ ->
                            // spring 物理动画，让尺寸变化有弹性
                            spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                    ),
                )
            } else {
                ScheduleContent(
                    // 展开状态的 Modifier
                    sharedBoundsModifier = Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("calendar"),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = { _, _ ->
                            spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                    ),
                )
            }
        }
    }
}
```

### 7.4 设计要点

| 要点 | 说明 |
|------|------|
| **key 一致** | 两个状态的 `rememberSharedContentState("calendar")` 用同一个字符串 key，Compose 自动匹配 |
| **shape 一致** | 两个元素的 `shape` 必须相同（都用 `RoundedCornerShape(24.dp)`），否则圆角会闪烁 |
| **`boundsTransform` 用 spring** | 物理弹簧动画让尺寸变化自然有弹性，比 tween 线性好 |
| **`SizeTransform(clip = false)`** | 允许内容溢出边界，变形过程中不会裁剪 |
| **fade 节奏不对称** | 展开时淡入慢、淡出快；收回时淡入快、淡出慢，避免两阶段内容同时可见造成"重影" |
| **modifier 作为参数传递** | 两个状态各自通过 `sharedBoundsModifier` 参数接收 modifier，保持 composable 签名干净 |

### 7.5 踩坑记录

- **`renderInSharedTransitionScopeOverlay`** 是独立的 `Modifier`，不是 `sharedElement` 的参数。`sharedElement` 默认已在 overlay 渲染（`renderInOverlayDuringTransition = true`）
- **`sharedElement` vs `sharedBounds`**：`sharedElement` 做完整形状+内容 morph，效果更好；`sharedBounds` 只做边界动画
- **shape 不匹配会闪烁**：如果源元素是 `RoundedCornerShape(8.dp)` 而目标是 `24.dp`，过渡期间圆角会跳动。两端的 Surface 必须用相同 shape

---

## 快速检查清单

- [ ] LazyColumn items 是否有 `key`？
- [ ] LazyColumn items 是否有 `contentType`？
- [ ] 切换场景时是否只触发 1 轮 state 写入？
- [ ] 颜色动画是否避免了 `Color.Transparent` 作为目标？
- [ ] 高频更新是否用了 debounce？
- [ ] Lambda callback 是否提升到 LazyColumn 外层或用 `remember` 缓存？
- [ ] 中间计算是否用 `remember(source)` 缓存？
- [ ] data class 是否加了 `@Stable`？
