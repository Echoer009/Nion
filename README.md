<h1 align="center">Nion</h1>

<p align="center">
  <strong>有记忆的 AI 伙伴 · 陪你一起成长的任务管理 App</strong><br>
  Rust 核心 · Kotlin UI · Jetpack Compose
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rust-1.95+-orange?logo=rust" alt="Rust">
  <img src="https://img.shields.io/badge/Kotlin-2.3-blue?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-26+-green?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License">
</p>

---

## Nion 是什么

Nion 不只是一个任务管理器。TA 是你的 **AI 伙伴** —— 会记住你喜欢什么、在忙什么、关注什么。

TA 能在聊天中直接帮你建任务、查进度、设提醒。TA 知道你今天有什么事、外面天气怎样。TA 是陪你一起规划的搭档。

核心特点：

- **TA 有记忆** — 你说过的话、你的偏好、你关心的事，TA 都会记住
- **TA 能做事** — 聊天中直接操作任务：创建、查询、修改、删除、移动、设置循环提醒，全部通过工具调用完成
- **TA 懂你** — 基于你的任务状态、天气情况主动发起提醒
- **TA 一直在线** — 后台提醒系统 + 悬浮窗卡片，不错过任何重要时刻

## 功能一览

### 任务管理

- **三级结构**: 清单 → 分组 → 任务，支持无限子任务嵌套
- **拖拽排序**: 长按拖拽跨层级移动，子树整体移动，自动降级
- **循环任务**: 每日循环 + 自定义提醒时间，每日自动重置完成状态
- **优先级**: 高 / 中 / 低三档，可视化标记
- **提醒**: 一次性提醒 + 每日循环提醒，AlarmManager 精确调度

### 伙伴

- **多模型支持**: OpenAI / Anthropic / DeepSeek，用户自带 API Key
- **SSE 流式聊天**: OkHttp 实现流式响应，逐字输出
- **9 个内置工具**: 伙伴可以实时操作你的任务数据（详见下方工具列表）
- **记忆系统**: 自动记忆用户偏好、关注点，跨会话保持上下文
- **天气感知**: GPS 定位 + Open-Meteo 免费 API，主动天气预警
- **角色预设**: Flavor 机制，支持标准版和内置角色版

### 专注模式

- **番茄钟**: 可自定义时长的倒计时专注
- **清单筛选**: 专注前按清单选择要做的任务
- **专注统计**: 按日分布、按任务分布、总时长、总次数
- **任务关联**: 专注时长自动累加到对应任务

### 提醒系统

- **渐进式提醒**: 从温和提示逐步升级，不粗暴打扰
- **悬浮窗卡片**: App 在后台时弹出悬浮提醒，支持「开始做了」「等 5 分钟」「今天算了」
- **LLM 文案**: 每条提醒由 LLM 根据任务内容和用户偏好生成，不重复不套路
- **天气预警**: 自动检测极端天气，提前通知
- **批量提醒**: 一键发送多条待办提醒
- **开机自启**: BootReceiver 重调度所有闹钟

### 早中晚问候

TA 会在一天的不同时段主动找你，每次问候都是量身定制的：

- **早安问候**（默认 8:00）— 汇总今日待办 + 高优先级任务，结合天气给出建议，帮你规划新的一天
- **午间检查**（默认 12:00）— 总结上午完成情况，提醒下午还有哪些待办，趁着午后精力充沛搞定它们
- **晚间总结**（默认 21:00）— 总结今日成就，告诉你完成了多少、还剩多少，安心休息

每种问候**独立开关 + 可自定义时间**。文案由 LLM 结合你的任务数据、天气、记忆生成；没配 API Key 时用模板兜底。问候会同时写入伙伴对话（打开面板就能看到）和系统通知。

### 课表

- **周视图**: 按周展示日程
- **可视化**: 直观的课程时间块

### 表情包系统

- **内置表情**: 角色专属表情包，聊天中可用
- **管理面板**: 查看、收藏、使用表情
- **Markdown 内联渲染**: 表情代码在聊天中自动渲染为图片

## AI 伙伴工具列表

伙伴通过工具调用（Function Calling）实时操作你的数据：

| 工具 | 功能 | 说明 |
|------|------|------|
| `query` | 查询 | 查任务（按 ID / 清单 / 子任务）、查清单、查分组 |
| `create` | 创建 | 创建任务（支持一次性提醒 + 每日循环）、清单、分组，支持批量 |
| `update` | 更新 | 修改任务标题/优先级/状态/提醒/循环规则、清单名称、分组名称/颜色，支持批量 |
| `delete` | 删除 | 删除任务/清单/分组，支持批量（不可撤销） |
| `move` | 移动 | 任务移到其他清单/分组/成为子任务/提升为主任务，分组整体迁移，支持批量 |
| `manage` | 通用操作 | 设置/移除每日循环规则 |
| `remember` | 用户偏好 | 记住用户明确要求的偏好（add / list / remove） |
| `memory` | 主动记忆 | 主动记录关于用户的事实性信息（add / list / update / remove） |
| `weather` | 天气查询 | GPS 定位 + Open-Meteo API，返回当前天气和预报 |

## 架构

```
┌──────────────────────────────────────────────────┐
│                 Android App (Kotlin)              │
│          Jetpack Compose + Material 3             │
├──────────────────────────────────────────────────┤
│              UniFFI Kotlin Bindings               │
├──────────────────────────────────────────────────┤
│              Rust Core (nion-core)                │
│        SQLite CRUD · Settings · Models            │
└──────────────────────────────────────────────────┘
```

**数据流**: Kotlin ViewModel → UniFFI → Rust `NionCore` (SQLite) → 回调更新 Compose 状态

## 项目结构

```
nion/
├── core/                    # Rust 核心库 (nion-core)
│   └── src/
│       ├── lib.rs           # uniffi setup + 模块导出
│       ├── models.rs        # 数据模型 (Checklist, Group, Task, ...)
│       └── nion_core.rs     # NionCore 实现：SQLite CRUD
├── app/                     # Android App
│   └── app/src/main/java/com/echonion/nion/
│       ├── NionApp.kt       # Application 单例
│       ├── MainActivity.kt
│       └── ui/
│           ├── NionApp.kt   # 导航 + 双面板布局
│           ├── task/        # 任务清单界面
│           ├── companion/   # AI 伙伴聊天
│           │   └── tools/   # 9 个工具实现
│           ├── focus/       # 专注模式
│           ├── schedule/    # 课表
│           ├── settings/    # 设置页
│           └── theme/       # 颜色/主题/形状
├── tools/
│   └── uniffi-bindgen/      # UniFFI 绑定生成 CLI
├── docs/                    # 设计文档 & 踩坑记录
├── build-android.sh         # Linux/WSL/Git Bash 构建脚本
├── build-android.ps1        # Windows PowerShell 构建脚本
└── deploy.sh                # 一键编译 + 安装到设备
```

## 数据模型

```
Checklist (清单) ──1:N──> Group (分组) ──1:N──> Task (任务)
Checklist (清单) ──1:N──> Task (任务, via category_id)
Task (任务) ──1:N──> Task (子任务, via parent_id)
```

## 快速开始

### 环境要求

| 工具 | 版本 |
|------|------|
| Rust | 1.95+ |
| Java | OpenJDK 17 |
| Android SDK | platform-36, NDK 27.0.12077973 |
| Kotlin | 2.3.21 |
| Compose BOM | 2026.05.00 |

### 1. 构建 Rust 核心库

```bash
# 桌面测试
cargo build -p nion-core
cargo test -p nion-core

# Android 交叉编译 + UniFFI 绑定生成
./build-android.sh        # Linux / WSL / Git Bash
# 或
./build-android.ps1       # Windows PowerShell
```

脚本会自动:
1. 编译 `nion-core` 为 `aarch64-linux-android` 和 `x86_64-linux-android`
2. 运行 `uniffi-bindgen` 生成 Kotlin 绑定
3. 复制 `.so` 和 `.kt` 到 `app/` 对应目录

### 2. 构建 Android App

```bash
cd app && ./gradlew assembleStandardDebug
```

### 3. 一键部署到设备

```bash
# 标准版
./deploy.sh standard

# 内置角色版
./deploy.sh character
```

## 技术栈

| 层 | 技术 |
|----|------|
| Android UI | Jetpack Compose, Material 3, Navigation Compose |
| 异步 | Kotlin Coroutines, ViewModel |
| 网络 | OkHttp (SSE 流式聊天) |
| 后台任务 | WorkManager, AlarmManager |
| 定位 | Google Play Services Location |
| Rust 核心 | SQLite (rusqlite), UniFFI (proc-macro), serde, chrono |
| 拖拽排序 | [reorderable](https://github.com/Calvin-LL/reorderable) |

## 许可证

[GNU General Public License v3.0](LICENSE)
