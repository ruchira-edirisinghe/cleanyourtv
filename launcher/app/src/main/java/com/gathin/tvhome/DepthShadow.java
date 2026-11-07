package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Raised type with no panel behind it: set as a TextView's background, it draws the view's own letters again a few
 * pixels lower as a dark solid side, under a soft wide shadow, and the view's text then lands on top as usual. The
 * clock, date and greeting on Home (liquid_glass) and on the screensaver stand off any picture this way.
 */
final class DepthShadow extends Drawable {
    /**
     * The letters' side, lit from above: a muted indigo just under them, deepening to near black at the bottom, so
     * it reads as depth over a dark scene as well as a bright one; and the soft shadow it casts.
     */
    private static final int SIDE_NEAR = 0x5C5688, SIDE_FAR = 0x14122A, SHADOW = 0xA6000000;

    private final TextView view;
    /** How far the side goes down, the shadow's blur and its drop, in px. */
    private final float depth, blur, drop;

    private DepthShadow(TextView view, float depth, float blur, float drop) {
        this.view = view;
        this.depth = depth;
        this.blur = blur;
        this.drop = drop;
    }

    /**
     * Raises the view's text by depthDp, casting a shadow blurDp soft. The view grows by the room the two need, so
     * they are drawn inside it, and gives that room back with negative margins once it is laid out in its parent: the
     * lines around it stay where they were, and the few layouts around it let it reach over their edges.
     */
    static void apply(TextView view, float depthDp, float blurDp) {
        float density = view.getResources().getDisplayMetrics().density;
        view.setShadowLayer(0f, 0f, 0f, 0);
        view.setBackground(new DepthShadow(view, depthDp * density, blurDp * density, blurDp * density * 0.35f));
        int bottom = Math.round((depthDp + blurDp * 0.7f) * density), right = bottom / 2;
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight() + right,
                view.getPaddingBottom() + bottom);
        if (view.isAttachedToWindow()) {
            giveBack(view, right, bottom);
            return;
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.removeOnAttachStateChangeListener(this);
                giveBack(v, right, bottom);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
            }
        });
    }

    private static void giveBack(View view, int right, int bottom) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            lp.bottomMargin -= bottom;
            lp.setMarginEnd(lp.getMarginEnd() - right);
            view.setLayoutParams(lp);
        }
        ViewParent parent = view.getParent();
        for (int i = 0; i < 3 && parent instanceof ViewGroup; i++) {
            ((ViewGroup) parent).setClipChildren(false);
            ((ViewGroup) parent).setClipToPadding(false);
            parent = parent.getParent();
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Layout layout = view.getLayout();
        if (layout == null) return;
        TextPaint paint = view.getPaint();
        int color = paint.getColor();
        int alpha = Math.round(((view.getCurrentTextColor() >>> 24) & 0xFF) * 0.9f);
        int steps = Math.max(1, Math.round(depth));
        float step = depth / steps;
        c.save();
        c.translate(view.getCompoundPaddingLeft(), view.getExtendedPaddingTop());
        for (int i = steps; i >= 1; i--) {
            // The lowest copy casts the shadow; the ones above it close the side up to the letters.
            if (i == steps) {
                paint.setShadowLayer(blur, 0f, drop, scaleAlpha(SHADOW, alpha / 255f));
            }
            paint.setColor((alpha << 24) | mix(SIDE_NEAR, SIDE_FAR, steps == 1 ? 1f : (i - 1f) / (steps - 1f)));
            c.save();
            c.translate(i * step * 0.3f, i * step);
            layout.draw(c);
            c.restore();
            if (i == steps) paint.clearShadowLayer();
        }
        c.restore();
        paint.setColor(color);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scaleAlpha(int argb, float f) {
        return (Math.round(((argb >>> 24) & 0xFF) * f) << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
