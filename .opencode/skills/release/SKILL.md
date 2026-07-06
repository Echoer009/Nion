---
name: release
description: Use when releasing or publishing a new Nion Android app version to GitHub Releases. Covers version bump, git tagging, building both flavor (standard + character) release APKs, and writing bilingual Chinese/English release notes. Trigger keywords: release, 发版, 发布, 打 tag, 打tag, version bump, 升版本, GitHub Release, 上传 APK.
---

# Releasing a Nion version

Step-by-step process for publishing a new Nion release to GitHub. Distilled
from the established release history (v0.7 → v0.8.2+). Repository:
**Echoer009/Nion** (`origin` remote).

## When to use this skill

Use when the user asks to publish / ship / release a new version, bump the
version and tag it, upload APKs to GitHub Releases, or write release notes.
Do NOT use for `deploy.sh` (that installs a debug/release build to a local
device via adb — not a public release).

## Key facts (verify before acting)

- **Version fields**: `app/app/build.gradle.kts` → `versionCode` (int, +1 each
  release) and `versionName` (string, e.g. `"0.8.2"`). As of v0.8.2 these are
  `versionCode = 19`, `versionName = "0.8.2"`.
- **Two flavors**: `standard` (Nion) and `character` (BrainGirl). The character
  flavor auto-appends `versionNameSuffix = "-character"`, but the **git tag
  never carries a suffix**.
- **Tag format**: `vX.Y.Z` (e.g. `v0.8.3`), lightweight tag, no `-character`.
- **Release title**: `Nion X.Y.Z` (e.g. `Nion 0.8.3`).
- **Repo**: `Echoer009/Nion`. `gh` must be authenticated.

## Pre-flight checks

Before starting, confirm:

1. `git status` is clean (no uncommitted changes). If the working tree is
   dirty, ask the user whether to commit first — do NOT bundle unrelated
   changes into the release commit.
2. Current branch is `main` and up to date with `origin/main`
   (`git log --oneline -1` matches remote).
3. The `gh` CLI is authed: `gh auth status`. If not, tell the user to run
   `gh auth login`.
4. Decide the next version with the user:
   - **Patch** (`0.8.2` → `0.8.3`): bug fixes only.
   - **Minor** (`0.8.2` → `0.9.0` or `0.8.2` → `0.8.3`'s successor): new features.
   Never skip confirming the target version with the user.

## Step-by-step

### 1. Bump version

Edit `app/app/build.gradle.kts`:

- `versionCode` → increment by 1 (e.g. `19` → `20`).
- `versionName` → new version string without `v` prefix (e.g. `"0.8.3"`).

Do NOT touch `versionNameSuffix` (line ~42, `-character`) — it applies to the
character flavor only and must stay.

### 2. Commit the version bump

```bash
git add app/app/build.gradle.kts
git commit -m "chore: 版本号升至 vX.Y.Z"
```

Use the exact `vX.Y.Z` in the message to match the established commit style
(e.g. `chore: 版本号升至 v0.8.2`, `chore: 引入 Markwon 依赖并升级版本号至 v0.8.2`).

### 3. Push and tag

```bash
git push origin main
git tag vX.Y.Z
git push origin vX.Y.Z
```

Tag is a **lightweight** tag (not annotated). Push the tag explicitly — `gh
release create` will pick it up.

### 4. Build both release APKs

```bash
cd app && ./gradlew.bat assembleStandardRelease assembleCharacterRelease
```

(On WSL/Linux use `./gradlew`; on Windows/PowerShell use `./gradlew.bat`. The
repo historically uses `./gradlew.bat`.)

Output artifacts:

- `app/app/build/outputs/apk/character/release/app-character-release.apk`
- `app/app/build/outputs/apk/standard/release/app-standard-release.apk`

Both must build `BUILD SUCCESSFUL`. If the build fails, **stop** and fix it —
do not publish a broken release.

### 5. Write bilingual release notes

Create a temp notes file (e.g. `RELEASE_NOTES.md`). Format is **strictly
bilingual, Chinese first**:

```markdown
## 新功能

- <中文条目，用反引号包代码/文件名>
- ...

## 修复

- <中文修复条目>

## New Features

- <English entry>
- ...

## Fixes

- <English fix entry>
```

Conventions observed across v0.7.4 → v0.8.2:

- Always both `## 新功能` and `## 修复` sections (Chinese), then `## New
  Features` and `## Fixes` (English). Omit a section only if there is genuinely
  nothing in that category.
- Each bullet: concise, one logical change. Wrap code identifiers, file names,
  error strings in backticks (e.g. `` `resolveProvider` ``, `` `Expected URL
  scheme http or https...` ``).
- Optional bold lead-in for the topic: `` **笔记型清单**：… `` / `` **Notebook
  checklists**: … ``.
- Derive the content from `git log vPREVIOUS..HEAD --oneline` (read the
  `feat:`/`fix:`/`chore:` commits since the last tag) plus the actual diff.
  Group features vs fixes accordingly. Skip pure refactors / chore / version
  bumps unless user-facing.

To list changes since last release:

```bash
git log v0.8.2..HEAD --oneline          # replace v0.8.2 with the previous tag
```

### 6. Create the GitHub Release and upload APKs

```bash
gh release create vX.Y.Z \
  --title "Nion X.Y.Z" \
  --notes-file RELEASE_NOTES.md \
  "app/app/build/outputs/apk/character/release/app-character-release.apk" \
  "app/app/build/outputs/apk/standard/release/app-standard-release.apk"
```

Notes:

- `gh release create` defaults to non-prerelease, non-draft — matches every
  past Nion release (`prerelease: false`, `draft: false`).
- **Asset naming**: recent releases (v0.7.6+) upload APKs under their raw
  Gradle names (`app-character-release.apk`, `app-standard-release.apk`). Early
  releases (v0.7.4–v0.7.5) renamed them to `BrainGirl.apk` / `Nion.apk` via
  the `path#newname` syntax. Prefer the **raw names** unless the user
  explicitly asks for the friendly rename.

### 7. Verify

```bash
gh release view vX.Y.Z
```

Confirm: title `Nion X.Y.Z`, both APK assets present and non-zero size, notes
render correctly in both languages. Open the URL it prints in a browser for a
final visual check.

Clean up the temp notes file:

```bash
rm RELEASE_NOTES.md
```

## Common pitfalls

- **Forgetting to push the tag.** `gh release create` works off a tag that
  exists on the remote; always `git push origin vX.Y.Z`.
- **Putting `-character` in the tag.** Tags are always bare `vX.Y.Z`. The
  character flavor suffix lives only in `versionNameSuffix`.
- **Publishing while `main` isn't pushed.** Push the version-bump commit
  before tagging so the tag points at a commit on the remote.
- **Renaming APKs inconsistently.** Pick raw names (current convention) or
  friendly names, don't mix. When unsure, ask the user.
- **Single-flavor build.** Always build BOTH `assembleStandardRelease` and
  `assembleCharacterRelease` — a release with only one APK is incomplete.
- **Dirty working tree.** Other agents/features may be mid-flight. Confirm
  with the user before committing; never silently sweep unrelated changes into
  the release commit.
