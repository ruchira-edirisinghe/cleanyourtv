package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;

/**
 * icon_grid: square artwork a theme gives an app for its grid tile, icons/<package>.png (or .jpg, .webp) in the
 * theme folder. Small pictures (a few hundred pixels), so they are read with the tiles.
 */
final class GridIcons {
    private static final String[] TYPES = {".png", ".webp", ".jpg", ".jpeg"};
    /** Decoded no smaller than this, a little over the largest square a 1080p grid draws. */
    private static final int MIN_SIDE = 256;

    private GridIcons() { }

    /** The app's artwork, or null when the theme has none. */
    static Drawable load(Context ctx, String pkg) {
        File dir = Config.dir(ctx);
        if (dir == null || pkg == null) return null;
        File icons = new File(dir, ThemeFiles.ICONS);
        if (!icons.isDirectory()) return null;
        for (String type : TYPES) {
            File f = new File(icons, pkg + type);
            if (!f.isFile() || f.length() == 0) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) continue;
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= MIN_SIDE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try {
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null) return new BitmapDrawable(ctx.getResources(), b);
            } catch (OutOfMemoryError ignored) {
                return null;
            }
        }
        return null;
    }
}
