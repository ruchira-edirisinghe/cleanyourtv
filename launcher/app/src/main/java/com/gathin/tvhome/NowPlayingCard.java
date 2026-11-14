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
 * now_playing: a glass card with the song that plays (NowPlaying): its cover, a kicker with small dancing bars, the
 * title, the artist, and a thin bar with the time in and the length. Home has it upright, the cover on top, on the right
 * between the top buttons and the dock, its cover shrinking to fit there (fitHeight); the screensaver has it lying
 * down, the cover at the left, in its bottom right corner. It only fades in and out, never moves. While shown, the
 * bars and the time tick.
 */
final class NowPlayingCard extends FrameLayout {
    /** The upright card's width and about its height, in dp. */
    static final int W = 176, H = 264;
    private static final int PAD = 13, ART = W - 2 * PAD;
    /** The card lying down: its size and its cover's, in dp. */
    private static final int WIDE_W = 440, WIDE_H = 104, WIDE_ART = 76;
    private static final int GREEN = 0xFF1ED760;
    private static final long FADE_MS = 320, TICK_MS = 1_000;

    private final float density;
    private final boolean upright;
    private final ImageView art;
    private final TextView kicker, title, artist, elapsed, length;
    private final View fill;
    private final Bars bars;
    private NowPlaying.Track track;
    /** The card's height without the cover, once laid out (0 before). */
    private int chrome;
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

    /** @param upright Home's card, the cover on top; false lays it down, the cover at the left (the screensaver's). */
    NowPlayingCard(Context context, GlassBackdrop backdrop, boolean upright) {
        super(context);
        this.upright = upright;
        density = context.getResources().getDisplayMetrics().density;
        setBackground(new GlassDrawable(backdrop, GlassDrawable.SMOKE, dp(22), density));
        setClipToOutline(true);
        setOutlineProvider(rounded(22));
        setAlpha(0f);
        setVisibility(View.GONE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setOutlineProvider(rounded(upright ? 14 : 12));
        art.setBackgroundColor(0x33000000);

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        bars = new Bars(context);
        top.addView(bars, upright ? new LinearLayout.LayoutParams(dp(12), dp(10))
                : new LinearLayout.LayoutParams(dp(13), dp(11)));
        kicker = text(context, upright ? 10 : 11, GREEN, R.string.font_label_bold);
        kicker.setLetterSpacing(upright ? 0.16f : 0.18f);
        kicker.setSingleLine(true);
        kicker.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        kl.leftMargin = dp(upright ? 7 : 8);
        top.addView(kicker, kl);

        title = text(context, upright ? 17 : 19, Color.WHITE, R.string.font_title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);

        artist = text(context, upright ? 12 : 13, 0xB3FFFFFF, R.string.font_label);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);

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
        trl.leftMargin = dp(upright ? 7 : 8);
        trl.rightMargin = dp(upright ? 7 : 8);
        bar.addView(trackView, trl);
        length = text(context, 10, 0x99FFFFFF, R.string.font_label_bold);
        bar.addView(length, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(2);
        if (upright) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));
            column.addView(art, new LinearLayout.LayoutParams(dp(ART), dp(ART)));
            LinearLayout.LayoutParams topl = new LinearLayout.LayoutParams(-1, -2);
            topl.topMargin = dp(12);
            column.addView(top, topl);
            tl.topMargin = dp(7);
            column.addView(title, tl);
            column.addView(artist, al);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(9);
            column.addView(bar, bl);
            addView(column, new FrameLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (WIDE_H - WIDE_ART) / 2;
            row.setPadding(dp(pad), dp(pad), dp(20), dp(pad));
            row.addView(art, new LinearLayout.LayoutParams(dp(WIDE_ART), dp(WIDE_ART)));
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(WIDE_ART), 1f);
            cl.leftMargin = dp(16);
            row.addView(column, cl);
            column.addView(top, new LinearLayout.LayoutParams(-1, -2));
            tl.topMargin = dp(6);
            column.addView(title, tl);
            column.addView(artist, al);
            column.addView(new View(context), new LinearLayout.LayoutParams(1, 0, 1f));
            column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            addView(row, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /** The card's layout params for its shape; the caller sets the gravity or the position. */
    FrameLayout.LayoutParams params(int gravity) {
        return upright ? new FrameLayout.LayoutParams(dp(W), ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
                : new FrameLayout.LayoutParams(dp(WIDE_W), dp(WIDE_H), gravity);
    }

    /**
     * Home: the cover shrinks (or grows back to its full size) so the card is this tall; the card is as wide as the cover
     * and its margins. Changes nothing when it already fits.
     */
    void fitHeight(int heightPx) {
        if (!upright) return;
        int rest = chrome > 0 ? chrome : dp(H - ART);
        int side = Math.max(dp(96), Math.min(dp(ART), heightPx - rest));
        ViewGroup.LayoutParams alp = art.getLayoutParams();
        if (Math.abs(alp.width - side) <= 1) return;
        alp.width = side;
        alp.height = side;
        art.setLayoutParams(alp);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.width = targetWidth();
            setLayoutParams(lp);
        }
    }

    /** The card's width for its cover now, in px. */
    int targetWidth() {
        return art.getLayoutParams().width + 2 * dp(PAD);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) chrome = h - art.getLayoutParams().height;
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
        String app = upright ? "" : appLabel(t.pkg);   // the upright card has no room for the app's name
        String head = app.isEmpty() ? getContext().getString(R.string.now_playing)
                : getContext().getString(R.string.now_playing_on, app);
        kicker.setText(head.toUpperCase(Locale.getDefault()));
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

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
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
