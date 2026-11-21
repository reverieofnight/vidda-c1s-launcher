#!/bin/sh
# 编译并链接 libx264jni.so（x264_jni.c + libx264.a）
#
# 用法：
#   sh build/native/build-jni.sh
# 可用环境变量覆盖：
#   NDK          NDK 根目录（默认 ~/dev-tools/android-ndk-r25c）
#   X264_PREFIX  x264 静态库与头文件目录（默认 ~/dev-tools/x264-arm64）
#   API          最低 Android API（默认 28）
set -eu

NDK=${NDK:-$HOME/dev-tools/android-ndk-r25c}
X264_PREFIX=${X264_PREFIX:-$HOME/dev-tools/x264-arm64}
API=${API:-28}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/build/launcher/libs/arm64-v8a/libx264jni.so"

TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TC" ] || { echo "找不到 NDK 工具链，请设置 NDK 环境变量" >&2; exit 1; }
[ -f "$X264_PREFIX/lib/libx264.a" ] || { echo "找不到 libx264.a，请先运行 build-x264.sh" >&2; exit 1; }

export PATH="$TC/bin:$PATH"
mkdir -p "$(dirname "$OUT")"

"$TC/bin/aarch64-linux-android${API}-clang" \
  -shared -fPIC -O2 \
  -I"$X264_PREFIX/include" \
  "$ROOT/build/launcher/jni/x264_jni.c" \
  "$X264_PREFIX/lib/libx264.a" \
  -llog -lm \
  -o "$OUT"

echo "已生成：$OUT"
ls -l "$OUT"
