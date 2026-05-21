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
./deploy.sh                           # build APK, install via adb, launch app (WSL + usbipd)
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

## Key Files

| What | Path |
|------|------|
| Rust core API | `core/src/nion_core.rs` |
| Rust models/errors | `core/src/models.rs` |
| Kotlin entry composable | `app/app/src/main/java/com/echonion/nion/ui/NionApp.kt` |
| Task screen + ViewModel | `app/app/src/main/java/com/echonion/nion/ui/task/` |
| UniFFI binding output | `app/app/src/main/java/uniffi/nion_core/nion_core.kt` |
| Known pitfalls | `docs/pitfalls.md` |
| Code audit | `docs/code-audit-report.md` |

## Environment

- WSL2 development. Android SDK at `~/android-sdk`. NDK version `27.0.12077973`.
- `deploy.sh` uses `usbipd` for USB passthrough from Windows to WSL.
- Rust edition 2021. Java 17. Kotlin 2.1.0. Compose BOM 2024.12.01. AGP 8.7.3.
