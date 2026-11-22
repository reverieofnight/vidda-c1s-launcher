#!/bin/sh
# 重新启用开机自启（B2：无障碍服务）
#
# 系统偶尔会把第三方无障碍服务从 enabled_accessibility_services 里移除，
# 那时开机不会自动进入 Dream 桌面（BOOT_COMPLETED 已被厂商拦截）。
# 本脚本只追加我们这一项，不动其它服务，也不会去关总开关。
#
# 用法：
#   sh tools/enable-autostart.sh                     # 默认 192.168.2.19:5555
#   sh tools/enable-autostart.sh 192.168.2.19:5555
set -eu

DIR=$(cd "$(dirname "$0")" && pwd)
ADB="$DIR/adb"
SERIAL="${1:-192.168.2.19:5555}"
OUR=cn.rainynight.dream/cn.rainynight.dream.BootAccessibilityService

"$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
if ! "$ADB" shell true >/dev/null 2>&1; then
  echo "设备未连接：$SERIAL" >&2
  exit 1
fi

CUR=$("$ADB" shell 'settings get secure enabled_accessibility_services' | tr -d '\r\n')
[ "$CUR" = "null" ] && CUR=

case ":$CUR:" in
  *":$OUR:"*)
    echo "无障碍服务已启用，无需改动"
    ;;
  *)
    NEW="${CUR:+$CUR:}$OUR"
    "$ADB" shell "settings put secure enabled_accessibility_services '$NEW'"
    echo "已启用：$OUR"
    ;;
esac

"$ADB" shell 'settings put secure accessibility_enabled 1'

echo "--- 当前设置 ---"
"$ADB" shell 'settings get secure enabled_accessibility_services' | tr -d '\r' | tr ':' '\n'
echo "accessibility_enabled = $("$ADB" shell 'settings get secure accessibility_enabled' | tr -d '\r')"
