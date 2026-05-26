#Requires -Version 5.1
<#
.SYNOPSIS
    Windows 版 Nion Android 构建脚本。
    编译 Rust core → 生成 UniFFI Kotlin 绑定 → 复制产物到 app 目录。
#>

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$NdkRoot   = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973"
$TC        = "$NdkRoot\toolchains\llvm\prebuilt\windows-x86_64"
$TmpOut    = "$env:TEMP\nion-uniffi-output"

if (-not (Test-Path "$TC\bin\clang.exe")) {
    Write-Host "NDK not found at $TC" -ForegroundColor Red
    Write-Host "Install: sdkmanager `"ndk;27.0.12077973`"" -ForegroundColor Yellow
    exit 1
}

# ── 编译器环境变量 ──
# cc crate (libsqlite3-sys) 需要绝对路径 + --target，不能用 .cmd 包装器
$env:CC_aarch64_linux_android    = "$TC\bin\clang.exe"
$env:CFLAGS_aarch64_linux_android = "--target=aarch64-linux-android26 -D__ANDROID__"
$env:AR_aarch64_linux_android    = "$TC\bin\llvm-ar.exe"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$TC\bin\aarch64-linux-android26-clang.cmd"

$env:CC_x86_64_linux_android     = "$TC\bin\clang.exe"
$env:CFLAGS_x86_64_linux_android = "--target=x86_64-linux-android26 -D__ANDROID__"
$env:AR_x86_64_linux_android     = "$TC\bin\llvm-ar.exe"
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$TC\bin\x86_64-linux-android26-clang.cmd"

Write-Host "=== Building Rust core for Android ===" -ForegroundColor Cyan

# ── [1/4] aarch64 ──
Write-Host "[1/4] aarch64-linux-android ..." -ForegroundColor Yellow
& cargo build -p nion-core --target aarch64-linux-android --release 2>&1 | ForEach-Object { $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "aarch64 build failed" -ForegroundColor Red; exit 1 }

# ── [2/4] x86_64 ──
Write-Host "[2/4] x86_64-linux-android ..." -ForegroundColor Yellow
& cargo build -p nion-core --target x86_64-linux-android --release 2>&1 | ForEach-Object { $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "x86_64 build failed" -ForegroundColor Red; exit 1 }

# ── [3/4] UniFFI bindings ──
Write-Host "[3/4] Generating UniFFI bindings ..." -ForegroundColor Yellow
if (Test-Path $TmpOut) { Remove-Item $TmpOut -Recurse -Force }
& cargo run -p uniffi-bindgen-cli -- generate `
    --library "$ScriptDir\target\aarch64-linux-android\release\libnion_core.so" `
    --language kotlin --out-dir $TmpOut 2>&1 | ForEach-Object { $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "UniFFI bindgen failed" -ForegroundColor Red; exit 1 }

# ── [4/4] Copy artifacts ──
Write-Host "[4/4] Copying artifacts ..." -ForegroundColor Yellow

$JniArm = "$ScriptDir\app\app\src\main\jniLibs\arm64-v8a"
$JniX86 = "$ScriptDir\app\app\src\main\jniLibs\x86_64"
$BindDir = "$ScriptDir\app\app\src\main\java\uniffi\nion_core"

New-Item -ItemType Directory -Path $JniArm -Force | Out-Null
New-Item -ItemType Directory -Path $JniX86 -Force | Out-Null
Copy-Item "$ScriptDir\target\aarch64-linux-android\release\libnion_core.so" "$JniArm\" -Force
Copy-Item "$ScriptDir\target\x86_64-linux-android\release\libnion_core.so" "$JniX86\" -Force
Copy-Item "$TmpOut\uniffi\nion_core\nion_core.kt" "$BindDir\nion_core.kt" -Force

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor Green
Write-Host "Artifacts:" -ForegroundColor Cyan
Write-Host "  $JniArm\libnion_core.so"
Write-Host "  $JniX86\libnion_core.so"
Write-Host "  $BindDir\nion_core.kt"
Write-Host ""
Write-Host "Next: cd app && .\gradlew assembleDebug  OR  .\deploy.sh" -ForegroundColor Cyan
