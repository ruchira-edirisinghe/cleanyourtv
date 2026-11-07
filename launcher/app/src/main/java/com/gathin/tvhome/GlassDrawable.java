package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * liquid_glass: a rounded slab of glass. On Android 13 and later it shows {@link GlassBackdrop}'s copy of what is
 * behind it, bent inwards near the edges like a lens, lifted and a little more vivid, with a light catching its rim;
 * before that, or until the first copy, a frosted gradient with the same rim.
 */
final class GlassDrawable extends Drawable {

    /** How a piece of glass looks. */
    static final class Look {
        /** Colour mixed over what shows through, 0xAARRGGBB: alpha is how much. */
        final int tint;
        /** Brightening of what shows through (0.15 lifts it a little). */
        final float lift;
        /** Strength of the light on the rim, 0 to 1.5. */
        final float rim;
        /** How far the edge bends what is behind it, in dp. */
        final float bend;

        Look(int tint, float lift, float rim, float bend) {
            this.tint = tint;
            this.lift = lift;
            this.rim = rim;
            this.bend = bend;
        }
    }

    /** Clear glass over Home's backdrop: the header, the button bar and the dock band. */
    static final Look CLEAR = new Look(0x14FFFFFF, 0.16f, 1f, 16f);
    /** The focused button: milky glass, so its dark label reads like on the old white pill. */
    static final Look MILK = new Look(0xD6FFFFFF, 0.1f, 1.2f, 10f);
    /** A button switched on (pill_bg's activated blue). */
    static final Look ACTIVE = new Look(0x993B82F6, 0.1f, 1f, 10f);

    private final GlassBackdrop backdrop;
    private final Look look;
    private final float density;
    /** Corner radius in px; negative: half the height (a capsule). */
    private final float radius;
    private final RectF rect = new RectF();
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] at = new int[2];
    private float fallbackTop = Float.NaN, fallbackBottom = Float.NaN;
    private Object shader;   // Lens, on Android 13 and later
    private int alpha = 255;

    GlassDrawable(GlassBackdrop backdrop, Look look, float radiusPx, float density) {
        this.backdrop = backdrop;
        this.look = look;
        this.radius = radiusPx;
        this.density = density;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, density));
        if (backdrop != null) backdrop.register(this);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        rect.set(getBounds());
        if (rect.isEmpty()) return;
        float r = radius < 0 ? rect.height() / 2f : Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        float originX = 0, originY = 0;
        // In a StateListDrawable the callback is the container; the view is its callback.
        Object host = getCallback();
        while (host instanceof Drawable) host = ((Drawable) host).getCallback();
        if (host instanceof View) {
            ((View) host).getLocationInWindow(at);
            originX = at[0];
            originY = at[1];
        }
        draw(canvas, rect, r, originX, originY);
    }

    /**
     * Draws the glass in rect (canvas coordinates) with corners r; the canvas's (0,0) is at (originX, originY) on the
     * screen. DockDecoration calls this for its band.
     */
    void draw(Canvas canvas, RectF rect, float r, float originX, float originY) {
        Bitmap copy = backdrop == null ? null : backdrop.bitmap();
        if (copy != null && Build.VERSION.SDK_INT >= 33) {
            if (shader == null) shader = new Lens();
            ((Lens) shader).draw(canvas, rect, r, originX, originY, copy);
            return;
        }
        if (rect.top != fallbackTop || rect.bottom != fallbackBottom) {
            int a = Math.min(255, Math.round((look.tint >>> 24) * 0.6f) + 40);
            int rgb = look.tint & 0xFFFFFF;
            fallback.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, (a << 24) | rgb,
                    ((a / 3) << 24) | rgb, Shader.TileMode.CLAMP));
            rimPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x80FFFFFF, 0x1FFFFFFF,
                    Shader.TileMode.CLAMP));
            fallbackTop = rect.top;
            fallbackBottom = rect.bottom;
        }
        fallback.setAlpha(alpha);
        rimPaint.setAlpha(alpha);
        canvas.drawRoundRect(rect, r, r, fallback);
        canvas.drawRoundRect(rect, r, r, rimPaint);
    }

    @RequiresApi(33)
    private final class Lens {
        private final RuntimeShader runtime = new RuntimeShader(AGSL);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private Bitmap shaded;
        private BitmapShader image;

        Lens() {
            float a = (look.tint >>> 24) / 255f;
            runtime.setFloatUniform("tint", ((look.tint >> 16) & 0xFF) / 255f, ((look.tint >> 8) & 0xFF) / 255f,
                    (look.tint & 0xFF) / 255f, a);
            runtime.setFloatUniform("lift", look.lift);
            runtime.setFloatUniform("rimAmt", look.rim);
            runtime.setFloatUniform("bend", look.bend * density);
            paint.setShader(runtime);
        }

        void draw(Canvas canvas, RectF rect, float r, float originX, float originY, Bitmap copy) {
            if (copy != shaded) {
                image = new BitmapShader(copy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                shaded = copy;
            }
            // The copy covers the screen: its pixel (x, y) is at screen (x, y) times the scale, here minus the origin.
            matrix.setScale(backdrop.screenW / (float) copy.getWidth(), backdrop.screenH / (float) copy.getHeight());
            matrix.postTranslate(-originX, -originY);
            image.setLocalMatrix(matrix);
            runtime.setInputShader("backdrop", image);
            runtime.setFloatUniform("origin", rect.left, rect.top);
            runtime.setFloatUniform("size", rect.width(), rect.height());
            runtime.setFloatUniform("radius", r);
            runtime.setFloatUniform("opacity", alpha / 255f);
            paint.setShader(runtime);
            canvas.drawRoundRect(rect.left - 1, rect.top - 1, rect.right + 1, rect.bottom + 1, r + 1, r + 1, paint);
        }
    }

    /**
     * The lens. d is the distance to the rounded edge (negative inside), n the outward normal: near the edge the
     * backdrop is read from further in (a thick rim bends light inwards), with red and blue split a little; inside it
     * is magnified slightly, lifted and made more vivid, tinted, and lit along the rim from the top left.
     */
    private static final String AGSL = ""
            + "uniform shader backdrop;\n"
            + "uniform float2 origin;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float4 tint;\n"
            + "uniform float lift;\n"
            + "uniform float rimAmt;\n"
            + "uniform float bend;\n"
            + "uniform float opacity;\n"
            + "float sdf(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + r;\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 hb = size * 0.5;\n"
            + "  float2 c = origin + hb;\n"
            + "  float2 p = xy - c;\n"
            + "  float r = min(radius, min(hb.x, hb.y));\n"
            + "  float d = sdf(p, hb, r);\n"
            + "  float aa = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (aa <= 0.0) return half4(0.0);\n"
            + "  float2 e = float2(0.75, 0.0);\n"
            + "  float2 g = float2(sdf(p + e.xy, hb, r) - sdf(p - e.xy, hb, r), sdf(p + e.yx, hb, r) - sdf(p - e.yx, hb, r));\n"
            + "  float2 n = g / max(length(g), 0.0001);\n"
            + "  float band = max(min(r * 1.2, min(hb.x, hb.y)), 6.0);\n"
            + "  float edge = 1.0 - clamp(-d / band, 0.0, 1.0);\n"
            + "  float bendAmt = edge * edge * edge;\n"
            + "  float2 sp = c + p * 0.95 - n * bendAmt * bend;\n"
            + "  float2 ca = n * bendAmt * bend * 0.14;\n"
            + "  half3 col = half3(backdrop.eval(sp + ca).r, backdrop.eval(sp).g, backdrop.eval(sp - ca).b);\n"
            + "  half l = dot(col, half3(0.2126, 0.7152, 0.0722));\n"
            + "  col = mix(half3(l), col, 1.35);\n"
            + "  col = col * (1.0 + lift) + lift * 0.10;\n"
            + "  col = mix(col, half3(tint.rgb), half(tint.a));\n"
            + "  float2 light = float2(-0.53, -0.85);\n"
            + "  float facing = dot(n, light);\n"
            + "  float rimLine = 1.0 - smoothstep(0.0, 2.2, -d);\n"
            + "  float soft = edge * edge;\n"
            + "  col += rimAmt * rimLine * (0.22 + 0.55 * max(facing, 0.0) + 0.30 * max(-facing, 0.0));\n"
            + "  col += rimAmt * soft * 0.07 * (0.6 + 0.4 * facing);\n"
            + "  float y = (xy.y - origin.y) / size.y;\n"
            + "  col += rimAmt * 0.045 * (1.0 - smoothstep(0.0, 0.5, y));\n"
            + "  float a = aa * opacity;\n"
            + "  return half4(clamp(col, 0.0, 1.0) * a, a);\n"
            + "}\n";

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
