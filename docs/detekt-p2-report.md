# P2 代码质量报告 —— 函数拆分与复杂度优化

> 由 detekt 2.0.0-alpha.3 检测，共 87 个 P2 级别问题。
> 本报告按文件分组，列出每个需要拆分的巨型函数及其建议拆分方案。

---

## 优先级定义

- **严重 (复杂度 > 100)**：强烈建议拆分，维护成本极高
- **中等 (复杂度 50-100)**：建议拆分，提升可维护性
- **轻微 (复杂度 25-50)**：可选优化，影响较小

---

## 1. SharedTaskList.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/components/SharedTaskList.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `SharedTaskList` | 394 | 407 | 88 |

**建议拆分方案**:
- 抽取拖拽排序逻辑为独立的 `DragReorderHandler` 类或顶层函数
- 抽取长按/点击事件处理为独立函数
- 抽取子任务折叠/展开逻辑为 `SubtaskCollapseManager`
- 将 `LazyColumn` 的 `item` 内容块提取为独立的 `TaskItem` Composable

---

## 2. TaskDetailOverlay.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/components/TaskDetailOverlay.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `TaskDetailOverlay` | 595 | 170 | 26 |

**建议拆分方案**:
- 拆分为 `TaskDetailHeader`、`TaskDetailBody`、`TaskDetailActions` 三个子 Composable
- 将附件、提醒、重复等设置面板各自提取为独立 Composable
- 将状态栏（优先级选择等）提取为 `TaskStatusBar` Composable

---

## 3. ApiProviderSetup.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/ApiProviderSetup.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `ApiProviderSetup` | 384 | 193 | 39 |

**建议拆分方案**:
- 按 API 提供商拆分：`OpenAiConfigSection`、`AnthropicConfigSection`、`OllamaConfigSection`、`CustomConfigSection`
- 抽取通用的 `ApiFieldInput` Composable
- 抽取模型选择逻辑为 `ModelSelector`

---

## 4. FocusScreen.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/focus/FocusScreen.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `FocusScreen` | 324 | 159 | 33 |
| `TaskPanelOverlay` | 150 | 77 | - |
| `HierarchicalTaskCard` | - | 50 | - |

**建议拆分方案**:
- `FocusScreen` → 拆分为 `FocusTimerDisplay`、`FocusControlBar`、`FocusBackground`
- `TaskPanelOverlay` → 拆分为 `TaskSearchBar`、`TaskSearchResults`
- `HierarchicalTaskCard` → 将展开/折叠子任务逻辑提取为 `SubtaskList`

---

## 5. NionApp.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/NionApp.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `NionApp` | 328 | 87 | 23 |

**建议拆分方案**:
- 按导航路由拆分：将每个 `composable("xxx") { }` 块提取为独立的 Composable
- 抽取 `NavigationGraph` Composable，将所有路由注册逻辑从 `NionApp` 移出
- 抽取 `DualPanelScaffold` Composable 处理双面板布局逻辑

---

## 6. SettingsScreen.kt — 严重

**文件**: `app/app/src/main/java/com/echonion/nion/ui/settings/SettingsScreen.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `SettingsScreen` | 363 | 145 | 25 |
| `LocationCard` | 183 | 59 | - |
| `CustomThemeItem` | 128 | 30 | - |

**建议拆分方案**:
- `SettingsScreen` → 拆分为 `ThemeSection`、`CompanionSection`、`FocusSection`、`NotificationSection`、`LocationSection`、`AboutSection`
- `LocationCard` → 拆分为 `LocationSearchBar`、`LocationResultList`
- `CustomThemeItem` → 拆分为 `ColorPickerRow`、`ThemePreviewChip`

---

## 7. MarkdownText.kt — 中等

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/MarkdownText.kt`

| 函数 | 认知复杂度 | 圈复杂度 |
|------|-----------|---------|
| `parseInline` | 70 | 26 |
| `parseBlocks` | 48 | 38 |

**建议拆分方案**:
- `parseInline` → 按语法类型拆分：`parseBoldItalic`、`parseCode`、`parseLink`、`parseSticker`
- `parseBlocks` → 按块类型拆分：`parseCodeBlock`、`parseListBlock`、`parseChecklistBlock`

---

## 8. ChatService.kt — 中等

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/ChatService.kt`

| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `chatStreamAnthropic` | 138 | 71 | 35 |
| `chatStreamOpenAI` | - | 63 | 36 |

**建议拆分方案**:
- 抽取 SSE 解析逻辑为 `SseParser` 工具类
- 抽取工具调用处理为 `ToolCallHandler`
- 抽取流式 token 处理为 `StreamingTokenProcessor`
- `ChatService` 类本身过大 (LargeClass)，可考虑按职责拆分

---

## 9. CompanionSidebar.kt — 中等

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/CompanionSidebar.kt`

| 函数 | 行数 | 认知复杂度 |
|------|------|-----------|
| `ProfileContent` | 321 | 83 |
| `MessageBubble` | 160 | 46 |
| `ExpandableReminderCard` | 160 | 46 |
| `ChatContent` | 255 | 44 |
| `PreferencesPanel` | 174 | 44 |
| `ExpandablePromptCard` | - | 38 |
| `MemoriesPanel` | 145 | 38 |
| `CompanionSidebar` | 132 | - |

**建议拆分方案**:
- `ProfileContent` → 拆分为 `GreetingCards`、`ReminderCards`、`FocusCards`、`CompanionStyleSection`
- `MessageBubble` → 拆分为 `ToolMessageBubble`、`TextMessageBubble`、`UserMessageBubble`
- `ChatContent` → 拆分为 `ChatInputBar`、`ChatMessageList`、`ChatHeader`
- `PreferencesPanel` / `MemoriesPanel` → 拆分列表项为独立的 `PreferenceItem` / `MemoryItem`

---

## 10. TaskViewModel.kt — 中等

**文件**: `app/app/src/main/java/com/echonion/nion/ui/task/TaskViewModel.kt`

| 问题 | 详情 |
|------|------|
| LargeClass | 类过大 |
| TooManyFunctions | 28 个函数（上限 20） |

**建议拆分方案**:
- 抽取任务排序逻辑为 `TaskReorderHelper`
- 抽取提醒/重复相关逻辑为 `TaskReminderManager`
- 抽取拖拽后状态同步逻辑为 `TaskSyncHelper`

---

## 11. 其他中等/轻微问题

### ChatService.kt — 类过大
| 问题 | 建议 |
|------|------|
| `ChatService` 类过大 | 考虑按 API 提供商拆分为 `OpenAiChatClient`、`AnthropicChatClient` |

### CompanionViewModel.kt
| 函数 | 行数 | 圈复杂度 |
|------|------|---------|
| `loadSettings` | 155 | 35 |
| 类过大 + 30 个函数 | - | - |

**建议**: 将 `loadSettings` 的 RawData 解析和默认值填充拆分为 `SettingsParser` 工具

### TaskScreen.kt
| 函数 | 行数 | 认知复杂度 |
|------|------|-----------|
| `TaskScreen` | 226 | 58 |
| `AddTaskOverlay` | 274 | - |

### Sidebar.kt
| 函数 | 行数 | 认知复杂度 |
|------|------|-----------|
| `SidebarContent` | 252 | 59 |

### ReminderTimePicker.kt
| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `ReminderTimePicker` | 324 | 91 | 28 |

### ReminderOverlay.kt
| 函数 | 行数 | 认知复杂度 |
|------|------|-----------|
| `ReminderOverlay` | 247 | 29 |

### DatePickerRow.kt
| 函数 | 行数 | 认知复杂度 |
|------|------|-----------|
| `NionCalendar` | 211 | 53 |
| `WheelSpinner` | - | 34 |
| `CalendarMonthGrid` | - | 46 |

### SharedTaskCard.kt
| 函数 | 行数 | 认知复杂度 | 圈复杂度 |
|------|------|-----------|---------|
| `SharedTaskCard` | 157 | 80 | 26 |

### 其他轻微问题
| 文件 | 函数 | 行数 | 认知复杂度 |
|------|------|------|-----------|
| `FloatingReminderCard` (ReminderFloatingService.kt) | 179 | - |
| `FocusStatsPanel` (FocusStatsPanel.kt) | 147 | 30 |
| `RecurrenceSelector` (RecurrenceSelector.kt) | 156 | - |
| `CompletionOverlay` (CompletionOverlay.kt) | 157 | - |
| `GreetingOverlay` (GreetingOverlay.kt) | 140 | - |
| `WeatherAlertOverlay` (WeatherAlertOverlay.kt) | 144 | - |
| `CalendarMonthGrid` (ScheduleScreen.kt) | - | 44 |
| `ScheduleScreen` (ScheduleScreen.kt) | - | 26 |

### LargeClass / TooManyFunctions
| 类 | 函数数 | 建议 |
|----|--------|------|
| `ToolPhrasePool` | 类过大 | 按角色风格拆分为独立文件 |
| `CompanionViewModel` | 30 个函数 | 拆分出 SettingsHelper、PromptManager |

---

## 拆分原则

1. **不破坏 SharedTransition 动画**：拆分子 Composable 时，确保 `sharedTransitionScope` 正确传递
2. **保持 state hoisting 模式**：子 Composable 通过参数接收状态，通过回调通知变更
3. **优先拆分独立的 UI 区块**：如设置面板中的各个 Section
4. **复杂逻辑先抽函数再抽类**：先在同级文件中提取 private 函数，确认边界后再考虑独立文件
