# 第三方组件与许可说明（NOTICE）

本仓库整体以 **GPL-2.0-or-later** 发布，许可证全文见根目录 [LICENSE](LICENSE)。采用该许可证的原因是：4K 静态壁纸的原生库静态链接了 GPL 的 x264，整个桌面因此作为结合作品一并开放。

## 1. 本仓库自有代码

- 范围：`build/launcher/src/`、`build/launcher/jni/x264_jni.c`、`build/launcher/www/`、`build/` 与 `tools/` 下的脚本、全部文档。
- 许可：**GPL-2.0-or-later**。
- 版权：Dream 桌面项目贡献者，2026。
- 完整对应源码：本仓库即完整源码，包括 `build/native/` 下可复现原生库的构建脚本。

## 2. x264（GPL-2.0-or-later）

- 用途：把 4K 图片编码成 H.264/MP4，供 4K 视频通道显示静态壁纸；静态链接进 `build/launcher/libs/arm64-v8a/libx264jni.so`。
- 上游：<https://code.videolan.org/videolan/x264.git>
- 版本：`stable` 分支，提交 `b35605ace3ddf7c1a5d67a2eb553f034aef41d55`。
- 修改：**未修改 x264 源码**。
- 许可证：GPL-2.0-or-later（见 x264 源码内 `COPYING` 与 `x264.h` 头部声明）；可向 x264 LLC 购买商业授权替代。
- 复现方式：`build/native/README.md` 提供从源码到 `.so` 的完整步骤；`build/native/build-x264.sh` 与 `build/native/build-jni.sh` 为实际使用的构建脚本。

## 3. H.264 专利（与 x264 版权许可无关）

- H.264/AVC 标准必要专利由 Via LA（原 MPEG LA）等专利池成员持有。
- x264 的 GPL 或商业许可**均不包含专利许可**，也不提供 pass-through。
- 个人非商业自用不涉及分发；对外分发产品（含公开仓库的二进制分发）时，专利许可理论上需由分发方自行向专利池取得。

## 4. Android SDK 组件

构建时使用 `aapt2`、`d8`、`zipalign`、`apksigner`、`android.jar`（Android SDK，Apache-2.0 / 各自条款），以及 `platform-tools` 中的 `adb`（Google 分发，Apache-2.0）。这些工具不入库，由使用方自行安装。

## 5. 其它依赖与资源

- `ffmpeg`：仅在构建期裁切壁纸（若存在），不随 APK 分发。
- 壁纸图像（`local/背景图/`、`local/背景图4k/`）属于临时资源，**不在本仓库中**；其版权归各自作者所有，公开或再分发前请自行确认授权。

## 6. 对外分发检查清单

分发 APK 或源码时，至少需要：

1. 附上本仓库完整源码（或明确、可获取的源码地址）；
2. 附上 [LICENSE](LICENSE) 全文与版权声明；
3. 附上本 NOTICE，保留 x264 的版权信息与许可证；
4. 提供 `build/native/` 构建脚本，并说明 x264 的准确版本；
5. 不附加任何限制接收者修改、再分发的条款（GPLv2 第 6 条）。
