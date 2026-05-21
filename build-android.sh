#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NDK_PATH="${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.0.12077973"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"

export PATH="$TOOLCHAIN/bin:$PATH"
export CC_aarch64_linux_android=aarch64-linux-android26-clang
export AR_aarch64_linux_android=llvm-ar
export CC_x86_64_linux_android=x86_64-linux-android26-clang
export AR_x86_64_linux_android=llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android26-clang
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=x86_64-linux-android26-clang

echo "=== Building Rust core for Android ==="

echo "[1/4] aarch64-linux-android ..."
cd "$SCRIPT_DIR"
cargo build -p nion-core --target aarch64-linux-android --release \
  --config "target.aarch64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/26']"

echo "[2/4] x86_64-linux-android ..."
cargo build -p nion-core --target x86_64-linux-android --release \
  --config "target.x86_64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/x86_64-linux-android/26']"

echo "[3/4] Generating UniFFI bindings ..."
cargo run -p uniffi-bindgen-cli \
  generate --library "$SCRIPT_DIR/target/aarch64-linux-android/release/libnion_core.so" \
  --language kotlin --out-dir /tmp/uniffi-output

echo "[4/4] Copying artifacts ..."
cp "$SCRIPT_DIR/target/aarch64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a/"
cp "$SCRIPT_DIR/target/x86_64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/x86_64/"
cp /tmp/uniffi-output/uniffi/nion_core/nion_core.kt \
   "$SCRIPT_DIR/app/app/src/main/java/uniffi/nion_core/nion_core.kt"

echo "=== Done! Run: rsync -av --exclude='target' --exclude='.gradle' --exclude='.kotlin' --exclude='app/app/build' --exclude='.idea' $SCRIPT_DIR/ /mnt/d/nion/ ==="
