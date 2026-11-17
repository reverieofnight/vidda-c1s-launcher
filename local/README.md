# local/ —— 本地私有目录（不入库）

整个 `local/` 被 `.gitignore` 排除，只有本文件被跟踪。放这里的都是「体积大 / 含密钥 / 只对本机有意义」的东西，仓库里不保存，换机器时需要自己准备或从备份恢复。

| 路径 | 内容 | 缺失后果 |
|---|---|---|
| `local/toolchain/` | JDK（`jdk/`）、Android build-tools（`bt/`：aapt2 / d8 / zipalign / apksigner）、`platform/android.jar`，约 633 MB | **无法构建**；见 README「环境准备」 |
| `local/launcher-out/` | 桌面 APK 构建产物（`launcher.apk` 9.0 MB + 中间文件） | 重新 `sh build/build-launcher.sh` 即可 |
| `local/settings-out/` | 设置入口 APK 构建产物 | 重新 `sh build/build.sh` 即可 |
| `local/keystore.jks` | APK 签名密钥（别名 `dsh`，口令 `dshpass`，仅本机调试） | 脚本会自动新建一把，但签名变了，设备上必须先卸载旧版才能装 |
| `local/platform-tools/` | 官方 adb 客户端（被 `tools/adb` 包装器调用） | `./tools/adb` 全部失效 |
| `local/adbhome/` | adb 的 HOME（里面是已被电视永久信任的 `adbkey`） | 删掉后电视会重新弹授权窗 |
| `local/screens/` | 106 张过程截图与验证图（文件名即主题） | 只影响文档配图 |
| `local/icons/settings.apk` | 早期用来抠「设置入口」图标素材的原始 APK（79 MB） | 不影响构建（图标已经落在 `build/icons/ext/`） |
| `local/背景图/` | 8 张壁纸原图（5.8K/4K，56 MB），构建时裁成内置兜底 assets | 构建仍能过，但 APK 里没有兜底壁纸（设备靠 `files/backgrounds_4k`） |
| `local/背景图4k/` | 4K 测试图 `test_4k.jpg`、`test_4k_b.jpg` | 只影响测试 |
| `local/桌面演示.mp4` | 18 秒桌面演示视频 | 无影响 |
| `local/8K狼标准黑底.jpg` | 8K 测试图（色彩/屏摄测试，已导入设备当壁纸） | 无影响 |
| `local/Thumbs.db` | 从 `背景图/` 挪出来的 Windows 缩略图缓存 | 无影响 |

## 恢复顺序（新机器）

1. 装 JDK 与 Android build-tools，放到 `local/toolchain/{jdk,bt,platform/android.jar}`；
2. 放好 adb 到 `local/platform-tools/adb`，确认 `./tools/adb devices` 能连；
3. 直接构建：`cd build && LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_HOME="$PWD/../local/toolchain/jdk" sh build-launcher.sh`；
4. 需要覆盖升级电视上已装的版本时，把旧 `keystore.jks` 放回 `local/keystore.jks`，否则签名不一致只能卸载重装。

## 关于壁纸原图

`local/背景图/`（8 张 5.8K/4K 原图）与 `local/背景图4k/`（2 张测试图）属于**临时资源**：体积大、授权未确认，不随仓库分发。缺这些图时构建仍能成功，只是 APK 里少了内置兜底壁纸；设备上的 `files/backgrounds_4k` 不受影响。
