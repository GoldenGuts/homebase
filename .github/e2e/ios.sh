#!/usr/bin/env bash
# The simulator app (booted by the smoke test) against the Home Assistant started on the runner.
set -uo pipefail
PY=${HASS_PY:-$HOME/hass/bin/python}
BUNDLE=in.weenja.homebase
BASE=http://127.0.0.1:8123
DEV=$(xcrun simctl list devices booted | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1)
fail=0

"$PY" .github/e2e/ha.py setup || { echo "::error::Home Assistant setup failed"; tail -60 ~/ha.log; exit 1; }
CODE=$("$PY" .github/e2e/ha.py code $BASE homebase/callback)
xcrun simctl terminate "$DEV" $BUNDLE 2> /dev/null || true
xcrun simctl launch "$DEV" $BUNDLE -HomebaseE2E $BASE "$CODE"
DATA=$(xcrun simctl get_app_container "$DEV" $BUNDLE data)
REPORT="$DATA/Documents/e2e.json"
for _ in $(seq 1 120); do [ -f "$REPORT" ] && break; sleep 2; done
if [ -f "$REPORT" ]; then
  cat "$REPORT"
  grep -q 'FAIL' "$REPORT" && { echo "::error::an end-to-end step failed"; fail=1; }
else
  echo "::error::no report from the app"; fail=1
  if [ -f "$DATA/Documents/e2e-partial.json" ]; then echo "last state (the step marked running is where it stopped):"; cat "$DATA/Documents/e2e-partial.json"
  else echo "the end-to-end hook never started (is it compiled in? Debug builds only)"; fi
fi
pgrep -f "Homebase.app/Homebase" > /dev/null || { echo "::error::Homebase quit during the end-to-end run"; fail=1; }

# the app again, now connected: For your home with the live widgets
xcrun simctl terminate "$DEV" $BUNDLE 2> /dev/null || true
xcrun simctl launch "$DEV" $BUNDLE
sleep 12
pgrep -f "Homebase.app/Homebase" > /dev/null || { echo "::error::Homebase quit showing the live home"; fail=1; }
mkdir -p e2e-ios && xcrun simctl io "$DEV" screenshot e2e-ios/live.png > /dev/null
cp "$REPORT" e2e-ios/ 2> /dev/null || true
exit $fail
