#!/usr/bin/env bash
# Push this TV's banner and icon art to the launcher's theme folder, then restart Home.
#
#   TV_HOST=<tv ip>:<port> bash my-tv/push-art.sh
#   ADB=/path/to/adb TV_HOST=... bash my-tv/push-art.sh     (when adb is not on PATH)
#
# Files go through the launcher's theme provider (content write), so the launcher owns them; a plain adb push into
# Android/data leaves a folder the launcher cannot read.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep /data/local/tmp as it is
cd "$(dirname "$0")"
ADB=${ADB:-adb}
: "${TV_HOST:?set TV_HOST to the TV ip:port}"
URI=content://com.gathin.tvhome.theme
TMP=/data/local/tmp/my-tv-art

"$ADB" connect "$TV_HOST" >/dev/null
a() { "$ADB" -s "$TV_HOST" "$@"; }

for f in heroes/*.jpg icons/*.png; do
  a push -q "$f" "$TMP" >/dev/null 2>&1 || a push "$f" "$TMP" >/dev/null
  a shell "content write --uri $URI/$f < $TMP && rm -f $TMP"
  echo "pushed $f"
done
a shell am force-stop com.gathin.tvhome
a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "done, Home restarted"
