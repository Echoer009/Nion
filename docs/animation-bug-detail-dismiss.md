# 任务详情关闭时触发虚假完成动画 Bug 分析

## 症状

1. 点击任务卡片打开详情浮层
2. 点击 × 或点击 scrim 关闭详情
3. 看到类似"勾选完成按钮"的动画（勾选框弹跳 + item 飞下）
4. 实际没有任务被标记为完成
5. **不能稳定复现** — 取决于已完成任务列表是否为空

## 根因分析

### 嫌疑人 1（最可能）：`LaunchedEffect(task.isDone)` 首次 composition 即执行

**位置**：`TaskCard.kt:168-174`

```kotlin
val checkScale = remember { Animatable(1f) }
LaunchedEffect(task.isDone) {
    if (task.isDone) {
        checkScale.animateTo(1.3f, tween(120))
        checkScale.animateTo(1f, spring(...))
    }
}
```

**`LaunchedEffect(key)` 的行为**：首次进入 composition 时**一定会执行一次**，不仅仅在 key 变化时执行。这与常见直觉相反。

**触发链路**：

1. 打开详情 → `AnimatedContent` 把 list 分支 dispose（`fadeOut(250ms)` 后销毁）
2. 关闭详情 → `AnimatedContent` 切换：详情 `fadeOut(400ms)`，列表 `fadeIn(250ms)` 重新进入 composition
3. `TaskList` 被全新创建（`remember` 全部重置）
4. 已完成区域（`TaskScreen.kt:1499-1537`）渲染 done items
5. 每个 `MainTaskRow` 的 `task.isDone = true`，`LaunchedEffect` 首次执行
6. `if (task.isDone)` = true → **所有已完成任务的勾选框同时弹跳**
7. 弹跳动画发生在 `AnimatedContent` crossfade 期间（`clip = false`），透过半透明 scrim 可见
8. 视觉上：多个 item 同时弹跳 → "像点了一堆完成按钮"

**与其他现象的吻合度**：

| 现象 | 解释 |
|------|------|
| "像是完成按钮的动画" | 勾选框 scale 弹跳（1.0→1.3→1.0）正是完成动画 |
| "有东西飞下去" | `animateItem()` 与 bounce 叠加，item 看起来在"飞" |
| "实际上没有完成任何东西" | `toggleDone` 从未被调用，这只是 `LaunchedEffect` 误触发 |
| "不能稳定复现" | 已完成列表为空时无动画；有任务时总是触发，但受 crossfade 遮盖程度影响 |

### 嫌疑人 2（次要）：已完成 item 的 `animateItem()` + crossfade 叠加

**位置**：`TaskScreen.kt:1512`

```kotlin
Box(modifier = Modifier
    .animateItem()   // ← 已完成 item 不需要排序动画
    .padding(vertical = 4.dp)
)
```

列表重新进入 composition 时，`animateItem()` 的 appear 动画和 `AnimatedContent` 的 `fadeIn` 叠加运行，增强嫌疑人 1 的视觉影响。

### 嫌疑人 3（低可能）：sharedElement morph 回退

`sharedElement("task_detail_$taskId")` 让详情 Surface 缩小回卡片位置。但这是每帧都会有的正常过渡，不是间歇性的。

### 为什么 `remember` 不保护我们

直觉上 `remember` 应该在 composition 存活期间保留状态。但关键问题是：

- `AnimatedContent` 在过渡完成后会**彻底销毁**退出分支的 composition tree
- 打开详情后，列表在 `fadeOut(250ms)` 完成后被 dispose
- 关闭详情时，列表重新进入 composition → 所有 `remember` 都是**全新实例**

## 修复方向

1. `TaskCard.kt:168-174`：`LaunchedEffect` 增加首次 composition 守卫，只在 `isDone` 真正从 false 变为 true 时才播放动画
2. `TaskScreen.kt:1512`：移除已完成列表的 `animateItem()`（已完成 item 不需要排序动画）
