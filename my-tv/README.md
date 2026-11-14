# My TV's art

The pictures this fork's launcher shows on my TCL Smart TV Pro (G08, Google TV 14). They are not built into the
APK: the launcher reads them from its theme folder on the TV.

| Folder | What it is | Launcher switch |
|---|---|---|
| `heroes/<package>.jpg` | A 1920x1080 banner for each dock app, shown at the top when that app is focused. `input.playstation.jpg` is the PlayStation tile's | `hero_banners` (with `cinematic_home`) |
| `icons/<package>.png` | A 320x320 glossy icon for each app in the grid under the dock; the grid's colour wash is taken from it | `icon_grid`, `grid_glow` |

All of it was made with Higgsfield (GPT Image 2.5). The banner prompts are in [`heroes/PROMPTS.md`](heroes/PROMPTS.md);
the icons come from [`icons/gen_icons.py`](icons/gen_icons.py), which skips any icon that is already there.

## Put it on the TV

With the fork's launcher installed and ADB connected:

```bash
TV_HOST=<tv ip>:<port> bash my-tv/push-art.sh
```

To add or replace one picture, put it in the right folder named after the app's package
(`adb shell pm list packages` lists them) and run the script again. An app with no file keeps its normal artwork.

## Continue watching

The row under the dock (`continue_watching`) fills itself; two things to do once after installing the launcher:

1. Let it see what other apps play (it reads their media sessions, not notifications):

   ```bash
   adb shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.WatchListener
   ```

   From then on, anything watched for more than a minute in Netflix, YouTube and the like, and not finished, shows
   up with its picture and how far in it is. Spotify and YouTube Music are left out (`continue_skip`).
2. For Stremio's own list and new episodes, open the **Connect Stremio** card in the row, then go to
   link.stremio.com on a phone and enter the code it shows. Only Stremio's key for this TV is kept on the TV.

Hold OK on a card to remove it, or to disconnect Stremio.

## Live screensaver

`LiveSaverDream` plays looping animated 4K scenes (synthwave suns, neon cities, pixel-art rooms, firefly forests,
lantern skies) and dips through black to the next every 75 seconds. Each is shown whole, edge to edge on a 16:9
screen, never cropped. At the top centre, like a tablet's lock screen, stand the time with the weather beside it, the
date, then the greeting with your name, as raised type with a soft shadow and no panel behind it. Every half minute or so a glass card with the poster comes up in
a bottom corner for something left part-watched, or a new Stremio episode. Press OK while a card is up to jump back
into it; any other key ends the screensaver.

The weather (`saver_weather`) comes from [Open-Meteo](https://open-meteo.com/): set `weather_location` to a place
name or `latitude,longitude`, or leave it empty to use the place the TV's internet address points to.

The scenes are not in this repo. They are free 3840x2160 videos from
[Pixabay](https://pixabay.com/service/license-summary/), which may be used but not passed on as files.
[`saver/get-scenes.sh`](saver/get-scenes.sh) downloads them from there, keeps them at 4K without sound, smooths the
loops that would jump and writes them to the TV:

```bash
TV_HOST=<tv ip>:<port> bash my-tv/saver/get-scenes.sh
adb shell settings put secure screensaver_components com.gathin.tvhome/.LiveSaverDream
```

Any other `.mp4`, `.webm` or `.mkv` put in the theme folder's `saver/` joins the rotation.

## Now playing

While Spotify plays (`now_playing`, the apps in `now_playing_apps`), a glass card with the cover, the song, the
artist and how far in stands on Home in the hero area on the left, between the clock and the focused app's name. It
fades out as soon as the apps scroll and comes back at the top. On the live screensaver the same card stays still in
the bottom right corner.

While a song plays:

- **Continue watching steps aside:** the row under the dock and the screensaver's poster cards stay hidden, and come
  back when the music stops.
- **The TV stays awake on the screensaver.** Android would end the screensaver and put the TV to sleep once the sleep
  timeout has passed since the last key, music or not. So while the screensaver shows a song, the launcher sets
  `sleep_timeout` to never and puts your own value back two minutes after the music stops, or as soon as a key ends
  the screensaver. If the launcher died while holding it, Home puts it back when it starts.

It reads the songs with the same listener as Continue watching (`allow_listener ... WatchListener`, above) and changes
the timeout with `WRITE_SECURE_SETTINGS`, both already granted on this TV.

## Liquid glass

`liquid_glass` sets the header as raised type with no panel (the clock, then the greeting over the date), puts the top
buttons in one glass bar with a milky lens on the focused one, and makes the dock band glass too. On Android 13 and
later the glass shows a softened copy of the picture behind it, bent at the edges like a lens, with light along its
rim. The screensaver's cards use the same glass, over the video.

Clocks are set in Sora, titles in Fraunces and labels in Space Grotesk (all SIL Open Font License), and the Continue
watching cards show each title's poster.

On this TV the titles and labels are Product Sans Black instead, all upright (the date too). Product Sans may not be
shared, so it is not in the repo: put the files in `my-tv/fonts/` (git-ignored), named after the string they replace
(`font_title.ttf`, `font_title_italic.ttf` as the upright Black too, `font_label.ttf`, `font_label_bold.ttf`), and `push-art.sh` sends them
to the launcher's theme folder, which uses a font there in place of the bundled one.
