package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * hero_banners: the picture shown behind the cinematic shelf for a dock app, heroes/<package>.jpg (or .png, .webp)
 * in the theme folder. Decoded at screen size at most, off the main thread (MainActivity).
 */
final class HeroBanners {
    private static final String[] TYPES = {".jpg", ".jpeg", ".png", ".webp"};

    private HeroBanners() { }

    /** The app's picture, or null when it has none. */
    static File file(Context ctx, String pkg) {
        File dir = Config.dir(ctx);
        if (dir == null || pkg == null) return null;
        File heroes = new File(dir, ThemeFiles.HEROES);
        for (String type : TYPES) {
            File f = new File(heroes, pkg + type);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Decodes the picture no larger than needed for a screen of this size; null if it cannot be read. */
    static Bitmap decode(File f, int screenW, int screenH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
