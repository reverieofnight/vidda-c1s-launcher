#!/bin/sh
# 交叉编译 Android arm64 静态 x264（GPL-2.0-or-later）
#
# 用法：
#   sh build/native/build-x264.sh
# 可用环境变量覆盖：
#   NDK        NDK 根目录（默认 ~/dev-tools/android-ndk-r25c）
#   X264_SRC   x264 源码目录（默认 ~/dev-tools/x264-stable）
#   BUILD_DIR  构建目录（默认 ~/dev-tools/x264-build-arm64）
#   PREFIX     安装目录（默认 ~/dev-tools/x264-arm64）
#   API        最低 Android API（默认 28）
#   JOBS       并行编译数（默认 4）
set -eu

NDK=${NDK:-$HOME/dev-tools/android-ndk-r25c}
X264_SRC=${X264_SRC:-$HOME/dev-tools/x264-stable}
BUILD_DIR=${BUILD_DIR:-$HOME/dev-tools/x264-build-arm64}
PREFIX=${PREFIX:-$HOME/dev-tools/x264-arm64}
API=${API:-28}
JOBS=${JOBS:-4}

TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TC" ] || { echo "找不到 NDK 工具链，请设置 NDK 环境变量" >&2; exit 1; }
[ -f "$X264_SRC/configure" ] || { echo "找不到 x264 源码，请设置 X264_SRC" >&2; exit 1; }

export PATH="$TC/bin:$NDK/prebuilt/windows-x86_64/bin:$NDK/prebuilt/linux-x86_64/bin:$PATH"

mkdir -p "$BUILD_DIR" "$PREFIX"
cd "$BUILD_DIR"

CC="aarch64-linux-android${API}-clang" \
"$X264_SRC/configure" \
  --host=aarch64-linux-android \
  --cross-prefix=llvm- \
  --enable-static \
  --disable-cli \
  --disable-opencl \
  --enable-pic \
  --prefix="$PREFIX"

make -j"$JOBS"

# 个别 MSYS 环境下 make install 的 ranlib 会因路径失败，失败时直接拷贝产物
if ! make install; then
  echo "make install 失败，改为直接拷贝产物"
  mkdir -p "$PREFIX/lib" "$PREFIX/include"
  cp libx264.a "$PREFIX/lib/"
  cp x264.h x264_config.h "$PREFIX/include/"
fi

echo "x264 静态库已生成：$PREFIX/lib/libx264.a"
