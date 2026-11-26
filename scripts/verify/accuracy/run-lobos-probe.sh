#!/usr/bin/env bash
# Run the glibc accuracy probe inside LobOS via its terminal exec hook and
# dump the rendered output. Uses the nativeLibraryDir glibc loader (the only
# executable loader path under app_data SELinux) and an app_data probe file.
# Usage: run-lobos-probe.sh <probe-name> <dim>
set -uo pipefail
ADB=/home/bowen/runtimes/platform-tools/adb
DEV=f0942f56
D=/data/user/0/lobos.os/files
PROBE="$1"
DIM="$2"

BASE=$($ADB -s "$DEV" shell "pm path lobos.os 2>/dev/null | sed 's/package://'" | tr -d '\r' | sed 's|/base.apk||')
NAT="$BASE/lib/arm64"

# Build a one-shot runner script in app_data that invokes the probe through
# the native loader with a clean LD_PRELOAD (mirrors glibc-run).
cat > /tmp/acc-one.sh <<EOF
#!/system/bin/sh
unset LD_PRELOAD
exec $NAT/libglibc-loader.so --library-path $D/opt/glibc-runtime/2.41.12/lib $D/$PROBE $DIM 2>&1
echo __RC__\$?
EOF
B=$(base64 -w0 /tmp/acc-one.sh)
$ADB -s "$DEV" shell "run-as lobos.os sh -c 'echo $B | base64 -d > $D/acc-one.sh; chmod 755 $D/acc-one.sh'" >/dev/null 2>&1

# Find the terminal input box, clear it, type the runner command.
$ADB -s "$DEV" shell 'uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml' 2>/dev/null > /tmp/acc-ui.xml
XY=$(python3 - "$@" <<'PY'
import re,sys
x=open('/tmp/acc-ui.xml',encoding='utf-8',errors='replace').read()
m=re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2) if m else print('979 1020')
PY
)
WX=${XY% *}; WY=${XY#* }
$ADB -s "$DEV" shell "input tap $WX $WY" >/dev/null 2>&1; sleep 1
for _ in 1 2 3; do timeout 5 $ADB -s "$DEV" shell "input keyevent KEYCODE_DEL" >/dev/null 2>&1; done
$ADB -s "$DEV" shell "input text 'bash $D/acc-one.sh'" >/dev/null 2>&1
$ADB -s "$DEV" shell "input keyevent KEYCODE_ENTER" >/dev/null 2>&1
sleep 6
$ADB -s "$DEV" shell 'uiautomator dump /sdcard/z.xml >/dev/null 2>&1; cat /sdcard/z.xml' 2>/dev/null
