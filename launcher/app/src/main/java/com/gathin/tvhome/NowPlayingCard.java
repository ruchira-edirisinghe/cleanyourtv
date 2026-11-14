package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * now_playing: an upright glass card with the song that plays (NowPlaying): its cover on top, then a kicker with small
 * dancing bars, the title, the artist, and a thin bar with the time in and the length. It only fades in and out, never
 * moves: Home puts it on the right between the top buttons and the dock, the screensaver in its bottom right corner.
 * While shown, the bars and the time tick.
 */
final class NowPlayingCard extends FrameLayout {
    /** The card's width and about its height, in dp: the same on Home and on the screensaver. */
    static final int W = 176, H = 264;
    private static final int PAD = 13, ART = W - 2 * PAD, GREEN = 0xFF1ED760;
    private static final long FADE_MS = 320, TICK_MS = 1_000;

    private final float density;
    private final ImageView art;
    private final TextView kicker, title, artist, elapsed, length;
    private final View fill;
    private final Bars bars;
    private NowPlaying.Track track;
    /** A song to show, and whether its place lets it show now (Home hides it once the apps scroll). */
    private boolean hasSong, allowed = true, visibleNow;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            removeCallbacks(this);
            if (!visibleNow || track == null) return;
            showTime();
            postDelayed(this, TICK_MS);
        }
    };

    NowPlayingCard(Context context, GlassBackdrop backdrop) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackground(new GlassDrawable(backdrop, GlassDrawable.SMOKE, dp(22), density));
        setClipToOutline(true);
        setOutlineProvider(rounded(22));
        setAlpha(0f);
        setVisibility(View.GONE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setOutlineProvider(rounded(14));
        art.setBackgroundColor(0x33000000);
        column.addView(art, new LinearLayout.LayoutParams(dp(ART), dp(ART)));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        bars = new Bars(context);
        top.addView(bars, new LinearLayout.LayoutParams(dp(12), dp(10)));
        kicker = text(context, 10, GREEN, R.string.font_label_bold);
        kicker.setLetterSpacing(0.16f);
        kicker.setSingleLine(true);
        kicker.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        kl.leftMargin = dp(7);
        top.addView(kicker, kl);
        LinearLayout.LayoutParams topl = new LinearLayout.LayoutParams(-1, -2);
        topl.topMargin = dp(12);
        column.addView(top, topl);

        title = text(context, 17, Color.WHITE, R.string.font_title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(7);
        column.addView(title, tl);

        artist = text(context, 12, 0xB3FFFFFF, R.string.font_label);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(2);
        column.addView(artist, al);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(context, 10, 0x99FFFFFF, R.string.font_label_bold);
        bar.addView(elapsed, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout trackView = new FrameLayout(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(2));
        trackBg.setColor(0x33FFFFFF);
        trackView.setBackground(trackBg);
        fill = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(2));
        fillBg.setColor(0xE6FFFFFF);
        fill.setBackground(fillBg);
        fill.setPivotX(0f);
        fill.setScaleX(0f);
        trackView.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(0, dp(3), 1f);
        trl.leftMargin = dp(7);
        trl.rightMargin = dp(7);
        bar.addView(trackView, trl);
        length = text(context, 10, 0x99FFFFFF, R.string.font_label_bold);
        bar.addView(length, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(9);
        column.addView(bar, bl);

        addView(column, new FrameLayout.LayoutParams(-1, -2));
    }

    /** The card's layout params: its width, and the height its content takes; the caller sets the gravity or the position. */
    static FrameLayout.LayoutParams params(Context context, int gravity) {
        float d = context.getResources().getDisplayMetrics().density;
        return new FrameLayout.LayoutParams(Math.round(W * d), ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }

    /** Shows this song (null: nothing plays, the card fades out). */
    void setTrack(NowPlaying.Track t) {
        NowPlaying.Track before = track;
        track = t;
        hasSong = t != null;
        if (t != null) {
            if (!t.sameSong(before)) showSong(t);
            showTime();
        }
        settle();
    }

    /** Whether the card's place lets it show (false fades it out even while a song plays). */
    void setAllowed(boolean allowed) {
        if (this.allowed == allowed) return;
        this.allowed = allowed;
        settle();
    }

    boolean hasSong() {
        return hasSong;
    }

    private void showSong(NowPlaying.Track t) {
        if (t.art != null) art.setImageBitmap(t.art);
        else art.setImageDrawable(appIcon(t.pkg));
        kicker.setText(getContext().getString(R.string.now_playing).toUpperCase(Locale.getDefault()));
        title.setText(t.title);
        String by = t.artist.isEmpty() ? t.album : t.artist;
        artist.setText(by);
        artist.setVisibility(by.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showTime() {
        if (track == null) return;
        long pos = track.positionNow();
        boolean known = track.duration > 0;
        elapsed.setText(clock(pos));
        length.setText(known ? clock(track.duration) : "");
        fill.setScaleX(known ? Math.min(1f, pos / (float) track.duration) : 0f);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Fades to what it should be now; gone once faded out, so it costs nothing while hidden. */
    private void settle() {
        boolean show = hasSong && allowed;
        if (show == visibleNow) return;
        visibleNow = show;
        animate().cancel();
        if (show) {
            setVisibility(View.VISIBLE);
            animate().alpha(1f).setDuration(FADE_MS).start();
            bars.setRunning(true);
            post(tick);
        } else {
            removeCallbacks(tick);
            bars.setRunning(false);
            animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                if (!visibleNow) setVisibility(View.GONE);
            }).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        bars.setRunning(false);
        super.onDetachedFromWindow();
    }

    private Drawable appIcon(String pkg) {
        try {
            return getContext().getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private TextView text(Context context, float sp, int color, int fontRes) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(context, fontRes));
        return t;
    }

    private ViewOutlineProvider rounded(float radiusDp) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * density);
            }
        };
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Three small bars that rise and fall like a level meter while the song plays, about 15 times a second. */
    private static final class Bars extends View {
        private static final long FRAME_MS = 66;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean running;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                invalidate();
                if (running) postDelayed(this, FRAME_MS);
            }
        };

        Bars(Context context) {
            super(context);
            paint.setColor(GREEN);
        }

        void setRunning(boolean on) {
            if (running == on) return;
            running = on;
            removeCallbacks(frame);
            if (on) post(frame);
            else invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float gap = w / 7f, bw = (w - 2 * gap) / 3f, r = bw / 2f;
            double t = SystemClock.uptimeMillis() / 1000.0;
            for (int i = 0; i < 3; i++) {
                float level = running
                        ? (float) (0.55 + 0.45 * Math.sin(t * (5.1 + i * 1.7) + i * 2.1) * Math.sin(t * (2.3 + i * 0.9)))
                        : 0.3f;
                float bh = Math.max(bw, h * Math.max(0.2f, Math.min(1f, level)));
                float x = i * (bw + gap);
                rect.set(x, h - bh, x + bw, h);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }
}
