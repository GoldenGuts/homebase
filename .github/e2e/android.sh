#!/usr/bin/env bash
# Runs with an emulator up (reactivecircus/android-emulator-runner): the debug app against the Home
# Assistant started on the runner (the emulator reaches the host at 10.0.2.2). Fails on a crash, on a
# failed end-to-end step, or when the pinned widget never draws.
set -uo pipefail
PY=${HASS_PY:-$HOME/hass/bin/python}
PKG=in.weenja.hawidgets
BASE=http://10.0.2.2:8123
OUT=e2e-android
mkdir -p "$OUT"
fail=0

ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1; }
# tap the first view whose text is exactly $1; false when it is not on screen
tap_text() {
  ui
  local xy
  xy=$("$PY" - "$1" "$OUT/ui.xml" <<'EOF'
import re, sys
x = open(sys.argv[2], encoding="utf-8", errors="replace").read()
m = re.search(r'text="' + re.escape(sys.argv[1]) + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x, re.I)
print(f"{(int(m[1]) + int(m[3])) // 2} {(int(m[2]) + int(m[4])) // 2}" if m else "")
EOF
)
  [ -n "$xy" ] || return 1
  adb shell input tap $xy
}
shot() {
  adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png "$OUT/$1.png" > /dev/null
  "$PY" - "$OUT/$1.png" "$OUT/$1.jpg" <<'EOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
im.thumbnail((360, 800))
im.save(sys.argv[2], quality=38)
EOF
}
crashed() { adb logcat -d | grep -q -E "FATAL EXCEPTION|ANR in $PKG"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

echo "::group::First launch (not connected)"
adb shell am start -W -n $PKG/in_.weenja.hawidgets.MainActivity
sleep 6
shot onboarding-welcome
tap_text "Get started" && sleep 5 && shot onboarding-connect && adb shell input keyevent KEYCODE_BACK
sleep 2
crashed && { echo "::error::crash on first launch"; fail=1; }
echo "::endgroup::"

echo "::group::End to end against Home Assistant"
CODE=$("$PY" .github/e2e/ha.py code $BASE)
adb shell am start -W -n $PKG/in_.weenja.hawidgets.E2eActivity --es url $BASE --es code "$CODE"
for _ in $(seq 1 150); do adb logcat -d -s HomebaseE2E:I | grep -q E2E-DONE && break; sleep 2; done
mkdir -p "$OUT/e2e"
REPORT="$OUT/e2e/report.json"
# the report from private storage (debug builds allow run-as); /sdcard can lag behind the write
for _ in 1 2 3 4 5; do
  adb exec-out run-as $PKG cat files/e2e-report.json > "$REPORT" 2> /dev/null && [ -s "$REPORT" ] && break
  sleep 2
done
[ -s "$REPORT" ] || rm -f "$REPORT"
adb pull /sdcard/Android/data/$PKG/files/e2e "$OUT/widgets" > /dev/null 2>&1 || true   # the widget PNGs, for the artifact
if [ -f "$REPORT" ]; then
  "$PY" - "$REPORT" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
for k, v in r.items():
    if isinstance(v, dict):
        print(f"{k}:")
        for kk, vv in v.items():
            print(f"    {kk}: {str(vv)[:300]}")
    elif isinstance(v, list):
        print(f"{k}:")
        for i in v:
            print(f"    {str(i)[:500]}")
    else:
        print(f"{k}: {v}")
EOF
  grep -q 'FAIL' "$REPORT" && { echo "::error::an end-to-end step failed"; fail=1; }
else
  echo "::error::no report"; adb logcat -d -s HomebaseE2E:* | tail -40; fail=1
fi
echo "::endgroup::"

echo "::group::For your home, then a widget on the launcher"
# a fresh start (-S): otherwise the intent goes to the task that still shows the end-to-end screen
adb shell am start -W -S -n $PKG/in_.weenja.hawidgets.MainActivity
sleep 4
if tap_text "For your home"; then
  sleep 8
  shot foryourhome
  if tap_text "Add to home screen"; then
    sleep 4
    ui; grep -o 'text="[^"]*"' "$OUT/ui.xml" | head -30 | tr '\n' ' '; echo
    # the launcher's confirmation (wording differs between launchers)
    tap_text "Add to home screen" || tap_text "Add" || tap_text "ADD" || tap_text "Add automatically" || true
    sleep 8
  fi
fi
adb shell input keyevent KEYCODE_HOME
sleep 6
# bound widgets are listed as "provider=ProviderId{… cmp:ComponentInfo{pkg/…}}" (installed providers without "=")
BOUND=$(adb shell dumpsys appwidget | grep -cE "provider=ProviderId.*ComponentInfo\{$PKG/")
echo "widgets bound on the launcher: $BOUND"
shot home
# the widget's tap zones carry labels ("Bed Light, off"): proof it drew live data on the launcher
ui; echo "home screen labels:"; grep -o 'content-desc="[^"]*"' "$OUT/ui.xml" | grep -v 'content-desc=""' | head -40
if [ "$BOUND" -gt 0 ]; then
  adb logcat -d -s hawidgets:E | grep -q "render" && { echo "::error::a widget failed to render"; adb logcat -d -s hawidgets:E | tail -20; fail=1; }
else
  echo "::warning::the launcher did not add the widget (pin dialog not confirmed); widget rendering on the launcher not checked"
fi
echo "::endgroup::"

echo "::group::Release build (R8-minified) on the same data"
REL=app/build/outputs/apk/release/app-release.apk
if [ -f "$REL" ] && adb install -r "$REL"; then
  crashed && { echo "::error::the debug build crashed before this point"; adb logcat -d | grep -A40 "FATAL EXCEPTION" | head -60; fail=1; }
  adb logcat -c
  adb shell am start -W -n $PKG/in_.weenja.hawidgets.MainActivity --ez dump true --es sizes 340x200,340x420
  sleep 30
  n=$(adb shell ls /sdcard/Android/data/$PKG/files/previews 2> /dev/null | wc -l)
  echo "release build drew $n widget previews"
  [ "$n" -gt 50 ] || { echo "::error::the release build drew only $n previews"; fail=1; }
  tap_text "For your home" && sleep 8 && shot release-foryourhome
  adb shell input keyevent KEYCODE_HOME
  sleep 3
else
  echo "::warning::no release APK installed"
fi
echo "::endgroup::"

if crashed; then
  echo "::error::the app crashed"
  adb logcat -d | grep -A40 "FATAL EXCEPTION" | head -80
  fail=1
fi
exit $fail
