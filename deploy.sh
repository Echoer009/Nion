#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Flavor 参数 ──
# 用法: ./deploy.sh [standard|character]
# 默认: standard
FLAVOR="${1:-standard}"
if [[ "$FLAVOR" != "standard" && "$FLAVOR" != "character" ]]; then
    echo "用法: $0 [standard|character]"
    echo "  standard  —— 通用 Nion 版本（默认）"
    echo "  character —— 类脑娘内置版本"
    exit 1
fi

# 将 flavor 首字母大写，用于 Gradle task 命名（如 assembleStandardDebug）
FLAVOR_CAPITALIZED="${FLAVOR^}"

# 自动检测环境：Git Bash（Windows 原生 adb.exe）或 WSL2（usbipd 透传）
# Git Bash 下 uname 输出 MINGW，WSL 下输出 Linux
if [[ "$(uname)" == MINGW* || "$(uname)" == MSYS* ]]; then
    # ---------- Windows Git Bash 环境 ----------
    ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    APK_PATH="$PROJECT_DIR/app/app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
else
    # ---------- WSL2 环境 ----------
    ADB=~/android-sdk/platform-tools/adb
    APK_PATH="$PROJECT_DIR/app/app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
fi

# character flavor 使用不同的 applicationId，需要对应不同的包名
if [[ "$FLAVOR" == "character" ]]; then
    PACKAGE="com.echonion.nion.character"
else
    PACKAGE="com.echonion.nion"
fi
ACTIVITY="com.echonion.nion/.MainActivity"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

step() { echo -e "${CYAN}[$1] $2${NC}"; }
ok()   { echo -e "${GREEN}  ✓ $1${NC}"; }
warn() { echo -e "${YELLOW}  ⚠ $1${NC}"; }
fail() { echo -e "${RED}  ✗ $1${NC}"; }

echo -e "${CYAN}Flavor: $FLAVOR (${FLAVOR_CAPITALIZED})${NC}"
echo ""

TOTAL_STEPS=4

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
TARGET_DEVICE=$(echo "$DEVICE_LIST" | grep -v 'emulator' | head -1)
if [ -z "$TARGET_DEVICE" ]; then
    TARGET_DEVICE=$(echo "$DEVICE_LIST" | head -1)
fi
ok "设备已连接 ($DEVICE_COUNT 台, 目标: $TARGET_DEVICE)"

# ---------- 2. 检查 .so 新鲜度 ----------
step 2/$TOTAL_STEPS "检查 .so 新鲜度"

STALE_SRC=$(find "$PROJECT_DIR/core/src" -name '*.rs' -newer "$PROJECT_DIR/app/app/src/main/jniLibs/arm64-v8a/libnion_core.so" 2>/dev/null | head -1)
if [ -n "$STALE_SRC" ]; then
    warn "Rust 源码比 .so 更新，自动重新编译 ..."
    cd "$PROJECT_DIR" && bash build-android.sh
    if [ $? -ne 0 ]; then
        fail "Rust 编译失败，中止部署"
        exit 1
    fi
    ok "Rust 编译完成"
else
    ok ".so 文件已是最新"
fi

# ---------- 3. 构建 APK ----------
step 3/$TOTAL_STEPS "构建 APK ($FLAVOR)"
if ! (cd "$PROJECT_DIR/app" && ./gradlew "assemble${FLAVOR_CAPITALIZED}Debug" 2>&1); then
    fail "构建失败，中止部署"
    exit 1
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
ok "构建完成 ($APK_SIZE)"

# ---------- 4. 安装并启动 ----------
step 4/$TOTAL_STEPS "安装到手机"

ADB_TARGET=()
if [ "$DEVICE_COUNT" -gt 1 ]; then
    ADB_TARGET=(-s "$TARGET_DEVICE")
fi

"$ADB" "${ADB_TARGET[@]}" shell am force-stop "$PACKAGE" 2>/dev/null || true

# adb install 在部分厂商 ROM（如 vivo 深度检查）下可能误报失败，
# 实际安装成功但 adb 返回非零退出码。因此用 pm path 二次确认是否真正装上。
install_and_verify() {
    "$ADB" "${ADB_TARGET[@]}" install -r "$APK_PATH" 2>&1 || true
    # 无论 adb install 返回什么，用 pm path 确认 App 是否已安装
    sleep 2
    "$ADB" "${ADB_TARGET[@]}" shell pm path "$PACKAGE" 2>/dev/null | grep -q "package:" 2>/dev/null
}

if install_and_verify; then
    ok "安装完成"
else
    warn "首次安装未确认，重试中..."
    "$ADB" kill-server 2>/dev/null || true
    sleep 1
    "$ADB" start-server 2>/dev/null || true
    sleep 2
    "$ADB" "${ADB_TARGET[@]}" shell am force-stop "$PACKAGE" 2>/dev/null || true
    if install_and_verify; then
        ok "安装完成"
    else
        fail "安装失败，请手动检查"
        exit 1
    fi
fi

"$ADB" "${ADB_TARGET[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1
ok "应用已启动"

echo ""
echo -e "${GREEN}部署完成！Flavor: $FLAVOR${NC}"
echo -e "${GREEN}查看日志: $ADB logcat -s NionApp:D TaskViewModel:D${NC}"
