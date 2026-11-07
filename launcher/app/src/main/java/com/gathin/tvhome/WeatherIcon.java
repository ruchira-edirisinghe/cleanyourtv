package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/** The screensaver's weather picture, drawn: a sun or moon, clouds, and rain, snow, a bolt or mist under them. */
final class WeatherIcon extends Drawable {
    private static final int SUN_TOP = 0xFFFFE08A, SUN_BOTTOM = 0xFFFF9F43, MOON = 0xFFE6EBFF, RAIN = 0xFF7FD4FF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cut = new Path();
    private int code;
    private boolean day = true;

    WeatherIcon() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int code, boolean day) {
        this.code = code;
        this.day = day;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        boolean clear = code <= 1, partly = code == 2, fog = code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95;
        if (clear) {
            orb(c, s * 0.5f, s * 0.5f, s * 0.24f, s, true);
        } else if (partly) {
            orb(c, s * 0.38f, s * 0.36f, s * 0.19f, s, true);
            cloud(c, s * 0.58f, s * 0.64f, s * 0.36f, 0xF7FFFFFF, 0xC8D9E2F0);
        } else if (fog) {
            line.setStrokeWidth(s * 0.07f);
            line.setColor(0xD9FFFFFF);
            for (int i = 0; i < 3; i++) {
                float y = s * (0.34f + i * 0.17f);
                float inset = s * (i == 1 ? 0.1f : 0.18f);
                c.drawLine(inset, y, s - inset, y, line);
            }
        } else {
            float cy = rain || snow || storm ? s * 0.42f : s * 0.54f;
            int top = storm ? 0xFFB9C0D0 : 0xF7FFFFFF, bottom = storm ? 0xFF6E7890 : 0xC8D9E2F0;
            cloud(c, s * 0.5f, cy, s * 0.42f, top, bottom);
            if (rain || storm) {
                line.setStrokeWidth(s * 0.055f);
                line.setColor(RAIN);
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.34f + i * 0.16f);
                    c.drawLine(x, s * 0.7f, x - s * 0.05f, s * 0.86f, line);
                }
            }
            if (snow) {
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                for (int i = 0; i < 3; i++) c.drawCircle(s * (0.34f + i * 0.16f), s * (0.76f + (i % 2) * 0.08f), s * 0.04f, fill);
            }
            if (storm) {
                path.reset();
                path.moveTo(s * 0.52f, s * 0.6f);
                path.lineTo(s * 0.4f, s * 0.8f);
                path.lineTo(s * 0.5f, s * 0.8f);
                path.lineTo(s * 0.44f, s * 0.97f);
                path.lineTo(s * 0.64f, s * 0.72f);
                path.lineTo(s * 0.53f, s * 0.72f);
                path.lineTo(s * 0.6f, s * 0.6f);
                path.close();
                fill.setShader(null);
                fill.setColor(SUN_TOP);
                c.drawPath(path, fill);
            }
        }
        c.restore();
    }

    /** The sun with its rays, or by night the moon. */
    private void orb(Canvas c, float x, float y, float r, float s, boolean rays) {
        if (!day) {
            path.reset();
            path.addCircle(x, y, r * 1.1f, Path.Direction.CW);
            cut.reset();
            cut.addCircle(x + r * 0.55f, y - r * 0.4f, r * 0.95f, Path.Direction.CW);
            path.op(cut, Path.Op.DIFFERENCE);
            fill.setShader(null);
            fill.setColor(MOON);
            c.drawPath(path, fill);
            return;
        }
        fill.setShader(new LinearGradient(0, y - r, 0, y + r, SUN_TOP, SUN_BOTTOM, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, fill);
        if (!rays) return;
        line.setStrokeWidth(s * 0.045f);
        line.setColor(SUN_TOP);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(x + dx * r * 1.4f, y + dy * r * 1.4f, x + dx * r * 1.85f, y + dy * r * 1.85f, line);
        }
    }

    /** A cloud w wide centred on (x, y): a flat base and three puffs. */
    private void cloud(Canvas c, float x, float y, float w, int top, int bottom) {
        float h = w * 0.5f;
        path.reset();
        path.addRoundRect(x - w, y - h * 0.1f, x + w, y + h * 0.75f, h * 0.45f, h * 0.45f, Path.Direction.CW);
        path.addCircle(x - w * 0.45f, y, h * 0.55f, Path.Direction.CW);
        path.addCircle(x + w * 0.05f, y - h * 0.3f, h * 0.85f, Path.Direction.CW);
        path.addCircle(x + w * 0.55f, y + h * 0.05f, h * 0.55f, Path.Direction.CW);
        fill.setShader(new LinearGradient(0, y - h * 1.2f, 0, y + h * 0.75f, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        line.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
