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
