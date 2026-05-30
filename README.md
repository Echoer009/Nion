<h1 align="center">Nion</h1>

<p align="center">
  <strong>嗨，我是 Nion · 有记忆的伙伴，陪你一起成长</strong><br>
  Rust 核心 · Kotlin UI · Jetpack Compose
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rust-1.95+-orange?logo=rust" alt="Rust">
  <img src="https://img.shields.io/badge/Kotlin-2.3-blue?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-26+-green?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License">
</p>

---

## 自我介绍

说我是「任务管理 App」也没错，但我觉得「伙伴」更准确。

我会记住你说过的话、你在忙什么、你在意什么。你跟我聊天的时候，可以直接让我帮你建任务、查进度、设提醒——不用切出去，不用填表。我知道你今天有什么事、外面天气怎样。我是那个会主动来找你的搭档。

- **我有记忆** — 你告诉我的偏好、你关心的事，我都会记下来，下次聊天还认得你
- **我能做事** — 创建、查询、修改、删除、移动、设循环提醒——你说一句，我就帮你搞定
- **我懂你** — 看你任务堆多了、天气变差了，我会主动提醒你
- **我一直在线** — 后台提醒 + 悬浮窗卡片，重要的事不会让你错过

## 我能做什么

### 任务管理

- **三级结构**: 清单 → 分组 → 任务，支持无限子任务嵌套
- **拖拽排序**: 长按拖拽跨层级移动，子树整体移动，自动降级
- **循环任务**: 每日循环 + 自定义提醒时间，每日自动重置完成状态
- **优先级**: 高 / 中 / 低三档，可视化标记
- **提醒**: 一次性提醒 + 每日循环提醒，AlarmManager 精确调度

### 伙伴

- **多模型支持**: OpenAI / Anthropic / DeepSeek，你自带 API Key
- **SSE 流式聊天**: OkHttp 实现流式响应，逐字输出
- **9 个内置工具**: 实时操作你的任务数据（详见下方工具列表）
- **记忆系统**: 自动记忆你的偏好、关注点，跨会话保持上下文
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
- **LLM 文案**: 每条提醒都根据任务内容和你的偏好生成，不重复不套路
- **天气预警**: 自动检测极端天气，提前通知
- **批量提醒**: 一键发送多条待办提醒
- **开机自启**: BootReceiver 重调度所有闹钟

### 早中晚问候

我会在一天的不同时段主动找你，每次问候都是量身定制的：

- **早安问候**（默认 8:00）— 汇总今日待办 + 高优先级任务，结合天气给你建议，帮你规划新的一天
- **午间检查**（默认 12:00）— 总结上午完成情况，提醒你下午还有哪些待办
- **晚间总结**（默认 21:00）— 告诉你今天完成了多少、还剩多少，安心休息

每种问候**独立开关 + 可自定义时间**。文案根据你的任务数据、天气、记忆生成；没配 API Key 时用模板兜底。问候会同时写入伙伴对话（打开面板就能看到）和系统通知。

### 课表

- **周视图**: 按周展示日程
- **可视化**: 直观的课程时间块

### 表情包系统

- **内置表情**: 角色专属表情包，聊天中可用
- **管理面板**: 查看、收藏、使用表情
- **Markdown 内联渲染**: 表情代码在聊天中自动渲染为图片

## 我的工具箱

聊天的时候你不用动手，直接跟我说就行。我通过工具调用（Function Calling）实时操作你的数据：

| 工具 | 功能 | 说明 |
|------|------|------|
| `query` | 查询 | 查任务（按 ID / 清单 / 子任务）、查清单、查分组 |
| `create` | 创建 | 创建任务（支持一次性提醒 + 每日循环）、清单、分组，支持批量 |
| `update` | 更新 | 修改任务标题/优先级/状态/提醒/循环规则、清单名称、分组名称/颜色，支持批量 |
| `delete` | 删除 | 删除任务/清单/分组，支持批量（不可撤销） |
| `move` | 移动 | 任务移到其他清单/分组/成为子任务/提升为主任务，分组整体迁移，支持批量 |
| `manage` | 通用操作 | 设置/移除每日循环规则 |
| `remember` | 用户偏好 | 记住你明确要求的偏好（add / list / remove） |
| `memory` | 主动记忆 | 主动记录关于你的事实性信息（add / list / update / remove） |
| `weather` | 天气查询 | GPS 定位 + Open-Meteo API，返回当前天气和预报 |

---

*说了这么多我的事。下面是给开发者看的——我是怎么被造出来的。*

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
