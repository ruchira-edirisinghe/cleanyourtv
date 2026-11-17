#!/usr/bin/env bash
# Push one of the two themes (lumen or midnight-glass, lumen when none is named): its config.json, the banner and icon
# art, Lumen's wallpapers and any fonts in fonts/, to the launcher's theme folder, then restart Home.
#
#   TV_HOST=<tv ip>:<port> bash my-tv/push-art.sh lumen
#   TV_HOST=<tv ip>:<port> bash my-tv/push-art.sh midnight-glass
#   ADB=/path/to/adb TV_HOST=... bash my-tv/push-art.sh     (when adb is not on PATH)
#
# Files go through the launcher's theme provider (content write), so the launcher owns them; a plain adb push into
# Android/data leaves a folder the launcher cannot read.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep /data/local/tmp as it is
cd "$(dirname "$0")"
ADB=${ADB:-adb}
: "${TV_HOST:?set TV_HOST to the TV ip:port}"
THEME=${1:-lumen}
[ -f "themes/$THEME/config.json" ] || { echo "no theme '$THEME': use lumen or midnight-glass" >&2; exit 1; }
URI=content://com.gathin.tvhome.theme
TMP=/data/local/tmp/my-tv-art

"$ADB" connect "$TV_HOST" >/dev/null
a() { "$ADB" -s "$TV_HOST" "$@"; }

# send <local file> <path in the theme folder>
send() {
  a push -q "$1" "$TMP" >/dev/null 2>&1 || a push "$1" "$TMP" >/dev/null
  a shell "content write --uri $URI/$2 < $TMP && rm -f $TMP"
  echo "pushed $2"
}

send "themes/$THEME/config.json" config.json
shopt -s nullglob
# Only Lumen shows the wallpapers; Midnight Glass's config.json leaves them off even when they are on the TV.
walls=()
[ "$THEME" = lumen ] && walls=(wallpapers/*.jpg)
# fonts/ is git-ignored: fonts that may not be shared (font_label.ttf and the like, see the guides) only go to the TV.
for f in heroes/*.jpg icons/*.png "${walls[@]}" fonts/font_*.ttf fonts/font_*.otf; do
  send "$f" "$f"
done
a shell am force-stop com.gathin.tvhome
a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "done, Home restarted"
