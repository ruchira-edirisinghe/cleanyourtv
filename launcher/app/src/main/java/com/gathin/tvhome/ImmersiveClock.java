package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Date;
import java.util.Locale;

/**
 * immersive_home: the date over a large time in the middle of Home, as on a phone's lock screen. The time is not
 * painted white on top of the wallpaper but blended into it: an overlay pass lifts and saturates the picture inside
 * the digits, then a faint white pass frosts them, so the wallpaper (or the focused app's banner) shows through the
 * numbers and their colour changes with it. Both passes blend with what is already drawn under the view, so the view
 * must never be drawn through an offscreen layer: it is never faded with setAlpha, only shown or hidden.
 * Redraws itself at each minute change while attached.
 */
final class ImmersiveClock extends View {
    /** Past the minute change, so the redraw cannot land just before it and repaint the old minute. */
    private static final long MARGIN_MS = 250L;

    private final float density;
    private final String clockMode;
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint blend = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint.FontMetrics dateMetrics = new Paint.FontMetrics();
    private final Paint.FontMetrics timeMetrics = new Paint.FontMetrics();
    private String dateText = "", timeText = "";
    /** Gap between the date's baseline and the top of the digits. */
    private final float gap;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            long now = System.currentTimeMillis();
            postDelayed(this, 60_000L - now % 60_000L + MARGIN_MS);
        }
    };

    ImmersiveClock(Context context, Typeface dateFace, Typeface timeFace, float timeSp, String clockMode) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        float scaled = context.getResources().getDisplayMetrics().scaledDensity;
        this.clockMode = clockMode;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);

        date.setTypeface(dateFace);
        date.setTextSize(19f * scaled);
        date.setColor(0xF2FFFFFF);
        date.setTextAlign(Paint.Align.CENTER);
        date.setShadowLayer(10f * density, 0f, 1f * density, 0x59000000);
        date.getFontMetrics(dateMetrics);

        for (Paint p : new Paint[]{blend, frost}) {
            p.setTypeface(timeFace);
            p.setTextSize(timeSp * scaled);
            p.setTextAlign(Paint.Align.CENTER);
            p.setLetterSpacing(-0.02f);
        }
        // Overlay keeps the picture's detail and lifts its light parts; screen then frosts the darks so the digits
        // always read lighter than what is around them, whatever the wallpaper.
        blend.setColor(Color.WHITE);
        blend.setAlpha(165);
        blend.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        frost.setColor(Color.WHITE);
        frost.setAlpha(112);
        frost.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        blend.getFontMetrics(timeMetrics);
        gap = 6f * density;
        update();
    }

    /** Height of the digits' box above the baseline that matters here: the cap height of "0". */
    private float digitHeight() {
        android.graphics.Rect b = new android.graphics.Rect();
        blend.getTextBounds("0", 0, 1, b);
        return b.height();
    }

    private void update() {
        Locale locale = Locale.getDefault();
        Date now = new Date();
        String d = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now).toString();
        String pattern = ClockFormat.is24(getContext(), clockMode) ? "HH:mm" : "h:mm";
        String t = DateFormat.format(pattern, now).toString();
        if (d.equals(dateText) && t.equals(timeText)) return;
        dateText = d;
        timeText = t;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Back from an app or the screensaver: the minute may have changed while Home was hidden.
        if (visibility == VISIBLE && isAttachedToWindow()) {
            removeCallbacks(tick);
            tick.run();
        }
    }

    /** The space from the view's top to the bottom of the digits. */
    int contentHeight() {
        return Math.round(-dateMetrics.ascent + dateMetrics.descent + gap + digitHeight() + 4 * density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), contentHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float dateBase = -dateMetrics.ascent;
        canvas.drawText(dateText, cx, dateBase, date);
        float timeBase = dateBase + dateMetrics.descent + gap + digitHeight();
        canvas.drawText(timeText, cx, timeBase, blend);
        canvas.drawText(timeText, cx, timeBase, frost);
    }
}
