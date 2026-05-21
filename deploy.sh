#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADB=~/android-sdk/platform-tools/adb
ADB_APK="$PROJECT_DIR/app/app/build/outputs/apk/debug/app-debug.apk"
POWERSHELL="/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"
PACKAGE="com.echonion.nion"
ACTIVITY="$PACKAGE/.MainActivity"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

step() { echo -e "${CYAN}[1/5] $1${NC}"; }
ok()   { echo -e "${GREEN}  ✓ $1${NC}"; }
warn() { echo -e "${YELLOW}  ⚠ $1${NC}"; }
fail() { echo -e "${RED}  ✗ $1${NC}"; }

# ---------- 1. USB attach ----------
step "检查 USB 设备"

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

adb_has_device() {
    sudo "$ADB" devices 2>/dev/null | grep -q -v 'List of devices' | grep -q -v '^$'
}

if is_attached; then
    ok "USB 设备已透传"
else
    BUSID=$(find_phone_busid)
    if [ -z "$BUSID" ]; then
        fail "未找到 Android 设备，请确认手机已通过 USB 连接"
        exit 1
    fi
    warn "正在透传 USB 设备 (busid=$BUSID)..."
    "$POWERSHELL" -Command "Start-Process usbipd -ArgumentList 'attach','--wsl','--busid','$BUSID' -Verb RunAs -Wait" 2>/dev/null
    sleep 2
    if is_attached; then
        ok "USB 透传成功"
    else
        fail "USB 透传失败，请手动在 Windows PowerShell (管理员) 执行:"
        echo "  usbipd bind --busid $BUSID"
        echo "  usbipd attach --wsl --busid $BUSID"
        exit 1
    fi
fi

# ---------- 2. Build ----------
step "构建 APK"
BUILD_OUTPUT=$(cd "$PROJECT_DIR/app" && ./gradlew assembleDebug 2>&1)
BUILD_EXIT=$?
if [ $BUILD_EXIT -ne 0 ]; then
    fail "构建失败:"
    echo "$BUILD_OUTPUT" | tail -30
    exit 1
fi
APK_SIZE=$(du -h "$ADB_APK" | cut -f1)
ok "构建完成 ($APK_SIZE)"

# ---------- 3. Install ----------
step "安装到手机"
sudo "$ADB" kill-server 2>/dev/null || true
sudo "$ADB" start-server 2>/dev/null
sleep 1

DEVICES=$(sudo "$ADB" devices 2>/dev/null | grep -v 'List of devices' | grep -v '^$' | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    fail "未检测到设备"
    echo "  请在手机上允许 USB 调试授权"
    exit 1
fi

# 直接执行 install，不通过管道，避免 tail 导致阻塞
INSTALL_OUTPUT=$(sudo "$ADB" install -r "$ADB_APK" 2>&1) || true
if echo "$INSTALL_OUTPUT" | grep -q "Success"; then
    ok "安装完成"
else
    fail "安装失败: $INSTALL_OUTPUT"
    exit 1
fi

# ---------- 4. Restart app ----------
step "重启应用"
sudo "$ADB" shell am force-stop "$PACKAGE" 2>/dev/null
sudo "$ADB" logcat -c 2>/dev/null
sudo "$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1
ok "应用已启动"

echo -e "${GREEN}部署完成，查看日志: sudo ~/android-sdk/platform-tools/adb logcat -s NionApp:D TaskViewModel:D${NC}"
