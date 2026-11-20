#!/bin/sh
# 把 4K 原图同步到投影仪：./tools/sync-backgrounds-4k.sh [源目录，默认 local/背景图]
set -eu
cd "$(dirname "$0")/.."
SRC=${1:-local/背景图}
DEST=/sdcard/Android/data/cn.rainynight.dream/files/backgrounds_4k
[ -d "$SRC" ] || { echo "源目录不存在: $SRC" >&2; exit 1; }
./tools/adb shell "mkdir -p $DEST"
count=0
for f in "$SRC"/*.jpg "$SRC"/*.jpeg "$SRC"/*.png; do
  [ -f "$f" ] || continue
  ./tools/adb push "$f" "$DEST/$(basename "$f")" >/dev/null
  count=$((count + 1))
done
echo "已同步 $count 张 4K 图片到 $DEST"
