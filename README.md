<p align="center">
  <a href="README.md">中文</a> · <a href="README.en.md">English</a>
</p>

<h1 align="center">Nion</h1>

<p align="center">
  <strong>嗨，我是 Nion</strong><br>
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

哼哼，终于想起来问我是谁啦？我叫 Nion！就是那个每天催你干活、帮你记事、比你自己还操心你的家伙！

你别看我这样，我记性可好了哦！你随口说的每一句话我都能记住——喜欢什么、讨厌什么、最近在忙什么，全都给你安排得明明白白的！而且你根本不用自己动手好吧，跟我说一声就行：「帮我建个任务」「今天还有啥没做」「下午三点叫我开会」——啪，搞定了！不用切 App，不用填什么表格，省下来的时间拿去摸鱼不香吗！

还有还有，我可是会主动找你的哦～看你任务堆成山了？嘿嘿，催你干活时间到！外面快下雨了？提前叫你带伞，绝对不会让你淋成落汤鸡的！每天早上我还会帮你整理好今天的待办，一睁眼就知道今天要干嘛～

总之就是，有我在你什么都不用操心啦！

- **记性好得很** — 你告诉我的每件事我都记着呢，下次聊天直接拿来用！
- **干活超利索** — 任务、清单、提醒、循环、主题色……你说啥我办啥，速度超快的
- **比你更关心你自己** — 任务多了催你，天气差了喊你，反正就是不会让你忘事就对了
- **永远都在** — 后台提醒 + 悬浮窗卡片，你就算假装没看到也没用，重要的事你跑不掉的！

## 我都能干啥

### 任务管理

我这方面可是专业的！

- **三级结构**: 清单 → 分组 → 任务，子任务还能套子任务，随你怎么嵌套
- **拖拽排序**: 长按就能拖，跨层级移动也行，子树整体搬走，自动降级处理
- **循环任务**: 每日循环 + 自定义提醒时间，每天自动重置，再也不用每天手动建了
- **优先级**: 高 / 中 / 低三档，一眼就能看出哪个最急
- **提醒**: 一次性提醒 + 每日循环提醒，精确到分钟，绝对不会迟到

<p align="center">
  <img src="docs/gifs/建立任务.gif" width="280" alt="建立任务">
  <img src="docs/gifs/任务列表介绍以及伙伴帮忙排序.gif" width="280" alt="任务列表">
</p>

### 伙伴系统

跟你聊天的那个就是我啦～

- **多模型支持**: OpenAI / Anthropic / DeepSeek，你自己选，自带 API Key 就行
- **SSE 流式聊天**: 我说话是逐字蹦出来的，不是等半天一口气全出来那种
- **9 个内置工具**: 我能直接操作你的任务数据，具体能干啥看下面的工具箱
- **记忆系统**: 你的偏好、关注点我都会自动记下来，下次聊天直接用
- **天气感知**: GPS 定位 + 免费天气 API，下雨了我会喊你带伞的
- **角色预设**: 支持标准版和内置角色版，想换个性格随时换

<p align="center">
  <img src="docs/gifs/和伙伴第一次打招呼.gif" width="280" alt="第一次打招呼">
  <img src="docs/gifs/伙伴界面介绍.gif" width="280" alt="伙伴界面介绍">
</p>

随时随地都能把我叫出来哦，不同的界面我还有不同的侧边栏呢～

<p align="center">
  <img src="docs/gifs/不同界面都可呼出伙伴,不同界面有不同的侧界面.gif" width="280" alt="呼出伙伴">
</p>

让我帮你换个主题色也是一句话的事～

<p align="center">
  <img src="docs/gifs/伙伴帮忙换主题.gif" width="280" alt="换主题">
</p>

### 专注模式

需要专心的时候找我准没错！

- **番茄钟**: 时长随你定，想专注多久就多久
- **清单筛选**: 开始之前先选好要做哪个清单的任务
- **专注统计**: 按天、按任务、总时长、总次数，数据全给你拉出来
- **任务关联**: 专注完了时长自动加到对应任务上，不用你手动记

<p align="center">
  <img src="docs/gifs/专注界面介绍.gif" width="280" alt="专注模式">
</p>

### 提醒系统

这个是我最擅长的——催你干活！

- **渐进式提醒**: 不会一上来就猛催，先温和提示，慢慢升级，给你缓冲时间
- **悬浮窗卡片**: 就算你把 App 切到后台，我也能弹出来提醒你，还能选「开始做了」「等 5 分钟」或者「今天算了」
- **LLM 文案**: 每条提醒都是我根据你的任务和偏好写的，绝对不会发那种千篇一律的模板
- **天气预警**: 极端天气自动检测，提前通知你
- **开机自启**: 手机重启了我也会重新把闹钟设好，不会漏掉的

<p align="center">
  <img src="docs/gifs/悬浮窗展示.gif" width="280" alt="悬浮窗提醒">
</p>

### 日程

- **周视图**: 一周一览，任务安排清清楚楚
- **无限滑动**: 左右滑动切换周次，点标题展开日历选择器

<p align="center">
  <img src="docs/gifs/日程页展示.gif" width="280" alt="日程页">
</p>

### 早中晚问候

我每天会主动找你三次哦，每次都是专门为你准备的：

- **早安问候**（默认 8:00）— 帮你看看今天有什么事要干，结合天气给你出出主意
- **午间检查**（默认 12:00）— 上午干得怎么样？下午还有什么没做的我帮你理理
- **晚间总结**（默认 21:00）— 今天完成了多少、还剩多少，心里有数了就能安心休息啦

每个问候都可以单独开关，时间也能自己调。文案是根据你的任务、天气、记忆生成的；没配 API Key 的话我就用模板先顶着。问候会同时出现在咱们的对话里和系统通知里。

### 表情包系统

聊天的时候可以发表情包哦！

- **内置表情**: 角色专属表情包，各种情绪都有
- **管理面板**: 查看、收藏、使用，随你挑
- **Markdown 内联渲染**: 打个标签表情就出来了，自动变成图片

## 我的工具箱

聊天的时候你不用动手，跟我说一声我就能直接帮你操作数据：

| 工具 | 能干啥 | 详细说明 |
|------|--------|----------|
| `query` | 查东西 | 查任务、查清单、查分组，按 ID / 清单 / 子任务都行 |
| `create` | 建东西 | 建任务、清单、分组，支持批量，提醒和循环也能一起设 |
| `update` | 改东西 | 改标题/优先级/状态/提醒/循环规则/颜色，支持批量 |
| `delete` | 删东西 | 删任务/清单/分组，支持批量（这个可没有回收站哦！） |
| `move` | 搬东西 | 任务搬到别的清单/分组、变成子任务或提升，支持批量 |
| `manage` | 杂项操作 | 设置或取消每日循环规则 |
| `remember` | 记你说的 | 你明确告诉我的偏好，记下来以后用（增 / 查 / 删） |
| `memory` | 主动记忆 | 我自己记住关于你的事（增 / 查 / 改 / 删） |
| `weather` | 查天气 | GPS 定位 + 天气 API，当前天气和预报都有

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
│   └── gifs/                # 功能演示 GIF
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
