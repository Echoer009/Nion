#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 自动检测环境：Git Bash（Windows）或 WSL2
if [[ "$(uname)" == MINGW* || "$(uname)" == MSYS* ]]; then
    # Windows Git Bash
    NDK_PATH="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/ndk/27.0.12077973"
    TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/windows-x86_64"
    CLANG_SUFFIX=".cmd"
    AR_NAME="llvm-ar.exe"
    LINKER_SUFFIX=".cmd"
    # Windows 下 /tmp 映射到 Temp 目录
    UNIFFI_OUT_DIR="$TEMP/uniffi-output"
else
    # WSL2
    NDK_PATH="${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.0.12077973"
    TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    CLANG_SUFFIX=""
    AR_NAME="llvm-ar"
    LINKER_SUFFIX=""
    UNIFFI_OUT_DIR="/tmp/uniffi-output"
fi

export CC_aarch64_linux_android="${TOOLCHAIN}/bin/aarch64-linux-android26-clang${CLANG_SUFFIX}"
export AR_aarch64_linux_android="${TOOLCHAIN}/bin/${AR_NAME}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${TOOLCHAIN}/bin/aarch64-linux-android26-clang${LINKER_SUFFIX}"
export CC_x86_64_linux_android="${TOOLCHAIN}/bin/x86_64-linux-android26-clang${CLANG_SUFFIX}"
export AR_x86_64_linux_android="${TOOLCHAIN}/bin/${AR_NAME}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${TOOLCHAIN}/bin/x86_64-linux-android26-clang${LINKER_SUFFIX}"

echo "=== Building Rust core for Android ($(uname)) ==="

echo "[1/4] aarch64-linux-android ..."
cd "$SCRIPT_DIR"
cargo build -p nion-core --target aarch64-linux-android --release \
  --config "target.aarch64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/26', '-C', 'link-arg=-Wl,-z,max-page-size=16384', '-C', 'link-arg=-Wl,-z,common-page-size=16384']"

echo "[2/4] x86_64-linux-android ..."
cargo build -p nion-core --target x86_64-linux-android --release \
  --config "target.x86_64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/x86_64-linux-android/26', '-C', 'link-arg=-Wl,-z,max-page-size=16384', '-C', 'link-arg=-Wl,-z,common-page-size=16384']"

echo "[3/4] Generating UniFFI bindings ..."
rm -rf "$UNIFFI_OUT_DIR"
cargo run -p uniffi-bindgen-cli \
  generate --library "$SCRIPT_DIR/target/aarch64-linux-android/release/libnion_core.so" \
  --language kotlin --out-dir "$UNIFFI_OUT_DIR"

echo "[4/4] Copying artifacts ..."
cp "$SCRIPT_DIR/target/aarch64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a/"
cp "$SCRIPT_DIR/target/x86_64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/x86_64/"
cp "$UNIFFI_OUT_DIR/uniffi/nion_core/nion_core.kt" \
   "$SCRIPT_DIR/app/app/src/main/java/uniffi/nion_core/nion_core.kt"

# 校验：确保 .so 和 .kt 比核心源码新，否则发出警告
CORE_SRC=$(find "$SCRIPT_DIR/core/src" -name '*.rs' -newer "$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a/libnion_core.so" 2>/dev/null | head -1)
if [ -n "$CORE_SRC" ]; then
    echo "⚠ WARNING: core/src 中有比 .so 更新的文件，可能需要重新编译"
fi

echo "=== Done! ==="
