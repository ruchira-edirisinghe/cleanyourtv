package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * immersive_home: the cinematic scrim painted into each full-screen picture once (the wallpaper as it is decoded, an
 * app's picture, a Continue watching cover) instead of a full-screen shade view drawn over them on every frame, which
 * on the TV's GPU cost more than anything else on Home. Safe to use from any thread: it only reads its shade.
 */
final class ShadeBaker {
    private final Bitmap shade;
    private final int alpha;

    ShadeBaker(Bitmap shade, int alpha) {
        this.shade = shade;
        this.alpha = alpha;
    }

    /** Paints the shade over this picture: in place when it can be drawn on, else into a copy. */
    Bitmap bake(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        Bitmap target = src;
        if (!src.isMutable() || src.getConfig() == Bitmap.Config.HARDWARE || src.getConfig() == null) {
            Bitmap.Config config = src.getConfig() == Bitmap.Config.RGB_565 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            try {
                target = src.copy(config, true);
            } catch (OutOfMemoryError e) {
                return src;
            }
            if (target == null) return src;
        }
        draw(new Canvas(target), target.getWidth(), target.getHeight());
        return target;
    }

    /** Paints the shade over a canvas of this size (a picture being decoded). */
    void draw(Canvas canvas, int width, int height) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        paint.setAlpha(alpha);
        canvas.drawBitmap(shade, null, new Rect(0, 0, width, height), paint);
    }
}
