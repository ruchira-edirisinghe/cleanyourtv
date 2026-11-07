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
screen, never cropped. A glass panel sits at the top centre, like a tablet's lock screen: the time with the weather
beside it, the date, then the greeting with your name. Every half minute or so a glass card with the poster comes up in
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

## Liquid glass

`liquid_glass` turns the header into one glass panel (the clock, then the greeting over the date), puts the top
buttons in one glass bar with a milky lens on the focused one, and makes the dock band glass too. On Android 13 and
later the glass shows a softened copy of the picture behind it, bent at the edges like a lens, with light along its
rim. The screensaver's clock and cards use the same glass, over the video.

Clocks are set in Sora, titles in Fraunces and labels in Space Grotesk (all SIL Open Font License), and the Continue
watching cards show each title's poster.
