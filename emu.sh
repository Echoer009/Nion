#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 模拟器快捷脚本（macOS 本机开发用）──
# 用法:
#   ./emu.sh start                    启动 nion_api36 模拟器（带可见窗口），等待系统完成启动
#   ./emu.sh deploy [standard|character]  构建 debug APK 并安装到模拟器（install -r，不卸载、不清数据）
#   ./emu.sh seed-music               推送测试歌曲到 /sdcard/Music/NionTest 并触发媒体扫描（/tmp/nion-music 为空时自动生成 5 首带封面的测试曲）
#   ./emu.sh record <seconds> <outdir>  用 adb screenrecord 录屏，结束后拉回并用 ffmpeg 按原生帧率逐帧导出 PNG
#   ./emu.sh shot <file>              截取当前屏幕到指定文件（PNG）
#
# 注意:
#   - 一律使用 ~/tools/android-sdk/platform-tools/adb（PATH 里可能先有 scrcpy 带的 adb）
#   - 绝不卸载应用 / 清除数据（pm clear 严禁），一律 adb install -r 覆盖安装
#   - 绝不把动画缩放设为 0（Compose 动画依赖 animator_duration_scale=1）

ADB="$HOME/tools/android-sdk/platform-tools/adb"
EMULATOR="$HOME/tools/android-sdk/emulator/emulator"
AVD_NAME="nion_api36"
SYS_IMAGE="system-images;android-36;google_apis;arm64-v8a"
EMU_LOG="/tmp/nion-emulator.log"
PACKAGE="com.echonion.nion"
ACTIVITY="com.echonion.nion/com.echonion.nion.MainActivity"
MUSIC_SRC="/tmp/nion-music"

# 构建所需 JDK：app/build.gradle.kts 声明 kotlin.jvmToolchain(25)，Gradle 必须能找到
# 精确的 Java 25（本机 17/26 都不匹配）。~/tools 下的 Corretto 25 存在时优先使用
JDK25_HOME="$HOME/tools/amazon-corretto-25.jdk/Contents/Home"
if [ -x "$JDK25_HOME/bin/java" ]; then
    export JAVA_HOME="$JDK25_HOME"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/tools/android-sdk}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
step() { echo -e "${CYAN}[$1] $2${NC}"; }
ok()   { echo -e "${GREEN}  ✓ $1${NC}"; }
warn() { echo -e "${YELLOW}  ⚠ $1${NC}"; }
fail() { echo -e "${RED}  ✗ $1${NC}"; }

# 选取当前在线的模拟器序列号（优先 emulator-*）
emu_serial() {
    "$ADB" devices | grep -E 'emulator-[0-9]+[[:space:]]+device$' | head -1 | awk '{print $1}'
}

# 等待指定设备完成开机（sys.boot_completed=1 且 WindowManager 就绪）
wait_boot() {
    local serial="$1"
    step "2" "等待系统启动完成 ($serial) ..."
    "$ADB" -s "$serial" wait-for-device
    for _ in $(seq 1 120); do
        local boot=$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$boot" = "1" ]; then
            # 再等包管理器就绪，避免开机后立刻装 APK 报错
            if "$ADB" -s "$serial" shell pm path com.android.settings >/dev/null 2>&1; then
                ok "系统启动完成"
                return 0
            fi
        fi
        sleep 2
    done
    fail "等待开机超时（240s）"
    exit 1
}

# ---------- 子命令: start ----------
cmd_start() {
    local serial
    serial="$(emu_serial || true)"
    if [ -n "$serial" ]; then
        ok "模拟器已在运行 ($serial)"
    else
        step "1" "启动 AVD ${AVD_NAME}（可见窗口 / GPU=${NION_EMU_GPU:-swiftshader_indirect} / 关闭开机动画）..."
        # GPU 默认软件渲染 swiftshader_indirect：macOS 上 API 36 用 -gpu host 实测会
        # "bad color buffer handle" 后 QEMU 主循环挂死（emulator 日志也提示 host GL 在
        # macOS 已弃用）。如需尝试硬件渲染：NION_EMU_GPU=host ./emu.sh start
        # nohup + & 让模拟器脱离本脚本会话存活；日志落 /tmp/nion-emulator.log
        nohup "$EMULATOR" -avd "$AVD_NAME" -gpu "${NION_EMU_GPU:-swiftshader_indirect}" -no-boot-anim >"$EMU_LOG" 2>&1 &
        ok "模拟器进程已拉起 (pid $!, 日志: $EMU_LOG)"
        serial="$(emu_serial || true)"
        for _ in $(seq 1 15); do
            [ -n "$serial" ] && break
            sleep 2
            serial="$(emu_serial || true)"
        done
        if [ -z "$serial" ]; then
            fail "模拟器 30s 内未上线，请查看 $EMU_LOG"
            exit 1
        fi
    fi
    wait_boot "$serial"
    # 打印三档动画缩放，确认全部为 1（Compose 动画正确呈现的前提）
    step "3" "检查动画缩放（必须全为 1）..."
    "$ADB" -s "$serial" shell settings get global window_animation_scale
    "$ADB" -s "$serial" shell settings get global transition_animation_scale
    "$ADB" -s "$serial" shell settings get global animator_duration_scale
    ok "模拟器就绪 ($serial)"
}

# ---------- 子命令: deploy ----------
cmd_deploy() {
    local flavor="${1:-standard}"
    if [ "$flavor" != "standard" ] && [ "$flavor" != "character" ]; then
        fail "用法: $0 deploy [standard|character]"
        exit 1
    fi
    local serial
    serial="$(emu_serial || true)"
    if [ -z "$serial" ]; then
        fail "模拟器未运行，请先执行 ./emu.sh start"
        exit 1
    fi
    if [ "$flavor" == "character" ]; then
        # character flavor 有 applicationIdSuffix，Activity 类名不变、包名带后缀
        PACKAGE="com.echonion.nion.character"
        ACTIVITY="com.echonion.nion.character/com.echonion.nion.MainActivity"
    fi
    step "1" "构建 ${flavor}Debug APK ..."
    # macOS 自带 bash 3.2 不支持 ${var^}，首字母大写用 tr 实现（standard → Standard）
    local flavor_cap
    flavor_cap="$(echo "${flavor:0:1}" | tr '[:lower:]' '[:upper:]')${flavor:1}"
    (cd "$PROJECT_DIR/app" && ./gradlew ":app:assemble${flavor_cap}Debug")
    local apk="$PROJECT_DIR/app/app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk"
    step "2" "覆盖安装（保留数据）到 $serial ..."
    "$ADB" -s "$serial" install -r "$apk"
    step "3" "启动应用 ..."
    "$ADB" -s "$serial" shell am force-stop "$PACKAGE" 2>/dev/null || true
    "$ADB" -s "$serial" shell am start -n "$ACTIVITY"
    ok "部署完成 ($flavor debug)"
}

# ---------- 子命令: seed-music ----------
cmd_seed_music() {
    local serial
    serial="$(emu_serial || true)"
    if [ -z "$serial" ]; then
        fail "模拟器未运行，请先执行 ./emu.sh start"
        exit 1
    fi
    step "1" "推送测试音乐到 /sdcard/Music/NionTest ..."
    # /tmp/nion-music 没有 mp3 时现场生成 5 首：120s 正弦音 + ID3 标题/艺术家/专辑 +
    # 内嵌纯色封面（深绿/深蓝/红/紫/橙，饱和度高、与米色主题差异大，方便肉眼与
    # 像素脚本区分 morph 期间的歌曲色和米色）
    if ! ls "$MUSIC_SRC"/*.mp3 >/dev/null 2>&1; then
        mkdir -p "$MUSIC_SRC"
        local i=0 spec name rest col freq
        for spec in "深林|0x1B5E20|440" "海蓝|0x0D47A1|523" "赤焰|0xB71C1C|587" "紫夜|0x4A148C|659" "金秋|0xE65100|698"; do
            i=$((i + 1)); name="${spec%%|*}"; rest="${spec#*|}"; col="${rest%%|*}"; freq="${rest##*|}"
            /opt/homebrew/bin/ffmpeg -loglevel error -y -f lavfi \
                -i "color=c=${col}:s=500x500,drawbox=x=60:y=60:w=380:h=380:color=white@0.25:t=fill" \
                -frames:v 1 "$MUSIC_SRC/cover$i.jpg"
            /opt/homebrew/bin/ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=${freq}:duration=120" \
                -i "$MUSIC_SRC/cover$i.jpg" -map 0:a -map 1:v -c:a libmp3lame -b:a 96k -c:v mjpeg \
                -id3v2_version 3 -metadata title="测试曲${i} ${name}" -metadata artist="Nion测试" \
                -metadata album="测试专辑${i}" -metadata:s:v title="Album cover" \
                -metadata:s:v comment="Cover (front)" "$MUSIC_SRC/track$i.mp3"
        done
        ok "已生成 5 首带封面的测试曲到 $MUSIC_SRC"
    fi
    "$ADB" -s "$serial" shell mkdir -p /sdcard/Music/NionTest
    for f in "$MUSIC_SRC"/*.mp3; do
        [ -e "$f" ] || { fail "$MUSIC_SRC 下没有 mp3"; exit 1; }
        "$ADB" -s "$serial" push "$f" /sdcard/Music/NionTest/
    done
    step "2" "触发 MediaStore 扫描 ..."
    for f in "$MUSIC_SRC"/*.mp3; do
        "$ADB" -s "$serial" shell am broadcast \
            -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
            -d "file:///sdcard/Music/NionTest/$(basename "$f")" >/dev/null
    done
    # 再用 MediaScanner 批量扫一遍目录，双保险
    "$ADB" -s "$serial" shell cmd media_scanner scan /sdcard/Music 2>/dev/null || true
    sleep 2
    local n=$("$ADB" -s "$serial" shell content query --uri content://media/external/audio/media --projection _display_name 2>/dev/null | grep -c mp3 || true)
    ok "媒体库中 mp3 数量: $n"
}

# ---------- 子命令: record ----------
cmd_record() {
    local seconds="$1" outdir="$2"
    local serial
    serial="$(emu_serial || true)"
    if [ -z "$serial" ]; then
        fail "模拟器未运行，请先执行 ./emu.sh start"; exit 1; fi
    if [ -z "$seconds" ] || [ -z "$outdir" ]; then
        fail "用法: $0 record <seconds> <outdir>"; exit 1; fi
    mkdir -p "$outdir"
    local tmpmp4="/tmp/nion_rec_$$.mp4"
    step "1" "screenrecord 录制 ${seconds}s ..."
    # --bugreport 不会改变内容；不压缩比特率，保证帧分析精度
    "$ADB" -s "$serial" shell screenrecord --time-limit "$seconds" /sdcard/nion_rec.mp4
    "$ADB" -s "$serial" pull /sdcard/nion_rec.mp4 "$tmpmp4" >/dev/null
    "$ADB" -s "$serial" shell rm /sdcard/nion_rec.mp4
    step "2" "ffmpeg 按视频原生帧率逐帧导出 PNG 到 $outdir ..."
    local fps
    fps=$(/opt/homebrew/bin/ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$tmpmp4")
    ok "视频帧率: $fps"
    # -fps_mode passthrough：每个视频帧原样导出（新版 ffmpeg 已移除 -vsync 参数）
    /opt/homebrew/bin/ffmpeg -loglevel error -y -i "$tmpmp4" -fps_mode passthrough "$outdir/f%04d.png"
    local frames
    frames=$(ls "$outdir" | grep -c '^f.*png$' || true)
    mv "$tmpmp4" "$outdir/rec.mp4"
    ok "共导出 $frames 帧到 ${outdir}（含 rec.mp4 原片）"
}

# ---------- 子命令: shot ----------
cmd_shot() {
    local file="$1"
    if [ -z "$file" ]; then
        fail "用法: $0 shot <file.png>"; exit 1; fi
    local serial
    serial="$(emu_serial || true)"
    if [ -z "$serial" ]; then
        fail "模拟器未运行，请先执行 ./emu.sh start"; exit 1; fi
    mkdir -p "$(dirname "$file")" 2>/dev/null || true
    # exec-out screencap 直接拿二进制流，避免 shell 端 \n 转义破坏 PNG
    "$ADB" -s "$serial" exec-out screencap -p > "$file"
    ok "截图已保存: $file"
}

case "${1:-}" in
    start)       cmd_start ;;
    deploy)      shift; cmd_deploy "${1:-standard}" ;;
    seed-music)  cmd_seed_music ;;
    record)      shift; cmd_record "${1:-}" "${2:-}" ;;
    shot)        shift; cmd_shot "${1:-}" ;;
    *)
        echo "用法: $0 {start|deploy [standard|character]|seed-music|record <seconds> <outdir>|shot <file>}"
        exit 1
        ;;
esac
