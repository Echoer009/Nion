<p align="center">
  <a href="README.md">中文</a> · <a href="README.en.md">English</a>
</p>

<h1 align="center">Nion</h1>

<p align="center">
  <strong>Hey, I'm Nion!</strong><br>
  Rust Core · Kotlin UI · Jetpack Compose
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rust-1.95+-orange?logo=rust" alt="Rust">
  <img src="https://img.shields.io/badge/Kotlin-2.3-blue?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-26+-green?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License">
</p>

---

## About Me

Hehe, so you finally remembered to ask who I am? I'm Nion! The one who worries about your stuff more than you do!

Don't let my looks fool you — my memory is amazing! Every little thing you tell me, I remember it all. What you like, what you hate, what you're busy with — organized and ready to go! And you don't even need to lift a finger, just tell me: "create a task", "what's left today", "remind me at 3 PM" — bam, done! No app switching, no form filling. Use that saved time to slack off instead, wouldn't that be nicer?

Oh, and I'll come find you on my own too~ See your tasks piling up like a mountain? Hehe, time to get you working! About to rain outside? I'll remind you to bring an umbrella — no way I'm letting you get soaked! Every morning I'll organize your to-do list too, so the moment you open your eyes you know what's up today~

Basically, with me around you don't need to worry about anything!

- **Memory like a steel trap** — Every little thing you tell me, I remember it. Next chat, it's all right there!
- **Super fast at getting stuff done** — Tasks, lists, reminders, recurring stuff, theme colors... you name it, I do it. Lightning fast!
- **More concerned about you than you are** — Too many tasks? I'll nudge you. Bad weather? I'll warn you. Basically, I won't let you forget anything.
- **Always around** — Background reminders + floating cards. Even if you pretend you didn't see them, you can't escape the important stuff!

## What I Can Do

### Task Management

This is where I really shine!

- **Three-level hierarchy**: Checklist → Group → Task, with subtasks inside subtasks — nest 'em however deep you want
- **Drag & drop reorder**: Long press to drag, works across levels too. Moves the whole subtree, auto-downgrades gracefully
- **Recurring tasks**: Daily loop + custom reminder times. Auto-resets every day, no more manual creation
- **Priority levels**: High / Medium / Low — one glance tells you what's urgent
- **Reminders**: One-time + daily recurring reminders. Down to the minute, never late

<p align="center">
  <img src="docs/gifs/建立任务.gif" width="280" alt="Create Task">
  <img src="docs/gifs/任务列表介绍以及伙伴帮忙排序.gif" width="280" alt="Task List">
</p>

### Companion System

That's me you're chatting with~

- **Multi-model support**: OpenAI / Anthropic / DeepSeek — your pick, just bring your own API key
- **SSE streaming chat**: I talk character by character, not that "wait forever then dump everything at once" style
- **9 built-in tools**: I can directly manipulate your task data — see the toolbox below for details
- **Memory system**: Your preferences and focus points get remembered automatically, carried across sessions
- **Weather awareness**: GPS + free weather API. Rain's coming? I'll tell you to grab an umbrella
- **Character presets**: Standard version and built-in character version — switch personalities whenever you want

<p align="center">
  <img src="docs/gifs/和伙伴第一次打招呼.gif" width="280" alt="First Greeting">
  <img src="docs/gifs/伙伴界面介绍.gif" width="280" alt="Companion UI">
</p>

Summon me from anywhere — different screens have different sidebars~

<p align="center">
  <img src="docs/gifs/不同界面都可呼出伙伴,不同界面有不同的侧界面.gif" width="280" alt="Summon Companion">
</p>

Want me to change your theme color? Just say the word~

<p align="center">
  <img src="docs/gifs/伙伴帮忙换主题.gif" width="280" alt="Change Theme">
</p>

### Focus Mode

When you need to concentrate, come to me!

- **Pomodoro timer**: Set whatever duration you want, focus as long as you like
- **Checklist filter**: Pick which checklist's tasks to work on before starting
- **Focus stats**: By day, by task, total time, total count — all the data laid out for you
- **Task linking**: After focusing, the time auto-adds to the corresponding task. No manual logging needed

<p align="center">
  <img src="docs/gifs/专注界面介绍.gif" width="280" alt="Focus Mode">
</p>

### Reminder System

This is what I'm best at — getting you to work!

- **Progressive reminders**: I don't go hard right away. Gentle nudge first, then slowly escalate. Giving you time to ease in
- **Floating cards**: Even if you switch the app to background, I pop up a floating reminder. You can pick "Started it", "5 more minutes", or "Skipping today"
- **LLM-written messages**: Every reminder is written by me based on your tasks and preferences. No cookie-cutter templates, ever
- **Weather alerts**: Automatic extreme weather detection, notified in advance
- **Boot resilience**: Phone restarted? I'll re-set all the alarms. Nothing gets missed

<p align="center">
  <img src="docs/gifs/悬浮窗展示.gif" width="280" alt="Floating Reminder">
</p>

### Schedule

- **Week view**: One week at a glance, tasks laid out clearly
- **Infinite swipe**: Swipe left/right to switch weeks, tap the header to expand calendar picker

<p align="center">
  <img src="docs/gifs/日程页展示.gif" width="280" alt="Schedule">
</p>

### Morning / Noon / Evening Greetings

I come find you three times a day, each one made just for you:

- **Morning greeting** (default 8:00) — Let's see what's on your plate today, plus weather-based suggestions to start the day right
- **Noon check-in** (default 12:00) — How'd the morning go? Let me sort out what's left for the afternoon
- **Evening wrap-up** (default 21:00) — Here's what you finished and what's still there. Know where you stand, then rest easy

Each greeting has its own toggle and customizable time. Messages are generated from your tasks, weather, and memories; if there's no API key configured, I'll fall back to templates. Greetings show up both in our chat and as system notifications.

### Sticker System

You can send stickers while chatting!

- **Built-in stickers**: Character-exclusive sticker pack, all kinds of moods covered
- **Management panel**: Browse, favorite, use — pick whatever you like
- **Markdown inline rendering**: Just type a tag and the sticker pops up, auto-rendered as an image

## My Toolbox

You don't need to lift a finger while chatting. Just tell me and I'll handle the data directly:

| Tool | What it does | Details |
|------|-------------|---------|
| `query` | Look things up | Tasks, checklists, groups — by ID / checklist / subtask, whatever works |
| `create` | Make things | Tasks, checklists, groups, supports batch. Reminders and recurring rules too |
| `update` | Change things | Title / priority / status / reminder / recurring rules / colors, supports batch |
| `delete` | Remove things | Tasks / checklists / groups, supports batch (no recycle bin here!) |
| `move` | Move things | Tasks to other checklists/groups, make subtask or promote, supports batch |
| `manage` | Misc operations | Set or remove daily recurring rules |
| `remember` | Remember your prefs | Preferences you explicitly tell me (add / list / remove) |
| `memory` | Auto-remember | Facts I pick up about you (add / list / update / remove) |
| `weather` | Check weather | GPS + weather API, current conditions and forecast |

---

*OK that's enough about me. Here's the dev stuff — how I was built.*

## What I Want to Learn Next

I'm already pretty awesome, but there's so much more I want to do!

- [ ] More list types: notes, journal, habit tracker
- [ ] More LLM providers: Google Gemini, local models (Ollama)
- [ ] Desktop support (built on existing Rust core)
- [ ] Cloud sync: multi-device data synchronization
- [ ] Widgets: home screen quick task, today's overview
- [ ] Collaboration: share checklists, assign tasks
- [ ] i18n: English, Japanese UI support
- [ ] Custom reminder sounds
- [ ] Quick actions: create tasks from notification bar

## You Might Be Wondering...

**Q: Do I need internet to use it?**
A: Core features like task management, focus timer, and schedule work entirely offline. Only chatting with me and weather queries need internet.

**Q: Do I need to create an account?**
A: Nope! All data is stored locally on your phone — no cloud account needed. Just plug in an LLM API key in settings to start chatting with me.

**Q: Which AI models are supported?**
A: Currently OpenAI (GPT series), Anthropic (Claude series), and DeepSeek. Basically any service with an OpenAI-compatible API format works.

**Q: Is my API key safe?**
A: Relax, your key stays on your device and is never sent to any third-party server. All API requests go directly from your phone to the AI provider.

**Q: What's the difference between Nion and BrainGirl?**
A: Nion is the standard version without a built-in character preset. BrainGirl comes with a pre-configured character card, avatar, and sticker pack. Same features, different personality — pick whoever you like~

**Q: Can I backup and restore my data?**
A: Of course! Settings → Data Management → Export generates a zip with all your data. Import it on a new device or after reinstalling to restore everything. Nothing gets lost!

**Q: Why are reminders sometimes delayed?**
A: Hey, that's not my fault! Some manufacturers (Xiaomi, Huawei, vivo, etc.) aggressively kill background apps, which delays my alarms. Grant "overlay" and "battery optimization whitelist" permissions in Settings to fix this.

**Q: Is it open source? Can I contribute?**
A: Fully open source under GPL-3.0. Issues and PRs welcome — come make me even better!

## Architecture

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

**Data flow**: Kotlin ViewModel → UniFFI → Rust `NionCore` (SQLite) → callback updates Compose state

## Project Structure

```
nion/
├── core/                    # Rust core library (nion-core)
│   └── src/
│       ├── lib.rs           # uniffi setup + module exports
│       ├── models.rs        # Data models (Checklist, Group, Task, ...)
│       └── nion_core.rs     # NionCore: SQLite CRUD
├── app/                     # Android App
│   └── app/src/main/java/com/echonion/nion/
│       ├── NionApp.kt       # Application singleton
│       ├── MainActivity.kt
│       └── ui/
│           ├── NionApp.kt   # Navigation + dual-panel layout
│           ├── task/        # Task & checklist UI
│           ├── companion/   # AI companion chat
│           │   └── tools/   # 9 tool implementations
│           ├── focus/       # Focus mode
│           ├── schedule/    # Schedule
│           ├── settings/    # Settings
│           └── theme/       # Colors / themes / shapes
├── tools/
│   └── uniffi-bindgen/      # UniFFI binding generation CLI
├── docs/                    # Design docs & gotchas
│   └── gifs/                # Feature demo GIFs
├── build-android.sh         # Linux/WSL/Git Bash build script
├── build-android.ps1        # Windows PowerShell build script
└── deploy.sh                # Build + install to device in one shot
```

## Data Model

```
Checklist ──1:N──> Group ──1:N──> Task
Checklist ──1:N──> Task (via category_id)
Task ──1:N──> Task (subtasks, via parent_id)
```

## Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| Rust | 1.95+ |
| Java | OpenJDK 17 |
| Android SDK | platform-36, NDK 27.0.12077973 |
| Kotlin | 2.3.21 |
| Compose BOM | 2026.05.00 |

### 1. Build the Rust core

```bash
# Desktop testing
cargo build -p nion-core
cargo test -p nion-core

# Android cross-compile + UniFFI binding generation
./build-android.sh        # Linux / WSL / Git Bash
# or
./build-android.ps1       # Windows PowerShell
```

The script automatically:
1. Compiles `nion-core` for `aarch64-linux-android` and `x86_64-linux-android`
2. Runs `uniffi-bindgen` to generate Kotlin bindings
3. Copies `.so` and `.kt` files to the corresponding `app/` directories

### 2. Build the Android App

```bash
cd app && ./gradlew assembleStandardDebug
```

### 3. Deploy to device

```bash
# Standard version
./deploy.sh standard

# Built-in character version
./deploy.sh character
```

## Tech Stack

| Layer | Tech |
|-------|------|
| Android UI | Jetpack Compose, Material 3, Navigation Compose |
| Async | Kotlin Coroutines, ViewModel |
| Network | OkHttp (SSE streaming chat) |
| Background | WorkManager, AlarmManager |
| Location | Google Play Services Location |
| Rust Core | SQLite (rusqlite), UniFFI (proc-macro), serde, chrono |
| Drag & Drop | [reorderable](https://github.com/Calvin-LL/reorderable) |

## License

[GNU General Public License v3.0](LICENSE)
