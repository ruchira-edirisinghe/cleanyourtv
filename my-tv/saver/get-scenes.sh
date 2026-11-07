#!/usr/bin/env bash
# The live screensaver's scenes (LiveSaverDream): downloads each looping 4K video from where it is published, keeps it at
# 3840x2160 without sound (re-encoding only a clip that needs it: one whose end does not meet its start gets its last
# seconds faded into its first, and a 10-bit one becomes 8-bit, which the TV's decoder plays), and writes it to the
# launcher's theme folder on the TV (saver/). The screensaver shows each whole, never cropped. The videos are not kept
# in this repo: the Pixabay Content License allows free use, not passing the files on as they are.
#
#   TV_HOST=<tv ip>:<port> bash my-tv/saver/get-scenes.sh          needs curl, ffmpeg and adb
#   ADB=/path/to/adb  to use another adb; OUT=dir to keep the files somewhere else (default ./work/saver/scenes)
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV address, ip:port}"
ADB=${ADB:-adb}
OUT=${OUT:-work/saver/scenes}
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
ENC=(-an -c:v libx264 -preset medium -crf 18 -maxrate 40M -bufsize 80M -profile:v high -pix_fmt yuv420p -movflags +faststart)
mkdir -p "$OUT/src"

# name | video link (all Pixabay, 3840x2160) | seconds to blend for a clip whose end does not meet its start, or empty
SCENES='
synthwave-sun|https://cdn.pixabay.com/video/2019/11/19/29225-374487091_large.mp4|2
grid-planet|https://cdn.pixabay.com/video/2021/07/23/82515-580137713_large.mp4|
pixel-tv-room|https://cdn.pixabay.com/video/2024/12/21/248092_large.mp4|
neon-city|https://cdn.pixabay.com/video/2025/03/18/265596_large.mp4|1.5
tokyo-street|https://cdn.pixabay.com/video/2025/08/31/300904_large.mp4|1.5
sky-lanterns|https://cdn.pixabay.com/video/2025/03/26/267593_large.mp4|1.5
camping-night|https://cdn.pixabay.com/video/2025/03/26/267613_large.mp4|1.5
mushroom-forest|https://cdn.pixabay.com/video/2023/03/19/155242-809619008_large.mp4|2
fairy-house|https://cdn.pixabay.com/video/2023/07/31/174062-850287032_large.mp4|
firefly-cave|https://cdn.pixabay.com/video/2023/09/21/181583-866999908_large.mp4|2
city-sunset|https://cdn.pixabay.com/video/2025/03/23/266892_large.mp4|1.5
sakura-street|https://cdn.pixabay.com/video/2025/03/26/267601_large.mp4|1.5
rain-camper|https://cdn.pixabay.com/video/2025/07/31/294606_large.mp4|
neon-road|https://cdn.pixabay.com/video/2026/07/12/363515_large.mp4|1.5
candle-room|https://cdn.pixabay.com/video/2024/11/17/241824_large.mp4|
'

echo "$SCENES" | while IFS='|' read -r name url blend; do
  [ -n "$name" ] || continue
  src="$OUT/src/$name.mp4"
  dst="$OUT/$name.mp4"
  if [ ! -s "$src" ]; then
    curl -sSfL -A "$UA" -e https://pixabay.com/ -o "$src" "$url" </dev/null
    sleep 5   # Pixabay refuses quick runs of downloads
  fi
  if [ ! -s "$dst" ]; then
    fps=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$src" | tr -d '\r')
    level=$(awk -v f="$fps" 'BEGIN { split(f, p, "/"); print (p[1] / (p[2] ? p[2] : 1) > 31) ? "5.2" : "5.1" }')
    bits=$(ffprobe -v error -select_streams v:0 -show_entries stream=pix_fmt -of csv=p=0 "$src" | tr -d '\r')
    if [ -n "$blend" ]; then
      len=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$src" | tr -d '\r')
      at=$(awk -v d="$len" -v f="$blend" 'BEGIN { printf "%.3f", d - 2 * f - 0.05 }')
      ffmpeg -nostdin -v error -y -i "$src" -filter_complex "[0:v]split[a][b];\
[a]trim=start=$blend,setpts=PTS-STARTPTS[body];[b]trim=0:$blend,setpts=PTS-STARTPTS[head];\
[body][head]xfade=transition=fade:duration=$blend:offset=$at,format=yuv420p" "${ENC[@]}" -level "$level" "$dst"
    elif [ "$bits" != "yuv420p" ]; then
      ffmpeg -nostdin -v error -y -i "$src" -vf format=yuv420p "${ENC[@]}" -level "$level" "$dst"
    else
      ffmpeg -nostdin -v error -y -i "$src" -an -c:v copy -movflags +faststart "$dst"
    fi
  fi
  "$ADB" -s "$TV_HOST" push "$dst" "/data/local/tmp/$name.mp4" >/dev/null </dev/null
  "$ADB" -s "$TV_HOST" shell "content write --uri content://com.gathin.tvhome.theme/saver/$name.mp4 \
< /data/local/tmp/$name.mp4 && rm /data/local/tmp/$name.mp4" </dev/null
  echo "on the TV: $name"
done

echo "To use it: $ADB -s $TV_HOST shell settings put secure screensaver_components com.gathin.tvhome/.LiveSaverDream"
