package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * liquid_glass: a small, softened copy of what is behind the glass (Home's backdrop layers, or a frame of the
 * screensaver's video), for {@link GlassDrawable} to bend and lighten. Two bitmaps take turns, so a drawable never reads one while it
 * is being redrawn; every update invalidates the drawables that use it.
 */
final class GlassBackdrop {
    static final int W = 160, H = 90;

    private final Bitmap[] buffers = {
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)};
    private final int[] pixels = new int[W * H];
    private final int[] temp = new int[W * H];
    private final List<WeakReference<Drawable>> users = new ArrayList<>();
    private int current = -1;
    /** A PixelCopy is on its way. */
    private boolean copying;
    private int generation;
    /** The screen size the copy stands for, in pixels. */
    int screenW = 1920, screenH = 1080;

    /** The latest copy, or null before the first. */
    Bitmap bitmap() {
        return current < 0 ? null : buffers[current];
    }

    /** Changes each update, so a drawable knows to remake its shader. */
    int generation() {
        return generation;
    }

    void register(Drawable d) {
        for (WeakReference<Drawable> ref : users) if (ref.get() == d) return;
        users.add(new WeakReference<>(d));
    }

    /** Home: draws these full-screen layers (children of root, in order) as they are now, alpha included. */
    void capture(ViewGroup root, View... layers) {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        screenW = root.getWidth();
        screenH = root.getHeight();
        Bitmap target = buffers[next()];
        target.eraseColor(0xFF000000);
        Canvas c = new Canvas(target);
        c.scale(W / (float) screenW, H / (float) screenH);
        for (View v : layers) {
            if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.01f) continue;
            int save = v.getAlpha() < 0.99f
                    ? c.saveLayerAlpha(v.getLeft(), v.getTop(), v.getRight(), v.getBottom(), Math.round(v.getAlpha() * 255))
                    : c.save();
            c.translate(v.getLeft() + v.getTranslationX(), v.getTop() + v.getTranslationY());
            try {
                v.draw(c);
            } catch (RuntimeException e) {
                // A hardware bitmap cannot be drawn into a software canvas: leave that layer out.
            }
            c.restoreToCount(save);
        }
        publish(target);
    }

    /** The screensaver: a frame of the video on this surface, copied and scaled down on the GPU (no-op while one is on its way). */
    void capture(SurfaceView view, Handler handler) {
        if (copying || !view.getHolder().getSurface().isValid() || view.getWidth() == 0) return;
        Bitmap target = buffers[next()];
        copying = true;
        try {
            PixelCopy.request(view, target, result -> {
                copying = false;
                if (result != PixelCopy.SUCCESS) return;
                screenW = view.getWidth();
                screenH = view.getHeight();
                publish(target);
            }, handler);
        } catch (IllegalArgumentException e) {
            copying = false;   // the surface went away
        }
    }

    private int next() {
        return current == 0 ? 1 : 0;
    }

    private void publish(Bitmap target) {
        blur(target);
        current = target == buffers[0] ? 0 : 1;
        generation++;
        for (Iterator<WeakReference<Drawable>> it = users.iterator(); it.hasNext(); ) {
            Drawable d = it.next().get();
            if (d == null) it.remove();
            else d.invalidateSelf();
        }
    }

    /** Three box blurs, about a Gaussian: the glass is frosted, not clear. */
    private void blur(Bitmap b) {
        b.getPixels(pixels, 0, W, 0, 0, W, H);
        for (int pass = 0; pass < 3; pass++) {
            box(pixels, temp, W, H, 2, true);
            box(temp, pixels, W, H, 2, false);
        }
        b.setPixels(pixels, 0, W, 0, 0, W, H);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        int span = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(line, Math.max(0, Math.min(n - 1, i)), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < n; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((sr / span) << 16) | ((sg / span) << 8) | (sb / span);
                int add = in[index(line, Math.min(n - 1, i + r + 1), w, horizontal)];
                int drop = in[index(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                sb += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
