"""Generate a matching set of glossy app icons for the launcher's icon grid with Higgsfield (GPT Image 2.5)."""
import json
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

OUT = os.path.dirname(os.path.abspath(__file__))

STYLE = (" Premium 3D app icon artwork for a TV home screen, part of one consistent icon set. The artwork fills the "
         "entire square edge to edge: a rich smooth gradient background reaching all four edges, with no border, no "
         "frame, no rounded corners, no drop shadow outside, no margins and no device mockup. One bold glossy 3D symbol "
         "centred, occupying about 55 percent of the square, soft studio lighting from the top left, subtle glass "
         "highlights and a gentle inner glow, clean and minimal, high contrast, crisp edges.")

ICONS = {
    "com.netflix.ninja": "A bold glossy red letter N made of folded ribbon, the Netflix N style, on a deep black to charcoal gradient.",
    "com.amazon.amazonvideo.livingroom": "A glossy white curved smile-shaped arrow (the Prime Video smile) above a small white play triangle, on a vivid sky blue to deep blue gradient. No words.",
    "com.android.vending": "A glossy 3D Google Play triangle play symbol made of four colour facets (green, blue, yellow, red), on a soft white to pale grey gradient.",
    "com.google.android.youtube.tvmusic": "A glossy red circle with a white ring inside and a white play triangle in the middle (the YouTube Music mark), on a deep black to dark red gradient.",
    "com.roopahala.tv": "A glossy orange vintage film reel combined with a movie camera and a white play button, cinema theme, on a warm orange to deep red-brown gradient.",
    "com.tcl.magiconnectfree": "A glossy smartphone casting its screen to a TV, with curved wireless signal waves between them, on a coral red to deep crimson gradient.",
    "com.tcl.suspension": "Three stacked translucent frosted-glass panels floating in layers, a quick control panel, on a cool slate blue to steel grey gradient.",
    "com.tcl.guard": "A glossy shield with a white check mark and a soft blue glow, security protection, on a royal blue to deep indigo gradient.",
    "com.android.tv.settings": "A glossy brushed chrome gear cog, on a dark teal to graphite gradient.",
    "com.tcl.partnercustomizer": "A glossy stack of three tilted channel cards with a white play button on the front card, on a magenta to deep crimson gradient.",
    "com.tcl.tv": "A glossy modern flat-screen television on a slim stand with a broadcast antenna signal icon glowing on its screen, live TV, on a vivid orange-red to dark maroon gradient.",
}


def generate(pkg, prompt):
    png = os.path.join(OUT, pkg + ".png")
    if os.path.exists(png):
        return pkg, "exists"
    r = subprocess.run(["higgsfield", "generate", "create", "gpt_image_2_5", "--prompt", prompt + STYLE,
                        "--aspect_ratio", "1:1", "--resolution", "1k", "--quality", "high",
                        "--wait", "--wait-timeout", "15m", "--json"],
                       capture_output=True, text=True, encoding="utf-8", shell=(os.name == "nt"))
    if r.returncode != 0:
        return pkg, "failed: " + (r.stderr or r.stdout)[-300:]
    job = json.loads(r.stdout)[0]
    url = job.get("result_url") or job.get("min_result_url")
    src = os.path.join(OUT, pkg + ".src")
    urllib.request.urlretrieve(url, src)
    im = Image.open(src).convert("RGB")
    s = min(im.size)
    im = im.crop(((im.width - s) // 2, (im.height - s) // 2, (im.width + s) // 2, (im.height + s) // 2))
    im.resize((320, 320), Image.LANCZOS).save(png, optimize=True)
    return pkg, "ok"


with ThreadPoolExecutor(6) as pool:
    for pkg, status in pool.map(lambda kv: generate(*kv), ICONS.items()):
        print(pkg, status, flush=True)
