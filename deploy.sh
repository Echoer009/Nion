#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 自动检测环境：Git Bash（Windows 原生 adb.exe）或 WSL2（usbipd 透传）
# Git Bash 下 uname 输出 MINGW，WSL 下输出 Linux
if [[ "$(uname)" == MINGW* || "$(uname)" == MSYS* ]]; then
    # ---------- Windows Git Bash 环境 ----------
    # adb.exe 路径：$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe
    ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    APK_PATH="/d/nion/app/app/build/outputs/apk/debug/app-debug.apk"
else
    # ---------- WSL2 环境 ----------
    ADB=~/android-sdk/platform-tools/adb
    APK_PATH="$PROJECT_DIR/app/app/build/outputs/apk/debug/app-debug.apk"
fi

PACKAGE="com.echonion.nion"
ACTIVITY="$PACKAGE/.MainActivity"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

step() { echo -e "${CYAN}[$1] $2${NC}"; }
ok()   { echo -e "${GREEN}  ✓ $1${NC}"; }
warn() { echo -e "${YELLOW}  ⚠ $1${NC}"; }
fail() { echo -e "${RED}  ✗ $1${NC}"; }

TOTAL_STEPS=3

# ---------- 1. 检查设备 ----------
step 1/$TOTAL_STEPS "检查设备"

# WSL2 环境需要先通过 usbipd 将 USB 从 Windows 透传到 WSL
if [[ "$(uname)" == Linux ]]; then
    POWERSHELL="/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"

    find_phone_busid() {
        "$POWERSHELL" -Command "usbipd list" 2>/dev/null \
            | grep -i -E 'android|adb' \
            | grep -v 'Attached' \
            | awk '{print $1}' \
            | head -1
    }

    is_attached() {
        "$POWERSHELL" -Command "usbipd list" 2>/dev/null \
            | grep -i -E 'android|adb' \
            | grep -q 'Attached'
    }
fi

# 检查 adb 是否存在
if ! command -v "$ADB" &>/dev/null && [ ! -f "$ADB" ]; then
    fail "未找到 adb，请确认 Android SDK 路径正确: $ADB"
    exit 1
fi

# WSL2: 尝试 usbipd 透传
if [[ "$(uname)" == Linux ]]; then
    if ! is_attached 2>/dev/null && ! "$ADB" devices 2>/dev/null | grep -v 'List of devices' | grep -v '^$' | grep -q .; then
        BUSID=$(find_phone_busid)
        if [ -z "$BUSID" ]; then
            fail "未找到 Android 设备，请确认手机已通过 USB 连接"
            exit 1
        fi
        warn "正在透传 USB 设备 (busid=$BUSID)..."
        "$POWERSHELL" -Command "Start-Process usbipd -ArgumentList 'attach','--wsl','--busid','$BUSID' -Verb RunAs -Wait" 2>/dev/null
        sleep 2
    fi
fi

# 验证设备已连接
DEVICE_OUTPUT=$("$ADB" devices 2>/dev/null)
# 只统计状态为 "device" 的项（排除 unauthorized、offline 等非 Android 设备）
DEVICE_LIST=$(echo "$DEVICE_OUTPUT" | grep -E '[[:space:]]+device$' | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICE_LIST" | grep -c . || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    fail "未检测到设备"
    echo "  请检查："
    echo "    1. 手机已通过 USB 连接电脑"
    echo "    2. 手机已开启「USB 调试」权限"
    echo "    3. 手机上已允许 USB 调试授权弹窗"
    exit 1
fi
# 多设备时取第一个真机（跳过 emulator）
TARGET_DEVICE=$(echo "$DEVICE_LIST" | grep -v 'emulator' | head -1)
if [ -z "$TARGET_DEVICE" ]; then
    TARGET_DEVICE=$(echo "$DEVICE_LIST" | head -1)
fi
ok "设备已连接 ($DEVICE_COUNT 台, 目标: $TARGET_DEVICE)"

# ---------- 2. 构建 APK ----------
step 2/$TOTAL_STEPS "构建 APK"
BUILD_OUTPUT=$(cd "$PROJECT_DIR/app" && ./gradlew assembleDebug 2>&1)
BUILD_EXIT=$?
if [ $BUILD_EXIT -ne 0 ]; then
    fail "构建失败:"
    echo "$BUILD_OUTPUT" | tail -30
    exit 1
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
ok "构建完成 ($APK_SIZE)"

# ---------- 3. 安装并启动 ----------
step 3/$TOTAL_STEPS "安装到手机"

# 构建 adb 目标参数（多设备时需要 -s 指定）
ADB_TARGET=()
if [ "$DEVICE_COUNT" -gt 1 ]; then
    ADB_TARGET=(-s "$TARGET_DEVICE")
fi

# 先 force-stop 防止旧 session 导致 install 卡死
"$ADB" "${ADB_TARGET[@]}" shell am force-stop "$PACKAGE" 2>/dev/null || true

# 安装 APK，超时 60 秒
if ! timeout 60 "$ADB" "${ADB_TARGET[@]}" install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
    # 重试一次：kill-server 后重新连接
    warn "首次安装失败，重试中..."
    "$ADB" kill-server 2>/dev/null || true
    sleep 1
    "$ADB" start-server 2>/dev/null || true
    sleep 2
    "$ADB" "${ADB_TARGET[@]}" shell am force-stop "$PACKAGE" 2>/dev/null || true
    if ! timeout 60 "$ADB" "${ADB_TARGET[@]}" install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
        fail "安装失败，请手动检查"
        exit 1
    fi
fi
ok "安装完成"

# 启动应用
"$ADB" "${ADB_TARGET[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1
ok "应用已启动"

echo ""
echo -e "${GREEN}部署完成！查看日志: $ADB logcat -s NionApp:D TaskViewModel:D${NC}"
