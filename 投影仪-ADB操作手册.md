# 投影仪 ADB 操作手册

> 记录时间：2026-11-16 · 环境：TerraMaster NAS（SEANAS）上的 DSH
> 所有步骤均经过实测验证，结论附实测证据。

---

## 一、设备速查

| 项 | 值 |
|---|---|
| 投影仪 IP | `192.168.2.19`（DHCP 分配，可能变化） |
| ADB 端口 | `5555` |
| 型号 / 品牌 | `VIDAA_TV` / Hisense（海信） |
| 芯片 / Android | MediaTek MT9669 / Android 9（SDK 28） |
| 构建类型 | `user` / `release-keys`（**无 root**） |
| 分辨率 | 1920×1080 |
| 手机 MAC（授权来源） | `02:27:b1:fe:78:13`（随机 MAC，对该 SSID 持久） |
| NAS IP | `192.168.2.15` |

> IP 会变。若连不上，先在路由器上查投影仪当前 IP，或做 DHCP 地址保留。

---

## 二、开关 USB 调试（已验证闭环）

### 关闭
1. 投影仪遥控器 → 桌面「我的应用」
2. 打开 **开发者选项**（齿轮图标，本手册第四节的那个应用）
3. 进入 **调试** → 选中 **USB调试** → 关闭

**实测结果（2026-11-16 05:08）**
```
adb connect → Connection refused
adb devices → 列表为空
TCP 5555   → 连续 3 次拒绝
```

### 重新开启
同上路径，把 **USB调试** 打开。

**实测结果**
```
9 秒内检测到 5555 重新监听
adb connect → connected
adb devices → 192.168.2.19:5555   device
```

**关键点：重新开启后不需要重新授权弹窗。** 关闭 USB 调试只是停掉 `adbd`，
`/data/misc/adb/adb_keys`（6 条记录）不会被清空，已授权的密钥继续有效。

---

## 三、从 NAS 连接

adb 客户端默认在 `~/dev-tools/platform-tools/`（项目里放 `local/platform-tools/` 则优先用它），
包装脚本是 `tools/adb`（会把 `HOME` 重定向到 `local/adbhome`，避免写系统目录被沙箱拒绝）。

> `local/` 是本地私有目录，整个不入库；里面的东西见 `local/README.md`。

```bash
cd /vol1/@appshare/Harness/投影仪桌面开发

tools/adb connect 192.168.2.19:5555
tools/adb devices -l
tools/adb shell getprop ro.product.model
tools/adb install xxx.apk
tools/adb uninstall 包名
```

投屏截图（相比 `exec-out` 更稳）：

```bash
tools/adb shell 'screencap -p /sdcard/_s.png'
tools/adb pull /sdcard/_s.png local/screens/now.png
tools/adb shell 'rm -f /sdcard/_s.png'
```

---

## 四、桌面应用「开发者选项」

- 包名 `tv.dsh.settingsentry`
- 名称「开发者选项」，图标为**从系统设置 APK 提取的原生齿轮**
- 行为：点开 → 直达 `com.android.tv.settings/.system.DeveloperOptionsActivity`
- 无界面（`Theme.NoDisplay`），不申请任何权限
- 三级兜底：开发者选项 → 海信设置主界面 → 系统设置首页

**它存在的意义**：系统设置首页 `MainSettings` 被厂商设为**不导出**，
遥控器又没有入口，所以一旦 USB 调试关闭，原本无法再打开。这个应用绕开了这个死锁。

### 为什么不做成"设置首页"
`MainSettings` 拒绝外部调用：
```
Security exception: Permission Denial: ... not exported from uid 1000
```
曾尝试做成 12 项子页面目录（网络/显示/声音/应用/…），但实测这些页面
在投影仪上大多不可用，故改为直达开发者选项。

---

## 五、源码与构建

```
build/
├── app/
│   ├── AndroidManifest.xml
│   ├── res/values/strings.xml        ← 应用名称
│   ├── res/drawable/icon.png         ← 图标（系统原生齿轮）
│   ├── res/drawable/banner.png       ← TV banner（深色底 + 齿轮）
│   └── src/tv/dsh/settingsentry/MainActivity.java
└── build.sh                           ← 一键构建

local/                                 ← 本地私有目录（整目录不入库）
├── keystore.jks                       ← 签名密钥（务必保留，否则无法覆盖升级）
├── toolchain/                         ← （可选）JDK + build-tools + android.jar；默认在 ~/dev-tools
└── settings-out/settings-entry.apk     ← 成品
```

构建命令：

```bash
cd build
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export JAVA_HOME="$HOME/dev-tools/toolchain/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
sh build.sh
```

**踩过的坑（重要）**：本机 `LANG` 未设置（POSIX 编码），JVM 无法打开含中文的
`java.home` 路径，会报 `Error loading java.security file`。任何 Java 工具都必须先
`export LANG=C.UTF-8`。

改名字只需改 `res/values/strings.xml`；换图标只需替换 `res/drawable/icon.png`。

---

## 六、授权密钥现状（6 条）

| # | 标签 | 归属 | 状态 |
|---|---|---|---|
| 1 | `YangHai@ESUNESEA` | 你的电脑 | 有效 |
| 2 | `u0_a427@localhost` | 设备应用（已卸载） | 死密钥 |
| 3 | `unknown@unknown` | **手机上的甲壳虫 ADB 助手** | 有效 |
| 4 | `u0_a431@localhost` | 设备应用（已卸载） | 死密钥 |
| 5 | `u0_a353@localhost` | 设备应用（已卸载） | 死密钥 |
| 6 | `@SEANAS` | **NAS（本 DSH）** | 有效 |

- 我的公钥指纹：`65:EC:FD:47:D2:C8:A6:F4:F7:E9:5B:C5:56:31:74:6C`
  （Android 的算法是 **对 adbkey.pub 解码后的原始 blob 做 MD5**，不是 DER）
- 私钥位置：`local/adbhome/.android/adbkey` —— **删掉就需要重新弹窗授权**
- 全部清除：投影仪 开发者选项 → 「撤销 USB 调试授权」

---

## 七、环境事实与风险

| 项 | 状态 | 说明 |
|---|---|---|
| SELinux | **Permissive** | 启动参数 `androidboot.selinux=permissive`，厂商出厂设定；策略拒绝但只记录不拦截 |
| 引导校验 | **orange** | `verifiedbootstate=orange`，引导链未验证，物理接触可刷机 |
| ADB 端口 | 5555 常开（若启用） | 对局域网暴露；建议路由器只放行 NAS 的 IP |
| SELinux 拒绝量 | 280 条/缓冲区 | 含厂商 HAL 越权，说明靠 permissive 才能跑起来 |

**建议**：不需要时关闭 USB 调试；需要时打开，用完再关。现在这个闭环已经成立。

---

## 八、故障排查

| 症状 | 排查 |
|---|---|
| `Connection refused` | USB 调试关了 → 用桌面「开发者选项」应用打开 |
| 连上但 `unauthorized` | 密钥不被信任 → 投影仪上确认弹窗，或检查 `local/adbhome/.android/adbkey` 是否还在 |
| 找不到投影仪 | IP 变了 → 路由器查当前 IP，或 `arp -a` 看局域网设备 |
| 构建报 `java.security` | `export LANG=C.UTF-8` |
| 覆盖安装报签名冲突 | 用了不同的 `local/keystore.jks`，先卸载再装 |

---

## 九、相关截图

`screens/` 目录：
- `app-devopts.png` — 桌面上的「开发者选项」图标
- `dev-options.png` / `dev-debug.png` — 开发者选项与调试页
- `home.png` — VOD 桌面
- `settings-menu.png` — 曾做过的设置目录（已弃用）
