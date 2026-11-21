#!/bin/sh
# 构建"桌面"APK：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
LOCAL="$ROOT/../local"          # 产物/密钥/素材：本地私有，不入库（见 .gitignore）
DEVTOOLS="${DEVTOOLS:-$HOME/dev-tools}"   # 跨项目共享的工具链（见 ~/dev-tools/README.md）
# 工具链优先用项目自带的 local/toolchain（可整包拷走），没有就退回机器级共享目录
if [ -d "$LOCAL/toolchain" ]; then TC="$LOCAL/toolchain"; else TC="$DEVTOOLS/toolchain"; fi
APP="$ROOT/launcher"
OUT="$LOCAL/launcher-out"
BT="$TC/bt"
JDK="$TC/jdk"
AJAR="$TC/platform/android.jar"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/7] 准备 assets"
ASSETS="$OUT/assets"
mkdir -p "$ASSETS"

# 远程导入页（局域网 HTTP 上传）的静态页面
mkdir -p "$ASSETS/www"
if [ -d "$APP/www" ]; then
  cp "$APP/www/"*.html "$ASSETS/www/" 2>/dev/null || true
fi

# 内置壁纸：随源码入库，安装到 APK 的 assets/backgrounds/bg_01.jpg
# 4K 列表、1080p 兜底、最后兜底渲染共用这一张，不复制到设备壁纸目录
if [ -d "$APP/assets" ]; then
  cp -R "$APP/assets/." "$ASSETS/"
  echo "    内置壁纸 $(ls -1 "$ASSETS/backgrounds" 2>/dev/null | wc -l) 张 → assets/backgrounds"
fi

echo "[2/7] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[3/7] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -A "$ASSETS" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 28 --target-sdk-version 28

# 原生库：POC 的 arm64 x264 JNI
if [ -f "$APP/libs/arm64-v8a/libx264jni.so" ]; then
  mkdir -p "$OUT/lib/arm64-v8a"
  cp "$APP/libs/arm64-v8a/libx264jni.so" "$OUT/lib/arm64-v8a/"
  (cd "$OUT" && zip -q -u -0 base.apk lib/arm64-v8a/libx264jni.so)
  echo "    已加入 libx264jni.so"
fi

echo "[4/7] javac"
"$JDK/bin/javac" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$AJAR" -classpath "$AJAR" \
  -d "$OUT/obj" \
  "$OUT/gen/cn/rainynight/dream/R.java" \
  "$APP/src/cn/rainynight/dream/LauncherActivity.java" \
  "$APP/src/cn/rainynight/dream/BootReceiver.java" \
  "$APP/src/cn/rainynight/dream/BootAccessibilityService.java" \
  "$APP/src/cn/rainynight/dream/RemoteImportServer.java" \
  "$APP/src/cn/rainynight/dream/QrCode.java" > "$OUT/javac.log" 2>&1 \
  || { cat "$OUT/javac.log"; echo "javac 失败"; exit 1; }
grep -v 'bootstrap class path' "$OUT/javac.log" || true

echo "[5/7] d8"
"$BT/d8" --lib "$AJAR" --min-api 28 --output "$OUT/dex" \
  $(find "$OUT/obj" -name '*.class')

echo "[6/7] 打包 dex + 对齐"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q -u "$OUT/unaligned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
if [ ! -f "$LOCAL/keystore.jks" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$LOCAL/keystore.jks" -alias dsh \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass dshpass -keypass dshpass -dname "CN=DSH, OU=Local, O=Local, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$LOCAL/keystore.jks" --ks-pass pass:dshpass --key-pass pass:dshpass \
  --out "$OUT/launcher.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/launcher.apk" && echo "签名校验通过"
ls -lh "$OUT/launcher.apk"
