#!/bin/sh
# 构建"桌面"APK：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
LOCAL="$ROOT/../local"          # 工具链/产物/密钥：本地私有，不入库（见 .gitignore）
TC="$LOCAL/toolchain"
APP="$ROOT/launcher"
OUT="$LOCAL/launcher-out"
BT="$TC/bt"
JDK="$TC/jdk"
AJAR="$TC/platform/android.jar"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/7] 生成背景图 assets"
BG_SRC="$LOCAL/背景图"   # 壁纸原图属临时资源，放在 local/ 下不入库
ASSETS="$OUT/assets"
mkdir -p "$ASSETS/backgrounds"
bg_count=0
[ -d "$BG_SRC" ] || echo "    提示：$BG_SRC 不存在，跳过内置兜底壁纸"
for f in "$BG_SRC"/*.jpg "$BG_SRC"/*.jpeg "$BG_SRC"/*.png; do
  [ -f "$f" ] || continue
  bg_count=$((bg_count + 1))
  name=$(printf 'bg_%02d.jpg' "$bg_count")
  if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -loglevel error -i "$f" \
      -vf "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080" \
      -frames:v 1 -q:v 3 "$ASSETS/backgrounds/$name"
  else
    cp "$f" "$ASSETS/backgrounds/$name"
  fi
done
echo "    背景图 $bg_count 张 → assets/backgrounds"

# 远程导入页（局域网 HTTP 上传）的静态页面
mkdir -p "$ASSETS/www"
if [ -d "$APP/www" ]; then
  cp "$APP/www/"*.html "$ASSETS/www/" 2>/dev/null || true
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
"$JDK/bin/javac" -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -classpath "$AJAR" \
  -d "$OUT/obj" \
  "$OUT/gen/tv/dsh/launcher/R.java" \
  "$APP/src/tv/dsh/launcher/LauncherActivity.java" \
  "$APP/src/tv/dsh/launcher/BootReceiver.java" \
  "$APP/src/tv/dsh/launcher/BootAccessibilityService.java" \
  "$APP/src/tv/dsh/launcher/RemoteImportServer.java" \
  "$APP/src/tv/dsh/launcher/QrCode.java" > "$OUT/javac.log" 2>&1 \
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
