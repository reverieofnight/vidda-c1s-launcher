#!/bin/sh
# 构建"设置入口"APK：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
LOCAL="$ROOT/../local"          # 工具链/产物/密钥：本地私有，不入库（见 .gitignore）
TC="$LOCAL/toolchain"
APP="$ROOT/app"
OUT="$LOCAL/settings-out"
BT="$TC/bt"
JDK="$TC/jdk"
AJAR="$TC/platform/android.jar"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/7] 检查 banner（已用齿轮图标生成，不再覆盖）"
[ -f "$APP/res/drawable/banner.png" ] || { echo "缺少 banner.png"; exit 1; }

echo "[2/7] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[3/7] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 28 --target-sdk-version 28

echo "[4/7] javac"
"$JDK/bin/javac" -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -classpath "$AJAR" \
  -d "$OUT/obj" \
  "$OUT/gen/tv/dsh/settingsentry/R.java" \
  "$APP/src/tv/dsh/settingsentry/MainActivity.java" 2>&1 | grep -v 'bootstrap class path' || true

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
  --out "$OUT/settings-entry.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/settings-entry.apk" && echo "签名校验通过"
ls -lh "$OUT/settings-entry.apk"
