#!/bin/sh
# 把 local/背景图/ 里的图片处理成 1920×1080，同步到投影仪桌面 App 的运行时背景目录。
# 用法：sh tools/sync-backgrounds.sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB="$ROOT/tools/adb"
SRC="$ROOT/local/背景图"
REMOTE_DIR="/sdcard/Android/data/cn.rainynight.dream/files/backgrounds"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"$ADB" shell "mkdir -p '$REMOTE_DIR'"
"$ADB" shell "rm -f '$REMOTE_DIR'/*.jpg '$REMOTE_DIR'/*.jpeg '$REMOTE_DIR'/*.png"

count=0
for f in "$SRC"/*.jpg "$SRC"/*.jpeg "$SRC"/*.png; do
  [ -f "$f" ] || continue
  count=$((count + 1))
  name=$(printf 'bg_%02d.jpg' "$count")
  if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -loglevel error -i "$f" \
      -vf "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080" \
      -frames:v 1 -q:v 3 "$TMP/$name"
  else
    cp "$f" "$TMP/$name"
  fi
  "$ADB" push "$TMP/$name" "$REMOTE_DIR/$name" >/dev/null
done

echo "已同步 $count 张壁纸到 $REMOTE_DIR"

# 触发桌面重新扫描背景目录（singleTask 会走 onNewIntent）
"$ADB" shell am start -n cn.rainynight.dream/.LauncherActivity >/dev/null 2>&1 || true
