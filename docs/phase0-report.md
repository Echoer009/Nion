# Nion Phase 0 完成报告

> 日期：2026-05-20
> 状态：Phase 0 完成，脚手架搭建完毕

---

## 已完成

### 1. 开发环境

| 工具 | 版本 | 状态 |
|------|------|------|
| Rust | 1.95.0 | ✅ |
| Java | OpenJDK 17.0.18 | ✅ |
| Android SDK | platform-35, build-tools-35, NDK-27 | ✅ |
| Gradle | 8.11.1 | ✅ |
| Android SDK 路径 | `~/android-sdk` | ✅ |

### 2. Rust 核心库 (`nion-core`)

- **位置**: `core/`
- **功能**: 任务 CRUD + SQLite（内存模式）
- **UniFFI**: proc-macro 模式，`#[uniffi::export]` + `uniffi::setup_scaffolding!()`
- **测试**: 5 个单元测试全部通过（create, get, update, complete, delete）
- **未完成**: 数据持久化（目前用 open_in_memory）、课表/番茄钟/统计/AI 陪伴模块

### 3. Axum 后端 (`nion-backend`)

- **位置**: `backend/`
- **功能**: 两个 Hello World 接口（`GET /`, `GET /health`）
- **启动**: `cargo run -p nion-backend` → `http://localhost:3000`
- **未完成**: 任务 API、AI Agent 接口、SSE、OpenAPI 文档

### 4. Android App (`app/`)

- **位置**: `app/`
- **构建**: `gradle assembleDebug` 成功，APK 17MB
- **APK**: `app/app/build/outputs/apk/debug/app-debug.apk`
- **UI**: 底部导航栏（Tasks / Schedule / Focus / Companion）+ 任务列表（假数据）
- **未完成**: Rust 核心库接入、数据持久化、课表/番茄钟/AI 陪伴页面

### 5. 踩坑文档

- **位置**: `docs/pitfalls.md`
- 已记录: UniFFI setup_scaffolding 宏、UDL weedle2 解析器问题

---

## 项目目录结构

```
nion/
├── Cargo.toml                    # Rust workspace
├── core/                         # Rust 核心库
│   ├── Cargo.toml
│   └── src/
│       └── lib.rs                # TaskData, NionCore, CRUD
├── backend/                      # Axum 后端
│   ├── Cargo.toml
│   └── src/
│       └── main.rs               # Hello World
├── app/                          # Android App
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/echonion/nion/
│           │   ├── NionApp.kt
│           │   ├── MainActivity.kt
│           │   └── ui/
│           │       ├── NionApp.kt         # 导航 + 底部栏
│           │       ├── theme/Theme.kt     # Material 3 主题
│           │       └── task/TaskScreen.kt  # 任务列表（假数据）
│           └── res/
├── docs/
│   └── pitfalls.md               # 踩坑记录
└── docker/                       # 空，后续用
```

---

## 未连接的部分

- Rust 核心库 ↔ Android App（UniFFI 绑定未接入）
- Rust 核心库 ↔ Axum 后端（后端未调用核心库）
- 所有数据都是临时的，没有持久化

---

## 下一阶段：本地 Android App（Phase 1-2）

### 优先级排序

1. **Rust → Android 接入**（UniFFI 生成 Kotlin 绑定，App 调用 Rust 核心库）
2. **数据持久化**（SQLite 文件存储，非内存）
3. **任务管理完整功能**（真实 CRUD，非假数据）
4. **课表模块**
5. **番茄钟模块**
6. **统计模块**
7. **AI 陪伴模块**（最后做，需要 LLM 接入）

### 暂缓

- Axum 后端（本地 App 不需要）
- 云同步
- AI Agent API
