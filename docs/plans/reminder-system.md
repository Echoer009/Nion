# 后台提醒系统实现计划

> 日期：2026-05-26
> 状态：实施中

---

## 需求

- 任务设置了提醒时间后，到点时无论 App 在前台/后台/锁屏，都要弹出提醒
- 每日循环任务：每天固定 HH:MM 提醒（已有 `recurrence_reminder_time` 字段 + UI）
- 普通任务：一次性提醒，选择"日期 + 时间"（复用现有 `NionCalendar` + `WheelSpinner`）
- 前台时：应用内底部弹窗（BottomSheet），支持"稍后提醒"（5/10/30分钟）
- 后台时：系统通知栏通知，点击打开 App

---

## 现有基础

### 已有数据字段（Rust 端）
- `TaskData.reminder: Option<String>` — 一次性提醒时间（RFC 3339），`create_task` 中固定为 `None`，从未被 UI 写入
- `TaskData.recurrence_reminder_time: Option<String>` — 每日循环提醒时间（"HH:MM"），UI 已支持
- `TaskData.due_date: Option<String>` — 截止日期

### 已有 UI 组件（Kotlin 端）
- `WheelSpinner`（`DatePickerRow.kt:577`）— 上下滑动滚轮选择器，可复用于时间选择
- `NionCalendar`（`DatePickerRow.kt:310`）— 自定义日历组件，可复用于日期选择
- `DatePickerRow` / `NionDatePickerDialog`（`DatePickerRow.kt:80/124`）— 日期选择入口
- `RecurrenceSelector`（`RecurrenceSelector.kt`）— 每日循环 + HH:MM 滚轮，已完整

### 完全缺失的部分
- Android 权限（`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`USE_EXACT_ALARM`）
- AlarmManager 闹钟调度
- BroadcastReceiver（闹钟触发 + 开机重调度）
- 通知系统（NotificationChannel + Notification）
- 应用内全局提醒弹窗
- 普通任务的一次性提醒 UI 入口
- 运行时权限请求

---

## 实施步骤

### 步骤 1: AndroidManifest.xml 权限 + Receiver 注册

```xml
+ <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+ <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
+ <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
+ <receiver android:name=".reminder.ReminderReceiver" android:exported="false" />
+ <receiver android:name=".reminder.BootReceiver" android:exported="true">
+     <intent-filter>
+         <action android:name="android.intent.action.BOOT_COMPLETED" />
+     </intent-filter>
+ </receiver>
```

### 步骤 2: NotificationHelper.kt（新建）

位置：`app/app/src/main/java/com/echonion/nion/reminder/NotificationHelper.kt`

- `createChannel(context)` — 创建 "task_reminders" 通知渠道（IMPORTANCE_HIGH）
- `showReminderNotification(context, taskId, title)` — 发送系统通知
  - 点击通知 → PendingIntent 打开 MainActivity，携带 taskId
  - 通知内容：任务标题 + "该任务提醒时间到了"
- `dismissNotification(context, taskId)` — 取消指定通知

### 步骤 3: ReminderScheduler.kt（新建）

位置：`app/app/src/main/java/com/echonion/nion/reminder/ReminderScheduler.kt`

AlarmManager 精确闹钟调度器，单例模式：

- `scheduleExactReminder(context, taskId, triggerMillis)`
  - 使用 `AlarmManager.setExactAndAllowWhileIdle()` 调度一次性闹钟
  - Intent 携带 taskId + type="exact"
- `scheduleDailyReminder(context, taskId, hour, minute)`
  - 计算今天/明天该时刻的 millis，调度一次性闹钟
  - 触发后由 Receiver 自动调度下一天
- `cancelReminder(context, taskId)` — 取消该任务的所有闹钟
- `rescheduleAll(context, core)` — 启动/开机时：
  - 查所有有 `reminder` 且时间在未来的普通任务 → 调度一次性
  - 查所有 `recurrence_rule='daily'` 且有 `recurrence_reminder_time` 的任务 → 调度每日

调度策略：
- Android 12+（API 31+）使用 `setExactAndAllowWhileIdle()`
- 每日任务不使用 `setRepeating`（不精确），而是每次触发后手动调度下一天
- Intent 使用 `PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE`
- 区分 exact/daily 通过 Intent extra `type` 字段

### 步骤 4: ReminderReceiver + BootReceiver（新建）

位置：`app/app/src/main/java/com/echonion/nion/reminder/`

**ReminderReceiver：**
- 接收 Intent（携带 taskId, type）
- 通过 NotificationHelper 发系统通知
- 如果 App 进程存活，通过 `NionApp.reminderEvents` 发 `ReminderEvent` 到 UI 层
- 如果 type="daily"：计算并调度明天的闹钟

**BootReceiver：**
- 监听 `BOOT_COMPLETED`
- 调用 `ReminderScheduler.rescheduleAll()` 重建所有闹钟

### 步骤 5: ReminderOverlay.kt（新建）

位置：`app/app/src/main/java/com/echonion/nion/ui/task/ReminderOverlay.kt`

全局提醒弹窗 Composable，放在 `NionApp.kt` 导航层之外：

- 监听 `NionApp.reminderEvents: SharedFlow<ReminderEvent>`
- 收到事件时弹出 BottomSheet：
  - 显示任务标题 + 提醒时间
  - "完成" 按钮 → 标记任务完成
  - "稍后提醒" 按钮 → 弹出 5分钟/10分钟/30分钟选项，选中后重新调度
  - "关闭" 按钮 → 关闭弹窗
- BottomSheet 使用 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`

### 步骤 6: NionApp.kt + TaskViewModel.kt 集成

**NionApp.kt 修改：**
- 新增 `reminderEvents: SharedFlow<ReminderEvent>` 事件总线
- 新增 `fun postReminderEvent(event: ReminderEvent)` 方法
- `onCreate()` 中调用 `NotificationHelper.createChannel()` + `ReminderScheduler.rescheduleAll()`

**TaskViewModel.kt 修改：**
- `createTask()` 成功后：如果有 reminder/daily，调度闹钟
- `updateRecurrence()` 后：重新调度/取消每日闹钟
- `updateReminder()` (新增)：设置一次性提醒后调度闹钟
- `deleteTask()` 后：取消该任务闹钟

### 步骤 7: ReminderTimePicker.kt（新建）

位置：`app/app/src/main/java/com/echonion/nion/ui/task/ReminderTimePicker.kt`

普通任务的一次性提醒选择器，**复用现有组件**：

- 复用 `NionCalendar` — 日期选择（点击日期后展开时间选择）
- 复用 `WheelSpinner` — HH:MM 时间滚轮（与每日提醒同一套）
- 选择完成后拼接为 `"YYYY-MM-DDTHH:MM"` 格式
- 提供"清除提醒"按钮
- 在任务编辑表单（BottomSheets.kt 或任务详情页）中添加入口

UI 交互流程：
1. 任务编辑区显示"提醒"行（带闹钟图标）
2. 点击后展开：先选日期（日历），再选时间（滚轮）
3. 确认后写入 `TaskData.reminder`

### 步骤 8: 运行时权限请求

- 在 `NionApp.kt` 的 `MainActivity` 中，首次启动时检查 `POST_NOTIFICATIONS` 权限
- Android 13+（API 33+）需要运行时请求
- 用户拒绝后，在设置页面提供"开启通知"入口引导

---

## 数据流

```
用户设置提醒 → TaskViewModel → Rust core 写 DB + ReminderScheduler 调度闹钟
                                                   ↓
                                          AlarmManager 到点触发
                                                   ↓
                                      ReminderReceiver.onReceive()
                                        ↙                    ↘
                            App在前台                      App在后台
                        NionApp.postReminderEvent     NotificationHelper
                                ↓                        .showNotification()
                        ReminderOverlay                   ↓
                      (BottomSheet弹窗)             系统通知栏
                      完成/稍后/关闭                 点击→打开App
```

## 文件清单

| 新建/修改 | 文件路径 | 说明 |
|-----------|---------|------|
| 修改 | `AndroidManifest.xml` | 权限 + Receiver |
| 新建 | `reminder/NotificationHelper.kt` | 通知渠道 + 发通知 |
| 新建 | `reminder/ReminderScheduler.kt` | 闹钟调度 |
| 新建 | `reminder/ReminderReceiver.kt` | 闹钟触发处理 |
| 新建 | `reminder/BootReceiver.kt` | 开机重调度 |
| 新建 | `ui/task/ReminderOverlay.kt` | 全局提醒弹窗 |
| 新建 | `ui/task/ReminderTimePicker.kt` | 一次性提醒选择器 |
| 修改 | `NionApp.kt` | 事件总线 + 初始化 |
| 修改 | `TaskViewModel.kt` | 调度集成 |
