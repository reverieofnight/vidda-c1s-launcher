# 第三方组件与许可说明（NOTICE）

本仓库包含/依赖以下第三方组件。**仓库当前未声明自有许可**，以下仅记录现状与义务。

## 1. x264（GPL-2.0-or-later）—— 强传染，注意

- 组件：`build/launcher/jni/x264_jni.c` 编译出的 `build/launcher/libs/arm64-v8a/libx264jni.so`，内部静态链接 x264。
- x264 许可证：**GPL-2.0-or-later**（见 x264 源码 `COPYING` 与 `x264.h` 头部声明），另有 x264 LLC 的商业授权可替代。
- 影响：JNI 与 x264 静态链接构成结合作品；**一旦对外分发本 APK 或其源码，整体即落入 GPL**，需按 GPL 提供完整对应源码（含构建脚本、本仓库中所有改动）并附许可证文本。不对外分发（仅自己使用）则不触发。
- 重新编译 JNI 需要 x264 源码（`~/dev-tools/x264-stable` 或 `git clone https://code.videolan.org/videolan/x264`）与 NDK r25c（`~/dev-tools/android-ndk-r25c`）。

## 2. H.264 专利（与 x264 版权许可无关）

- H.264/AVC 标准必要专利由 Via LA（原 MPEG LA）等专利池成员持有；**x264 的 GPL/商业许可均不包含专利许可，也不提供 pass-through**。
- x264 商业授权（x264 LLC）只解决版权问题，不解决专利问题。
- 个人非商业自用不涉及分发与出货；若对外分发产品（含公开仓库形式的软件分发），专利许可理论上由分发方自行向专利池取得。

## 3. Android SDK 组件

构建时使用 `aapt2`、`d8`、`zipalign`、`apksigner`、`android.jar`（Android SDK，Apache-2.0 / 各自条款），以及 `local/platform-tools` 中的 `adb`（Google 分发，Apache-2.0）。这些工具不入库，由使用方自行安装。

## 4. 其它运行时依赖

- `ffmpeg`：仅在构建期裁切壁纸（若存在），不随 APK 分发。
- 壁纸图像（`local/背景图/`、`local/背景图4k/`）属于临时资源，**不在本仓库中**；其版权归各自作者所有，公开或再分发前请自行确认授权。
