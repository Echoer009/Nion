# AGENTS.md

## Project Overview

Nion is an AI companion + task management Android app. Rust core library exposed to Kotlin via UniFFI. Axum backend exists but is not yet wired to the app.

## Workspace Layout

- **`core/`** — Rust library (`nion-core`). SQLite-backed task/checklist CRUD, settings. Exposed as `cdylib` + `lib`.
- **`backend/`** — Axum HTTP server (`nion-backend`). Stub; depends on `nion-core`.
- **`app/`** — Android app (Kotlin, Jetpack Compose, Material 3). Package: `com.echonion.nion`.
- **`tools/uniffi-bindgen/`** — CLI wrapper for UniFFI binding generation (`uniffi-bindgen-cli`).
- **`docs/`** — Design notes, pitfalls, audit reports.

## Commands

### Rust

```bash
cargo build -p nion-core              # build core library
cargo test -p nion-core               # run core tests (inline #[cfg(test)] mod in nion_core.rs)
cargo run -p nion-backend             # start backend on :3000
cargo run -p uniffi-bindgen-cli -- generate --library <path-to-so> --language kotlin --out-dir <out>
```

### Android

```bash
cd app && ./gradlew assembleDebug     # build APK
./build-android.sh                    # cross-compile Rust for Android + generate UniFFI Kotlin bindings + copy artifacts
./deploy.sh                           # build debug APK, install via adb, launch app (default: standard debug)
./deploy.sh character                 # character flavor debug
./deploy.sh character release         # character flavor release
./deploy.sh standard release          # standard flavor release
```

## Build & Deploy Flow

1. `build-android.sh` compiles `nion-core` for `aarch64-linux-android` and `x86_64-linux-android` using NDK 27.
2. Runs `uniffi-bindgen` against the aarch64 `.so` to produce `nion_core.kt`.
3. Copies `.so` files to `app/app/src/main/jniLibs/{arm64-v8a,x86_64}/` and `.kt` binding to `app/app/src/main/java/uniffi/nion_core/`.
4. `deploy.sh` runs Gradle build, installs via `adb`, launches `com.echonion.nion/.MainActivity`.

## UniFFI Conventions

- **Proc-macro mode only** — no UDL files, no `build.rs`, no `uniffi` build feature.
- `uniffi::setup_scaffolding!()` must be called at the top of `core/src/lib.rs`.
- Types use `#[uniffi::Record]`, `#[uniffi::Object]`, `#[uniffi::Error]`, `#[uniffi::export]`.
- Do not mix `setup_scaffolding!()` with `include_scaffolding!("xxx.udl")`.

## Architecture Notes

- `NionCore` holds `db: Mutex<Connection>` — single Rust instance, but Kotlin side historically created two (see audit C1). The `core()` extension on `Application` is the intended singleton.
- All DB ops are synchronous on the caller thread. Kotlin callers should use `Dispatchers.IO`.
- `reorder_tasks` uses manual `BEGIN`/`COMMIT`; other multi-step DB ops do not.
- The app uses a `DualPanelLayout` with left sidebar (checklists) and right sidebar (companion), both swipeable.
- Navigation: `tasks`, `schedule`, `pomodoro`, `settings` routes via Jetpack Navigation Compose.
- **分组 (Groups)**: 清单下的二级分类。数据模型: `Checklist → Group → Task`。`TaskData.group_id` 关联分组，`GroupData.checklist_id` 关联清单。

## Data Model

```
Checklist (清单) ──1:N──> Group (分组) ──1:N──> Task (任务)
Checklist (清单) ──1:N──> Task (任务, via category_id, group_id 可为空)
Task (任务) ──1:N──> Task (子任务, via parent_id)
```

## Backend API (Axum on :3000)

### 清单 API
- `GET    /api/checklists` — 获取所有清单
- `POST   /api/checklists` — 创建清单 `{ "name": "..." }`
- `PUT    /api/checklists/:id` — 修改清单名称 `{ "name": "..." }`
- `DELETE /api/checklists/:id` — 删除清单
- `PUT    /api/checklists/reorder` — 重排清单 `{ "ordered_ids": [...] }`

### 分组 API
- `POST   /api/groups` — 创建分组 `{ "name": "语文", "checklist_id": "...", "color": "#FF5722" }`
- `GET    /api/groups/:checklist_id` — 获取清单下的所有分组
- `PUT    /api/groups/:id` — 更新分组 `{ "name": "...", "color": "..." }`
- `DELETE /api/groups/:id` — 删除分组（保留组内任务，group_id 置空）
- `PUT    /api/groups/reorder` — 重排分组 `{ "ordered_ids": [...] }`

### 任务 API
- `GET    /api/tasks` — 获取所有任务
- `GET    /api/tasks/by-category?category_id=...&group_id=...` — 按清单和分组筛选
- `GET    /api/tasks/:id` — 获取单个任务
- `POST   /api/tasks` — 创建任务 `{ "title": "...", "group_id": "...", ... }`
- `PUT    /api/tasks/:id` — 更新任务（支持 `group_id` 字段）
- `DELETE /api/tasks/:id` — 删除任务
- `GET    /api/tasks/:id/subtasks` — 获取子任务
- `PUT    /api/tasks/:id/parent` — 更新父任务 `{ "new_parent_id": "..." }`
- `PUT    /api/tasks/:id/group` — 更新分组归属 `{ "group_id": "..." }`
- `POST   /api/tasks/:id/focus` — 累加专注时长 `{ "seconds": 60 }`
- `PUT    /api/tasks/reorder` — 重排任务 `{ "ordered_ids": [...] }`

### 设置 API
- `GET    /api/settings/:key` — 获取设置值
- `PUT    /api/settings` — 设置键值对 `{ "key": "...", "value": "..." }`

## Key Files

| What | Path |
|------|------|
| Rust core API | `core/src/nion_core.rs` |
| Rust models/errors | `core/src/models.rs` |
| Kotlin entry composable | `app/app/src/main/java/com/echonion/nion/ui/NionApp.kt` |
| Task screen + ViewModel | `app/app/src/main/java/com/echonion/nion/ui/task/` |
| Memory tool | `app/app/src/main/java/com/echonion/nion/ui/companion/tools/MemoryTool.kt` |
| UniFFI binding output | `app/app/src/main/java/uniffi/nion_core/nion_core.kt` |
| Known pitfalls | `docs/pitfalls.md` |
| Code audit | `docs/code-audit-report.md` |

## Coding Rules

### 不要主动修改不是本次任务范围的代码

如果某个文件存在编译错误或代码问题，但该文件**不是你当前任务涉及的目标文件**，不要主动去修复它。只在用户明确要求时才修改。

### 禁止卸载应用

**绝对不能执行 `adb uninstall` 或任何卸载操作**，必须先询问用户确认。卸载会清除所有本地数据（任务、对话、设置等），造成不可逆的数据丢失。

### 注释规范

所有新增和修改的代码**必须写详细注释**。要求：

- 每个函数/Composable 必须有中文注释说明其用途
- 复杂逻辑块（动画、手势、状态转换等）必须有行内注释解释"为什么这么做"
- 状态变量的用途必须用注释标明
- 回调参数（`onXxx`）必须注释说明触发时机和传递的数据
- 动画的 `transitionSpec` 和 `animationSpec` 必须注释说明动画效果的意图

示例：
```kotlin
/**
 * 周日期选择器 —— 显示当前周的 7 天，支持左右滑动切换周次。
 *
 * @param selectedDate 当前选中的日期，用于高亮显示
 * @param today 今天的日期，用于标记"今天"圆点
 * @param onSelect 用户点击某一天时触发，回调传入被点击的 LocalDate
 */
@Composable
private fun WeekDaySelector(
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    // weekOffset: 相对于本周的周偏移量，0=本周，1=下周，-1=上周
    var weekOffset by remember { mutableStateOf(0) }
    ...
}
```

## Environment

- WSL2 development. Android SDK at `~/android-sdk`. NDK version `27.0.12077973`.
- `deploy.sh` uses `usbipd` for USB passthrough from Windows to WSL.
- Rust edition 2021. Java 17. Kotlin 2.3.21. Compose BOM 2026.05.00. AGP 9.1.1.

## Release 流程

1. 修改 `app/app/build.gradle.kts` 中的 `versionCode` +1 和 `versionName` 升版本号，提交
2. 打 tag：`git tag v0.x.x`（不带 `-character` 后缀）
3. 构建 **两个** flavor 的 release APK：
   ```bash
   cd app && ./gradlew.bat assembleStandardRelease assembleCharacterRelease
   ```
4. 创建 GitHub Release，title 格式 `Nion x.x.x`，tag `v0.x.x`，中英文对照 notes
5. 上传两个 APK，重命名为 `BrainGirl.apk`（character）和 `Nion.apk`（standard）：
   ```bash
   gh release create v0.x.x --title "Nion x.x.x" --notes "..." \
     "app/app/build/outputs/apk/character/release/app-character-release.apk#BrainGirl.apk" \
     "app/app/build/outputs/apk/standard/release/app-standard-release.apk#Nion.apk"
   ```
6. Release notes 格式参考 `v0.7.4`、`v0.7.5`：中文在前（`## 新功能` / `## 修复`），英文在后（`## New Features` / `## Fixes`）
