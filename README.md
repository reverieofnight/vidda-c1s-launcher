# Dream 桌面（Hisense VIDAA TV Launcher）

自制的电视桌面替代品，跑在 **海信 VIDAA 智能电视**（Android 9 / API 28 / MediaTek MT9669）上：
4K 静态壁纸（x264 编码的 1fps 循环 mp4）、时钟/天气、底部应用坞、原生风格顶部菜单，外加一个完整的**壁纸管理页**（缩略图网格、设为当前/排序/删图、缓存统计与清理、U 盘/本地导入），以及**局域网网页导入**（电视上起个零依赖 HTTP 服务，电脑/手机浏览器直接上传）。

- 包名：`cn.rainynight.dream`
- 当前版本：`versionCode 1` / `versionName 1.0.0`
- 设备实测：1920×1080 UI（320dpi），面板走 4K 输出；无 4K 硬件编码器，所以 4K 静图靠自带的 x264 JNI 软编 2 帧。

## 功能一句话概览

| 模块 | 状态 |
|---|---|
| 桌面主体（壁纸 + 时钟 + 天气 + 应用坞 + 控制行） | 可用 |
| 4K 静态壁纸管线（x264 软编 → 缓存 → VideoView 循环播放） | 可用，硬切无动画 |
| 1080p 轮播兜底（MediaCodec 不可用时退化为静态图淡入淡出） | 可用 |
| 播放设置（幻灯片开关 / 间隔 10s–30min / 切换特效 / 动态壁纸静音 / 视频循环） | 可用 |
| 壁纸管理页（导入 / 排序 / 删除 / 单张与全量清缓存） | 可用 |
| 内置壁纸「月海云境」（直接从 APK assets 读取，同时充当 1080p 与空列表兜底，列表固定排最后，带「内置」角标） | 可用 |
| 远程导入（局域网 HTTP 上传页，电视上显示二维码，端口 8080 起） | 可用 |
| 动态壁纸（视频文件循环播放，与静态壁纸分栏管理） | 可用 |
| 顶部菜单 | 三项，仅「壁纸管理」有实现（直达管理页） |
| 开机自启 | 无障碍服务方案生效；`BOOT_COMPLETED` 被厂商拦截 |
| 「桌面设置」「关于」菜单 | 未实现（点了只打日志） |

详细现状、已知问题、待办见 **[项目说明.md](项目说明.md)**；真机调试命令见 **[投影仪-ADB操作手册.md](投影仪-ADB操作手册.md)**。

## 目录结构

```
build/
  build-launcher.sh       桌面 APK 构建脚本（aapt2 → javac → d8 → zipalign → apksigner）
  native/                 x264 / JNI 原生库构建脚本（可完整复现 libx264jni.so）
  launcher/               桌面源码：src/ res/ jni/ AndroidManifest.xml
    libs/arm64-v8a/libx264jni.so   预编译 x264 JNI（构建方式见 build/native/README.md）
    src/cn/rainynight/dream/QrCode.java   零依赖二维码编码器（远程导入页用）
    www/upload.html  远程导入的上传页（构建时打进 assets/www/）
tools/
  adb                     adb 包装器（HOME 重定向到 local/adbhome）
  enable-autostart.sh     重新启用开机自启（无障碍服务被系统移除时跑一下）
  sync-backgrounds*.sh    批量同步壁纸到设备
local/                    本地私有目录，整目录不入库（详见 local/README.md）
  toolchain/              （可选）JDK + build-tools + android.jar；默认在 ~/dev-tools/toolchain
  launcher-out/           桌面 APK 构建产物
  keystore.jks            签名密钥
  platform-tools/         （可选）adb 客户端；默认在 ~/dev-tools/platform-tools
  adbhome/                adb 的 HOME（含已授权 adbkey）
  screens/                过程截图（106 张）
  背景图/                本地壁纸素材（不再打进 APK，1080p 兜底改用内置壁纸）
  背景图4k/              4K 测试图
  桌面演示.mp4 / 8K狼标准黑底.jpg / Thumbs.db
```

「开发者选项」入口 APK（`tv.dsh.settingsentry`）已拆分到独立仓库：<https://github.com/reverieofnight/vidda-settings-entry>。

壁纸原图属于**临时资源**（大文件、授权待确认），不进仓库，只放 `local/背景图/`。

`local/` 之外的路径都是仓库内容；`local/` 里只有 `README.md` 被跟踪。

## 环境准备

仓库不含工具链和平台工具。默认从**机器级共享目录** `~/dev-tools/` 取（清单与安装步骤见 `~/dev-tools/README.md`），项目里 `local/toolchain`、`local/platform-tools` 存在时优先用本地的（方便整包拷走）：

| 依赖 | 位置（先 local/，再 ~/dev-tools/） | 说明 |
|---|---|---|
| JDK 8/17 | `local/toolchain/jdk` → `~/dev-tools/toolchain/jdk` | 需要 `bin/javac`、`bin/keytool` |
| Android build-tools | `local/toolchain/bt` → `~/dev-tools/toolchain/bt` | 需要 `aapt2`、`d8`、`zipalign`、`apksigner` |
| `android.jar`（API 28） | `local/toolchain/platform/android.jar` → `~/dev-tools/toolchain/platform/android.jar` | 编译 bootclasspath |
| ffmpeg | `PATH` 中 | 裁切壁纸；缺失时脚本直接拷贝原图 |
| adb | `local/platform-tools/adb` → `~/dev-tools/platform-tools/adb` | `tools/adb` 包装器按此顺序找 |
| NDK r25c + x264 源码 | 仅**修改 JNI** 时才需要 | 见 `build/launcher/jni/` |

## 构建

```sh
cd build
export LANG=C.UTF-8 LC_ALL=C.UTF-8          # 必须，否则 JDK 报 Error loading java.security file
export JAVA_HOME="$HOME/dev-tools/toolchain/jdk"   # 或 local/toolchain/jdk
export PATH="$JAVA_HOME/bin:$PATH"
sh build-launcher.sh                        # 产物：local/launcher-out/launcher.apk
```

签名密钥 `local/keystore.jks` 不入库；脚本发现缺失会自动生成（口令 `dshpass`，仅本地调试用）。

## 安装 / 调试

```sh
./tools/adb connect 192.168.2.19:5555
./tools/adb install -r local/launcher-out/launcher.apk
./tools/adb shell am start -n cn.rainynight.dream/.LauncherActivity            # 桌面
./tools/adb shell am start -n cn.rainynight.dream/.LauncherActivity --ez wallpaper_manager true
./tools/adb shell am start -n cn.rainynight.dream/.LauncherActivity --ez wallpaper_manager true --ez remote_import true
curl -s http://<电视IP>:8080/api/list          # 远程导入服务只在「远程导入」页打开时监听
./tools/adb exec-out screencap -p > /tmp/now.png
```

设备 `debuggable=false`，`run-as` 和直接读 `/data/data/cn.rainynight.dream` 都不可用，排查走 logcat（tag `DSH_LAUNCHER`）。

## 许可证

本项目以 **GPL-2.0-or-later** 发布，完整许可证文本见 **[LICENSE](LICENSE)**。

之所以选择 GPLv2+，是因为 4K 静态壁纸的 `libx264jni.so` 静态链接了 GPL 的 x264，整个桌面作为结合作品一并开放。

- 完整对应源码：本仓库全部内容，包括 `build/native/` 下的原生构建脚本；
- x264 的版本与获取方式：**[build/native/README.md](build/native/README.md)**；
- 第三方组件、H.264 专利说明与分发清单：**[NOTICE.md](NOTICE.md)**。

自用不受限制；对外分发 APK 时，请随附源码、LICENSE 与 NOTICE，并保留接收者修改、再分发的权利。
