# 原生库构建（libx264jni.so）

4K 静态壁纸依赖 x264 把图片编码成 H.264/MP4。相关源码与构建脚本都在仓库内，可完整复现。

## 组成

| 文件 | 说明 |
|---|---|
| `build/launcher/jni/x264_jni.c` | 本项目编写的 JNI 胶水层：JNI 入口、编码参数、I420 填图、MP4 封装 |
| `build/native/build-x264.sh` | 用 NDK 交叉编译 x264 静态库 `libx264.a` |
| `build/native/build-jni.sh` | 把 `x264_jni.c` 与 `libx264.a` 链接成 `libx264jni.so` |
| `build/launcher/libs/arm64-v8a/libx264jni.so` | 构建产物，直接打包进 APK |

## x264 版本

- 上游：<https://code.videolan.org/videolan/x264.git>
- 分支：`stable`
- 提交：`b35605ace3ddf7c1a5d67a2eb553f034aef41d55`
- 本项目未修改 x264 源码。

获取完全相同的源码：

```sh
git clone https://code.videolan.org/videolan/x264.git
cd x264
git checkout b35605ace3ddf7c1a5d67a2eb553f034aef41d55
```

## 环境

- NDK r25c（<https://developer.android.com/ndk/downloads>）
- 目标平台：Android arm64-v8a，最低 API 28
- 构建主机：Windows（Git Bash）、Linux、macOS 均可，脚本会自动选择对应工具链目录

## 构建

```sh
# 1. 编 x264 静态库（默认输出 ~/dev-tools/x264-arm64）
NDK=~/dev-tools/android-ndk-r25c \
X264_SRC=~/dev-tools/x264-stable \
sh build/native/build-x264.sh

# 2. 编 JNI 动态库，直接覆盖仓库内的 .so
NDK=~/dev-tools/android-ndk-r25c \
X264_PREFIX=~/dev-tools/x264-arm64 \
sh build/native/build-jni.sh
```

构建完成后重新执行 `build/build-launcher.sh` 打包 APK。

## 校验

```sh
# 确认导出符号与新包名一致
llvm-nm --defined-only build/launcher/libs/arm64-v8a/libx264jni.so | grep Java_
# 期望：Java_cn_rainynight_dream_LauncherActivity_encodeI4204K
```

## 许可

x264 采用 **GPL-2.0-or-later**，本仓库整体同样以 GPL-2.0-or-later 发布，详见根目录 `LICENSE` 与 `NOTICE.md`。H.264 标准涉及的专利许可与 x264 的版权许可无关。
