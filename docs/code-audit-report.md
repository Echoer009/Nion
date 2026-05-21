# Nion 代码质量审计报告

**日期：** 2026-05-21
**范围：** 全项目（Rust core + Kotlin UI）
**文件数：** 20（18 Kotlin, 1 Rust, 1 Shell）

---

## CRITICAL（3个）

### C1. 重复 NionCore / SQLite 连接

- **文件：** `NionApp.kt:62`, `TaskViewModel.kt:155-159`
- **问题：** `NionApp` 创建一个 `NionCore`，`TaskScreen → taskViewModel() → rememberNionCore()` 创建第二个。两个独立 SQLite 连接写同一个文件。
- **风险：** 数据库锁冲突、数据不一致。
- **修复：** 单例 NionCore，通过 Application 子类或依赖注入共享。

### C2. FFI 调用零错误处理

- **文件：** `TaskViewModel.kt` 全部 `core.*` 行、`NionApp.kt:64,159`
- **问题：** 所有 Rust FFI 调用都可能抛 `NionError`，但没有任何 try/catch。
- **风险：** 磁盘满、数据库损坏、锁冲突 = 直接崩溃。
- **修复：** 所有 `core.*` 调用包裹 try/catch，错误时显示 Toast 或降级。

### C3. 主线程同步 SQLite I/O

- **文件：** `TaskViewModel.kt:init`, `NionApp.kt:64`
- **问题：** 所有数据库操作在 Compose 主线程同步执行。
- **风险：** 数据量大时 UI 卡顿甚至 ANR。
- **修复：** 使用 `viewModelScope.launch(Dispatchers.IO)`。

---

## HIGH（4个）

### H1. N+1 递归查询

- **文件：** `TaskViewModel.kt:86-97`
- **问题：** `loadTasksWithSubtasks` 对每个任务调 `getSubtasks()`，子任务再递归。20 任务 × 5 子任务 = 120 次查询。
- **修复：** Rust 端用递归 CTE 或 JOIN 一次查完，返回带层级的数据。

### H2. 每次变更全量重载

- **文件：** `TaskViewModel.kt:99-117`
- **问题：** `createTask`/`toggleDone`/`deleteTask` 后都调完整递归 `loadTasksWithSubtasks`。
- **修复：** 只更新变更的项（如 toggleDone 只改本地状态 + 发异步写入）。

### H3. Focus/Companion/Schedule 没有 ViewModel

- **文件：** `FocusScreen.kt`, `CompanionScreen.kt`, `ScheduleScreen.kt`
- **问题：** 全用 composable 本地状态，旋转屏幕/进程死亡数据全丢。
- **修复：** 添加 ViewModel，至少 FocusScreen 的计时器状态需要 `SavedStateHandle`。

### H4. FocusScreen 计时器实现有性能问题

- **文件：** `FocusScreen.kt:100-108`
- **问题：** `LaunchedEffect(isRunning, remainingSeconds)` 每秒因 key 变化取消并重建协程。
- **修复：** 用一个 `while(isRunning)` 循环的单一协程。

---

## MEDIUM（5个）

### M1. 魔数泛滥

- **范围：** 全项目
- **示例：** 动画时长 `250`/`300`/`120`、透明度 `0.3f`/`0.5f`、尺寸 `280.dp`/`260.dp`/`88.dp`、阈值 `0.80f`/`0.20f`。
- **修复：** 抽取为命名常量（如 `AnimationSpecs.kt`、`Dimensions.kt`）。

### M2. 重复的 OutlinedTextField 样式

- **文件：** `BottomSheets.kt` 出现 4 次，`CompanionScreen.kt` 1 次
- **修复：** 抽成 `NionTextField` 公共组件。

### M3. Priority 是裸字符串

- **范围：** `TaskUtils.kt`、`PrioritySelector.kt`、`TaskViewModel.kt`、`BottomSheets.kt`
- **问题：** `"high"`/`"medium"`/`""low"` 到处传，拼写错误无编译期检查。
- **修复：** `enum class Priority(val label: String, val color: Color)`。

### M4. Sidebar 拖拽逻辑重复

- **文件：** `Sidebar.kt:66-70`, `TaskScreen.kt:100-115`
- **问题：** Sidebar 和主内容区各有一套独立的拖拽开/关逻辑。
- **修复：** 抽取 `SidebarState` 类封装动画和阈值。

### M5. Rust `reorder_tasks` 没有 transaction

- **文件：** `lib.rs:351-377`
- **问题：** N 条 UPDATE 不在事务里，O(N) 次单独锁获取。
- **修复：** 包裹在 `conn.execute("BEGIN", [])` / `COMMIT` 中。

---

## LOW（6个）

| # | 问题 | 文件 |
|---|------|------|
| L1 | `greet()` 死函数（hello-world 残留） | `lib.rs:379-381` |
| L2 | `Uuid as UuidCrate` 无意义 alias | `lib.rs:5` |
| L3 | `Serialize/Deserialize` derive 了但没序列化 | `lib.rs:9-17` |
| L4 | `Orange*` 系列颜色常量没引用 | `Color.kt` |
| L5 | `NionApp` Application 类整个是空的 | `NionApp.kt` |
| L6 | `SubtaskList` 的 `depth` 参数传了但从未读取 | `TaskCard.kt:186` |
| L7 | `build-android.sh` 硬编码 NDK 版本/路径 | `build-android.sh` |

---

## 修复优先级建议

1. **C1 单例 NionCore** → 所有后续修复的基础
2. **C2 try/catch** → 防崩溃
3. **C3 IO 线程** → 和 C1 一起做
4. **H1 N+1 查询** → Rust 端改，需要重新 build-android.sh
5. **H2 增量更新** → ViewModel 层优化
6. **H3-H4 FocusScreen** → 独立页面，可并行
7. **M1-M5** → 逐个清理
8. **L1-L7** → 最后扫尾
