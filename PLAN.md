# AI 陪伴功能增强计划

## 总览

为 Nion 增加 3 项 AI 陪伴功能，从简到难依次实现：

| # | 功能 | 核心思路 | 状态 |
|---|------|---------|------|
| 1 | 专注完成鼓励 | 完成专注时 LLM 根据任务/时长/历史生成鼓励 | ✅ 已完成 |
| 2 | 每晚互动回顾 | 每晚 AI 问"今天怎么样？"，用户可回复 | 待实现 |
| 3 | 专注分心拦截 | 专注期间检测抖音等 App，弹悬浮窗劝回 | 待实现 |

---

## Feature 1: 专注完成鼓励（已完成）

**目标**: 用户完成番茄钟后，LLM 根据任务名、专注时长、历史累计、今日统计等多维度数据，生成个性化鼓励文案，底部卡片动画展示，4 秒自动消失。

### 已实现文件

**新建文件（4 个）：**
- `app/.../focus/CompletionEvent.kt` — 专注完成事件数据类
- `app/.../focus/CompletionMotivator.kt` — LLM 调用 + ToolPhrasePool fallback
- `app/.../focus/CompletionOverlay.kt` — 底部悬浮鼓励卡片 UI

**修改文件（7 个）：**
- `ToolPhrasePool.kt` — 新增 focus_complete / focus_interrupted 操作类型（6 性格 × 2 类型）
- `PromptDefaults.kt` — 新增 KEY_FOCUS_COMPLETE / KEY_FOCUS_INTERRUPTED prompt 模板
- `NionApp.kt` (Application) — 新增 completionEvents SharedFlow 事件总线
- `NionApp.kt` (Composable) — 新增 CompletionOverlay 全局悬浮卡片
- `FocusTimerViewModel.kt` — 自然完成/中断(≥5min)时收集数据并 emit CompletionEvent
- `CompanionViewModel.kt` — focusCompletionEnabled 状态 + prompt 加载/保存
- `CompanionSidebar.kt` — "提醒设定"区域新增"专注"开关卡片 + "专注中断"提示词卡片

---

## Feature 2: 每晚互动回顾

**目标**: 每晚 22:00（可自定义），AI 收集当日数据，主动问"今天怎么样？"。用户可在 Overlay 中回复，回复后自动打开陪伴面板继续聊。

### 设计细节

- 复用现有 `GreetingScheduler → GreetingWorker → OverlayDispatcher → GreetingOverlay` 链路
- 新增第 4 种 greeting 类型：`"review"`
- review 类型的 Overlay 带输入框，用户可回复
- 回复后：将 AI 回顾 + 用户回复写入当前对话 → 打开陪伴面板
- 未回复：仅将 AI 回顾写入对话，下次打开面板能看到
- Prompt 风格：温暖总结 + 开放式提问，不用 Markdown

### 数据收集（在 GreetingWorker 中）

```
- 今日完成任务列表（含名称和完成时间）
- 今日待办任务
- 今日专注总时长
- 分心次数（Feature 3 完成后接入）
- 天气信息（复用已有）
```

### 修改文件

- **`GreetingScheduler.kt`**
  - `rescheduleAll()` 中新增 `review` 类型
  - 新增 `scheduleReview()` 方法
  - review 默认时间 22:00

- **`GreetingWorker.kt`**
  - 新增 `review` 分支
  - 收集当日完成数据（任务名、专注时长等）
  - 调用 LLM 生成回顾开场白
  - 通过 `OverlayDispatcher` 展示
  - 写入对话历史

- **`GreetingOverlay.kt`**
  - 新增 `isReview` 判断：`greetingType == "review"` 时
  - 显示 `TextField` 输入框
  - "回复"按钮 → 写入对话 + 打开陪伴面板
  - "稍后再说"按钮 → 仅写入对话，不打开面板
  - 输入框样式：圆角卡片内嵌，hint "跟{name}聊聊今天..."

- **`GreetingFloatingService.kt`**
  - review 类型在后台也显示带输入框的悬浮窗
  - 需调整 Compose 布局容纳 TextField

- **`PromptDefaults.kt`**
  - 新增 `KEY_GREETING_REVIEW` = `"prompt_greeting_review"`
  - 默认 Prompt 内容：
    ```
    你是{name}，用户的 AI 伴侣。现在是每晚回顾时间。
    以下是用户今天的任务完成情况：
    {taskSummary}
    专注时长：{focusTime}
    请用温暖语气总结今天的成就，然后用一句开放式提问问用户今天感觉怎么样。
    2-3句话，不使用Markdown，像朋友聊天一样自然。
    ```

- **`GreetingEvent.kt`**
  - `getTitle()` 新增 `"review"` → `"每日回顾"` 映射

- **`CompanionViewModel.kt`**
  - 新增 `injectReviewMessage(aiText: String, userReply: String?)` 方法
  - 将 AI 回顾作为 AI 消息写入当前对话
  - 用户回复作为 user 消息写入
  - 触发 `openCompanionPanel` 事件

- **`NionApp.kt`**（Application + Composable）
  - Application: 注册 review 事件总线
  - Composable: 监听 `openCompanionPanel` 事件，review 回复后自动展开陪伴侧栏

---

## Feature 3: 专注分心拦截

**目标**: 专注期间检测用户切到干扰 App（抖音/快手等），弹出悬浮窗劝回，带倒计时防秒关，AI 生成个性化劝回文案。

### 权限要求

此功能需要 **`PACKAGE_USAGE_STATS`（查看使用情况）** 权限，这是一个**特殊权限**，无法通过运行时弹窗授权，必须引导用户手动操作：

```
系统设置 → 安全 → 使用情况访问 → 找到 Nion → 允许
```

**权限特性说明：**

- `PACKAGE_USAGE_STATS` 属于 AppOps 特殊权限，不在普通运行时权限体系中
- 用户必须手动去系统设置页开启，App 只能引导跳转
- 需在 `AndroidManifest.xml` 中声明：`<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />`
- 检测权限是否已授予：`UsageStatsManager` 或 AppOpsManager 方式
- 引导跳转：`Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)`

**权限引导 UI 设计（FocusScreen 中）：**

```
专注开始前检查流程：
1. 检查 SYSTEM_ALERT_WINDOW（悬浮窗权限，专注弹窗需要）—— 已有
2. 检查 PACKAGE_USAGE_STATS（使用情况访问，分心检测需要）—— 新增
3. 两者都满足 → 正常开始专注
4. 缺少权限 → 显示引导卡片：
   ┌────────────────────────────────────┐
   │  🔒 开启分心保护                    │
   │                                    │
   │  专注期间检测你是否切到其他 App，    │
   │  及时提醒你回来。                   │
   │                                    │
   │  需要授予「使用情况访问」权限：      │
   │  设置 → 安全 → 使用情况访问 → Nion  │
   │                                    │
   │  [去设置开启]    [暂不开启，直接专注] │
   └────────────────────────────────────┘
```

- "去设置开启" → `startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))`
- "暂不开启，直接专注" → 跳过权限，专注正常运行但不检测分心
- 权限状态在 `onResume` 中重新检查（用户从设置返回后自动刷新）

### 设计细节

- 专注开始时启动前台 Service 保活计时器 + 轮询检测
- 用 `UsageStatsManager.queryEvents()` 每 1.5 秒检测一次前台 App
- 命中干扰名单 → 弹悬浮窗（SYSTEM_ALERT_WINDOW，已有权限）
- 悬浮窗内容：AI 文案 + 专注进度 + 3 秒倒计时 + "返回专注"按钮
- 统计分心次数，专注结束后在回顾数据中体现
- **如果没有 PACKAGE_USAGE_STATS 权限**：专注功能正常可用，只是没有分心检测保护

### 预设干扰 App 名单

```
抖音:      com.ss.android.ugc.aweme
快手:      com.smile.gifmaker
微博:      com.sina.weibo
B站:       tv.danmaku.bili
小红书:    com.xingin.xhs
微信:      com.tencent.mm（默认关闭，用户可选）
QQ:        com.tencent.mobileqq（默认关闭，用户可选）
王者荣耀:  com.tencent.tmgp.sgame
和平精英:  com.tencent.tmgp.pubgmhd
原神:      com.miHoYo.Yuanshen
```

- 分类标签：社交媒体 / 游戏 / 视频 / 通讯
- 用户可自定义增删（存储在 `distracting_apps` setting）
- 默认开启：社交媒体 + 游戏 + 视频
- 默认关闭：通讯类（微信/QQ）

### 新建文件

- **`app/.../focus/FocusForegroundService.kt`**
  - 仿 `ReminderFloatingService` 模式
  - `onStartCommand`: 接收 taskTitle, totalSeconds, companionStyle
  - `startForeground()` 低优先级通知（"正在专注..."，带进度更新）
  - 内部维护倒计时（不依赖 ViewModel 协程）
  - 启动 `DistractionDetector` 轮询（仅在 PACKAGE_USAGE_STATS 已授权时）
  - 检测到分心 → 调用 `showDistractionOverlay()`
  - 专注结束/停止时 `stopSelf()`
  - 与 `FocusTimerViewModel` 通过 callback/broadcast 同步状态

- **`app/.../focus/DistractionDetector.kt`**
  - 单例 `object`
  - 封装 `UsageStatsManager.queryEvents()` 轮询
  - `startPolling(intervalMs: Long = 1500)`: 协程循环查询
  - 检测 `MOVE_TO_FOREGROUND` 事件的 `packageName`
  - 对比干扰名单（从设置读取）
  - 命中时回调 `onDistractionDetected(packageName, appLabel)`
  - `stopPolling()` 暂停
  - `isUsageAccessGranted(context): Boolean` 静默检查权限
    ```kotlin
    // 检测方式：尝试查询最近事件，无权限时返回空列表
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val events = usageStatsManager.queryEvents(now - 1000, now)
    // 如果有权限，events.hasNextEvent() 会返回 true（至少有自己 App 的事件）
    // 无权限时，queryEvents 返回空 UsageEvents
    ```
  - `requestUsageAccess(context)` 跳转系统设置页：
    ```kotlin
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    ```

- **`app/.../focus/DistractionOverlay.kt`**
  - Compose 悬浮窗 UI：
    - 陪伴头像 + AI 劝回文案
    - 专注进度条（"已专注 13 分钟 / 25 分钟"）
    - 3 秒倒计时后才可关闭（防秒关），倒计时中按钮灰色
    - "返回专注"按钮 → Intent 跳回 Nion MainActivity
    - 温暖语气，不指责
  - 两种文案来源：
    1. LLM 生成（调用 `ReminderLlmClient.chatSimple()`，传入当前专注进度 + 干扰 App 名）
    2. 模板 fallback（`ToolPhrasePool` 新增 `DISTRACTION` 类型）

- **`app/.../focus/DistractingApps.kt`**
  - `data class AppInfo(packageName: String, label: String, category: AppCategory, enabled: Boolean)`
  - `enum class AppCategory { SOCIAL, GAME, VIDEO, COMMUNICATION }`
  - 预设名单常量 `DEFAULT_DISTRACTING_APPS`
  - `loadDistractingApps(context): List<AppInfo>` 从设置读取
  - `saveDistractingApps(context, apps)` 保存到设置
  - `isDistracting(context, packageName): Boolean` 检查

### 修改文件

- **`FocusTimerViewModel.kt`**
  - `start()` 时启动 `FocusForegroundService`
  - `stopEarly()` / 自然完成时停止 Service
  - 新增 `distractionCount: Int` 状态
  - 新增 `distractionEvents: List<String>` 记录（App 名 + 时间）
  - 接收 Service 上报的分心数据
  - 专注结束时将分心数据传给持久化层

- **`AndroidManifest.xml`**
  - 添加 `android.permission.PACKAGE_USAGE_STATS` 权限
  - 注册 `FocusForegroundService`（`foregroundServiceType="specialUse"`）
  - 权限声明需加 `tools:ignore="ProtectedPermissions"` 抑制 lint 警告

- **`FocusScreen.kt`**
  - 专注开始前检查 `PACKAGE_USAGE_STATS` 权限
  - 未授权时显示引导卡片（见上方「权限引导 UI 设计」）
  - 权限状态在 `onResume` 中重新检查（用户从系统设置返回后自动刷新）
  - 设置图标入口 → 干扰 App 名单管理页面
  - 新增干扰 App 管理页面（可复用 Composable）：
    - 分类展示预设 App（开关切换）
    - "添加自定义 App"入口（输入包名或从已安装列表选择）
    - 删除自定义 App

- **`PromptDefaults.kt`**
  - 新增 `KEY_DISTRACTION` = `"prompt_distraction"`
  - Prompt 内容：
    ```
    你是{name}，用户的 AI 伴侣。用户正在专注完成任务「{taskName}」，
    已专注{elapsed}分钟，还剩{remaining}分钟。
    但用户现在打开了{appName}，请用{style}的语气温柔地劝用户回来继续专注。
    1句话，不超过30个字，不要说教，像朋友一样轻松提醒。
    ```

- **`ToolPhrasePool.kt`**
  - 新增 `DISTRACTION` 操作类型
  - 6 种性格 × 5 条劝回模板（LLM fallback 用）

- **`NionApp.kt`**（Application）
  - 无需修改，专注 Service 自成体系

---

## 实施顺序

```
Phase 1 → 任务完成激励（最简单）
  ├─ 扩展 ToolPhrasePool（COMPLETION + COMPLETION_STREAK 类型）
  ├─ NionApp Application 添加 completionEvents 事件总线
  ├─ 新建 CompletionMotivator
  └─ NionApp Composable 添加 CompletionOverlay

Phase 2 → 每晚互动回顾（中等）
  ├─ 扩展 GreetingScheduler（review 类型）
  ├─ 扩展 GreetingWorker（review 分支）
  ├─ 扩展 PromptDefaults（review prompt）
  ├─ 改造 GreetingOverlay（输入框）
  ├─ 改造 GreetingFloatingService（输入框）
  ├─ GreetingEvent 新增 review 标题
  └─ CompanionViewModel 注入回顾消息

Phase 3 → 专注分心拦截（最复杂）
  ├─ Manifest 声明 PACKAGE_USAGE_STATS 权限 + FocusForegroundService
  ├─ 新建 DistractingApps（干扰名单管理）
  ├─ 新建 DistractionDetector（UsageStats 轮询 + 权限检查）
  ├─ 新建 FocusForegroundService（前台保活 + 调度 DistractionDetector）
  ├─ 新建 DistractionOverlay（悬浮窗 UI）
  ├─ 改造 FocusTimerViewModel（联动 Service）
  ├─ 改造 FocusScreen（权限引导 UI + 名单管理页）
  ├─ 扩展 ToolPhrasePool（DISTRACTION 类型）
  └─ 扩展 PromptDefaults（distraction prompt）
```

---

## 注意事项

- 所有新增代码必须写详细中文注释（每函数/Composable 注释用途，复杂逻辑行内注释）
- 不要修改非本次任务范围的代码（TaskScreen.kt / TaskCard.kt 的预存编译错误不碰）
- Phase 3 需要在真机上测试（模拟器可能没有抖音等 App，且 UsageStats 行为可能不同）
- `PACKAGE_USAGE_STATS` 是特殊权限，无法运行时弹窗授权，必须引导用户去系统设置页手动开启
  - 引导 UI 需包含：权限用途说明 + 跳转按钮 + 跳过选项（不强制）
  - `onResume` 中重新检查权限状态，用户从设置返回后自动刷新
- 专注前台 Service 须在 Manifest 声明 `foregroundServiceType="specialUse"`
- 分心检测为可选功能：无 `PACKAGE_USAGE_STATS` 权限时专注正常可用，只是没有分心保护
