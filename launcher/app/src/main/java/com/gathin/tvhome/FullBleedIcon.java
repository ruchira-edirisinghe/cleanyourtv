package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * icon_grid: an adaptive icon's two layers drawn to fill the whole tile, the way a phone draws its app icons,
 * instead of the system's masked shape floating on a plate. The layers are 1.5 times the visible icon (108dp
 * for 72dp), so they run a quarter past each edge; the tile's rounded outline clips them.
 */
final class FullBleedIcon extends Drawable {
    private final Drawable background, foreground;

    FullBleedIcon(AdaptiveIconDrawable icon) {
        background = copy(icon.getBackground());
        foreground = copy(icon.getForeground());
    }

    /** A private copy, so the bounds set here never move the layers of the icon drawn anywhere else. */
    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState state = d.getConstantState();
        return state != null ? state.newDrawable().mutate() : d;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int dx = b.width() / 4, dy = b.height() / 4;
        if (background != null) {
            background.setBounds(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy);
            background.draw(canvas);
        }
        if (foreground != null) {
            foreground.setBounds(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy);
            foreground.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (background != null) background.setAlpha(alpha);
        if (foreground != null) foreground.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        if (background != null) background.setColorFilter(filter);
        if (foreground != null) foreground.setColorFilter(filter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
