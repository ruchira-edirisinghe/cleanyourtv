package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live screensaver: looping animated scenes (the videos in the theme folder's saver/, see ThemeFiles), each shown
 * whole and at its full resolution, dipping through black to the next every so often. A liquid-glass panel at the top
 * centre holds the time, the date and a greeting, like a tablet's lock screen; now and then a glass card with the poster
 * of something left part-watched or a new episode (WatchHistory) rises in a bottom corner, and OK opens it. Any other key
 * ends the screensaver. Pick it with `settings put secure screensaver_components com.gathin.tvhome/.LiveSaverDream`.
 */
public class LiveSaverDream extends DreamService {
    private static final long CLIP_MS = 75_000, DIP_OUT_MS = 650, DIP_IN_MS = 900;
    private static final long CARD_FIRST_MS = 7_000, CARD_SHOW_MS = 11_000, CARD_GAP_MS = 20_000;
    private static final long BACKDROP_MS = 120, MINUTE_MS = 60_000;
    private static final int CYAN = 0xFF7FE7FF, VIOLET = 0xFFB18CFF, GOLD = 0xFFFFD27A;
    /** The poster card's size and its poster's, in dp: always the same, whatever it shows. */
    private static final int CARD_W = 452, CARD_H = 180, POSTER_W = 101, POSTER_H = 152;
    /** How far in from the screen's edges the panels sit, in dp. */
    private static final int MARGIN = 48;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final GlassBackdrop backdrop = new GlassBackdrop();
    private float density;
    private Config config;
    private boolean running;

    // the scenes
    private FrameLayout root;
    private SurfaceView screen;
    private MediaPlayer player;
    private View dip;
    private View plainBackground;
    private List<File> videos = Collections.emptyList();
    private int videoIndex;
    private boolean switching;

    // the clock
    private LinearLayout island;
    private TextView greeting;

    // the card
    private FrameLayout card;
    private ImageView cardPoster;
    private TextView cardKicker, cardTitle, cardMeta, cardPercent;
    private View cardBar, cardFill, cardSheen;
    private WatchHistory.Item cardItem;
    private int cardIndex;
    private boolean cardShown;
    private boolean cardRight;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        density = getResources().getDisplayMetrics().density;
        config = Config.load(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        plainBackground = new View(this);
        plainBackground.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B1036, 0xFF0B1630, 0xFF04060E}));
        root.addView(plainBackground, match());

        screen = new SurfaceView(this);
        screen.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (player != null) player.setDisplay(holder);
                else if (running) nextClip();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releasePlayer();
            }
        });
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Soft dark corners, so the glass and its text read over any scene.
        View vignette = new View(this);
        GradientDrawable shade = new GradientDrawable();
        shade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shade.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.75f);
        shade.setColors(new int[]{0x00000000, 0x00000000, 0x73000000});
        vignette.setBackground(shade);
        root.addView(vignette, match());

        buildIsland();
        buildCard();
        dip = new View(this);
        dip.setBackgroundColor(Color.BLACK);
        root.addView(dip, match());
        setContentView(root);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---- the clock ----------------------------------------------------------------------------------------------

    /**
     * At the top centre: the time in Sora, the date under it in Fraunces italic, then the greeting in Space Grotesk
     * capitals, all centred.
     */
    private void buildIsland() {
        island = new LinearLayout(this);
        island.setOrientation(LinearLayout.VERTICAL);
        island.setGravity(Gravity.CENTER_HORIZONTAL);
        island.setPadding(dp(44), dp(22), dp(44), dp(24));
        island.setBackground(new GlassDrawable(backdrop, GlassDrawable.SMOKE, dp(36), density));
        island.setAlpha(0f);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        style(time, 80, Color.WHITE, R.string.font_clock_bold);
        time.setLetterSpacing(-0.03f);
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(10), 0, dp(2), 0x40000000);
        top.addView(time, wrap());
        if (!DateFormat.is24HourFormat(this)) {
            TextClock ampm = new TextClock(this);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("");
            style(ampm, 16, 0xCCFFFFFF, R.string.font_label_bold);
            ampm.setAllCaps(true);
            LinearLayout.LayoutParams lp = wrap();
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(dp(6), 0, 0, dp(10));
            top.addView(ampm, lp);
        }
        island.addView(top, wrap());

        TextClock date = new TextClock(this);
        date.setFormat12Hour(getString(R.string.date_format_home));
        date.setFormat24Hour(getString(R.string.date_format_home));
        style(date, 26, Color.WHITE, R.string.font_title_italic);
        date.setSingleLine(true);
        LinearLayout.LayoutParams dl = wrap();
        dl.topMargin = dp(8);
        island.addView(date, dl);

        greeting = new TextView(this);
        style(greeting, 13, 0xD9FFFFFF, R.string.font_label_bold);
        greeting.setLetterSpacing(0.2f);
        greeting.setSingleLine(true);
        greeting.setAllCaps(true);   // after setSingleLine, which would replace the capitals
        LinearLayout.LayoutParams gl = wrap();
        gl.topMargin = dp(10);
        island.addView(greeting, gl);
        updateGreeting();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(MARGIN);
        root.addView(island, lp);
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22 ? R.string.good_evening : R.string.good_night;
        String name = config.greetingName;
        greeting.setText(name.isEmpty() ? getString(res) : getString(R.string.greeting_with_name, getString(res), name));
    }

    /** The greeting follows the time of day. */
    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            main.postDelayed(this, MINUTE_MS);
        }
    };

    // ---- the card -----------------------------------------------------------------------------------------------

    /** A fixed-size glass card: the poster on the left, and beside it what it is, how far in, and that OK opens it. */
    private void buildCard() {
        card = new FrameLayout(this);
        card.setBackground(new GlassDrawable(backdrop, GlassDrawable.SMOKE, dp(26), density));
        card.setClipToOutline(true);
        card.setOutlineProvider(rounded(26));
        card.setAlpha(0f);
        card.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(14), dp(22), dp(14));

        cardPoster = new ImageView(this);
        cardPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cardPoster.setClipToOutline(true);
        cardPoster.setOutlineProvider(rounded(14));
        cardPoster.setBackgroundColor(0x33000000);
        row.addView(cardPoster, new LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)));

        // The text fills the poster's height: kicker, title and meta at the top, the bar and the hint at the bottom.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(POSTER_H), 1f);
        cl.leftMargin = dp(20);
        row.addView(column, cl);

        cardKicker = new TextView(this);
        style(cardKicker, 11, CYAN, R.string.font_label_bold);
        cardKicker.setLetterSpacing(0.2f);
        cardKicker.setSingleLine(true);
        cardKicker.setAllCaps(true);
        column.addView(cardKicker, wrap());

        cardTitle = new TextView(this);
        style(cardTitle, 22, Color.WHITE, R.string.font_title);
        cardTitle.setMaxLines(2);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        cardTitle.setLineSpacing(0, 0.92f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(6);
        column.addView(cardTitle, tl);

        cardMeta = new TextView(this);
        style(cardMeta, 13, 0xB3FFFFFF, R.string.font_label);
        cardMeta.setSingleLine(true);
        cardMeta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(4);
        column.addView(cardMeta, ml);

        column.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // An XP-style bar: how far in, with the percentage at its end.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(3));
        trackBg.setColor(0x33FFFFFF);
        track.setBackground(trackBg);
        cardFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{CYAN, VIOLET});
        fillBg.setCornerRadius(dp(3));
        cardFill.setBackground(fillBg);
        cardFill.setPivotX(0f);
        track.addView(cardFill, new FrameLayout.LayoutParams(-1, -1));
        bar.addView(track, new LinearLayout.LayoutParams(0, dp(6), 1f));
        cardPercent = new TextView(this);
        style(cardPercent, 12, 0xCCFFFFFF, R.string.font_label_bold);
        LinearLayout.LayoutParams pl = wrap();
        pl.leftMargin = dp(10);
        bar.addView(cardPercent, pl);
        cardBar = bar;
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        style(hint, 11, 0x99FFFFFF, R.string.font_label);
        hint.setLetterSpacing(0.12f);
        hint.setSingleLine(true);
        hint.setAllCaps(true);
        hint.setText(R.string.saver_ok_hint);
        LinearLayout.LayoutParams hl = wrap();
        hl.topMargin = dp(10);
        column.addView(hint, hl);

        card.addView(row, match());
        // A shine that sweeps across the card as it lands.
        cardSheen = new View(this);
        cardSheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}));
        cardSheen.setRotation(18f);
        cardSheen.setScaleY(1.8f);
        cardSheen.setVisibility(View.INVISIBLE);
        card.addView(cardSheen, new FrameLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(card, new FrameLayout.LayoutParams(dp(CARD_W), dp(CARD_H)));
    }

    private ViewOutlineProvider rounded(float radiusDp) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * density);
            }
        };
    }

    private final Runnable showCard = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (!running || card.getVisibility() == View.VISIBLE) return;
            List<WatchHistory.Item> items = WatchHistory.shown(LiveSaverDream.this,
                    StremioSync.linked(LiveSaverDream.this), StremioSync.dismissed(LiveSaverDream.this),
                    ContinueRow.MAX_CARDS);
            List<WatchHistory.Item> usable = new ArrayList<>();
            for (WatchHistory.Item it : items) if (installed(it.pkg)) usable.add(it);
            if (usable.isEmpty()) {
                main.postDelayed(this, CARD_GAP_MS);
                return;
            }
            WatchHistory.Item item = usable.get(cardIndex++ % usable.size());
            String poster = WatchHistory.posterOf(item);
            String src = !poster.isEmpty() ? poster : !item.art.isEmpty() ? item.art : item.cover;
            int width = dp(POSTER_W) * 2;
            work.execute(() -> {
                Bitmap b = src.isEmpty() ? null : ContinueRow.picture(LiveSaverDream.this, src, width);
                main.post(() -> {
                    if (running) enterCard(item, b);
                });
            });
        }
    };

    /** The card rises in a bottom corner, left and right in turn, well below the clock. */
    private void enterCard(WatchHistory.Item item, Bitmap picture) {
        if (card.getVisibility() == View.VISIBLE) return;
        cardItem = item;
        if (picture != null) cardPoster.setImageBitmap(picture);
        else cardPoster.setImageDrawable(appBanner(item.pkg));
        cardKicker.setText(item.isNew ? R.string.saver_new_episode : R.string.saver_continue);
        cardKicker.setTextColor(item.isNew ? GOLD : CYAN);
        cardTitle.setText(item.title);
        String meta = ContinueRow.metaLine(this, item);
        cardMeta.setText(meta);
        cardMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        float progress = item.progress();
        cardBar.setVisibility(!item.isNew && progress > 0f ? View.VISIBLE : View.INVISIBLE);
        cardPercent.setText(getString(R.string.saver_percent, Math.round(progress * 100)));

        cardRight = !cardRight;
        float x = cardRight ? root.getWidth() - dp(CARD_W) - dp(MARGIN) : dp(MARGIN);
        float y = root.getHeight() - dp(CARD_H) - dp(MARGIN);
        card.setVisibility(View.VISIBLE);
        card.setTranslationX(x);
        card.setTranslationY(y + dp(36));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        cardFill.setScaleX(0f);
        card.animate().alpha(1f).translationY(y).scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(new OvershootInterpolator(1.1f)).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        card.animate().setListener(null);
                        if (!running) return;
                        cardShown = true;
                        cardFill.animate().scaleX(progress).setDuration(900)
                                .setInterpolator(new DecelerateInterpolator(2f)).start();
                        sweep();
                    }
                }).start();
        main.removeCallbacks(hideCard);
        main.postDelayed(hideCard, CARD_SHOW_MS);
    }

    private void sweep() {
        cardSheen.setVisibility(View.VISIBLE);
        cardSheen.setTranslationX(-dp(120));
        cardSheen.animate().translationX(dp(CARD_W) + dp(40)).setDuration(1100)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> cardSheen.setVisibility(View.INVISIBLE)).start();
    }

    private final Runnable hideCard = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            cardShown = false;
            card.animate().setListener(null);
            card.animate().alpha(0f).translationYBy(dp(30)).scaleX(0.96f).scaleY(0.96f).setDuration(600)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                        card.setVisibility(View.GONE);
                        cardItem = null;
                        cardPoster.setImageDrawable(null);
                    }).start();
            if (running) main.postDelayed(showCard, CARD_GAP_MS);
        }
    };

    private boolean installed(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Drawable appBanner(String pkg) {
        try {
            Drawable d = getPackageManager().getApplicationBanner(pkg);
            return d != null ? d : getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ---- the scenes ---------------------------------------------------------------------------------------------

    private List<File> findVideos() {
        File dir = Config.dir(this);
        File[] files = dir == null ? null : new File(dir, ThemeFiles.SAVER).listFiles(
                (d, name) -> ThemeFiles.saverVideo(name));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.shuffle(list, random);
        return list;
    }

    private final Runnable nextClipTimer = this::nextClip;

    /** Dips to black, starts the next scene, and comes back up once its first frame is on the screen. */
    private void nextClip() {
        main.removeCallbacks(nextClipTimer);
        if (!running || videos.isEmpty() || switching) return;
        if (!screen.getHolder().getSurface().isValid()) return;   // surfaceCreated starts it
        switching = true;
        File file = videos.get(videoIndex++ % videos.size());
        dip.animate().cancel();
        dip.animate().alpha(1f).setDuration(player == null ? 0 : DIP_OUT_MS).withEndAction(() -> play(file)).start();
    }

    private void play(File file) {
        if (!running) return;
        releasePlayer();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setDisplay(screen.getHolder());
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((p, w, h) -> fit(w, h));
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && p == player) {
                    switching = false;
                    plainBackground.setVisibility(View.GONE);
                    dip.animate().alpha(0f).setDuration(DIP_IN_MS).start();
                    if (videos.size() > 1) main.postDelayed(nextClipTimer, CLIP_MS);
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player) {
                    switching = false;
                    main.post(this::nextClip);
                }
                return true;
            });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            switching = false;
            main.postDelayed(this::nextClip, 500);
        }
    }

    /**
     * The whole picture on the screen, never cropped: the surface takes the video's shape, as large as fits (black
     * bars only for a video that is not 16:9), and its buffer the video's own size, so a 4K scene stays 4K on a 4K panel.
     */
    private void fit(int vw, int vh) {
        int w = root.getWidth(), h = root.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.min(w / (float) vw, h / (float) vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) screen.getLayoutParams();
        int fw = Math.round(vw * scale), fh = Math.round(vh * scale);
        if (lp.width != fw || lp.height != fh) {
            lp.width = fw;
            lp.height = fh;
            screen.setLayoutParams(lp);
        }
        screen.getHolder().setFixedSize(vw, vh);
    }

    private void releasePlayer() {
        if (player == null) return;
        MediaPlayer mp = player;
        player = null;
        try {
            mp.stop();
        } catch (IllegalStateException ignored) {
            // not started yet
        }
        mp.release();
    }

    /** A few times a second the glass gets a fresh copy of the scene behind it (not while dipped to black). */
    private final Runnable copyBackdrop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (videos.isEmpty()) {
                backdrop.capture(root, plainBackground);
                return;
            }
            if (player != null && dip.getAlpha() < 0.05f) backdrop.capture(screen, main);
            main.postDelayed(this, BACKDROP_MS);
        }
    };

    // ---- lifecycle and keys -------------------------------------------------------------------------------------

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        main.removeCallbacksAndMessages(null);
        videos = findVideos();
        videoIndex = 0;
        switching = false;
        if (videos.isEmpty()) dip.setAlpha(0f);
        nextClip();
        island.animate().alpha(1f).setStartDelay(600).setDuration(1200).start();
        main.postDelayed(minute, MINUTE_MS);
        main.postDelayed(copyBackdrop, 400);
        main.postDelayed(showCard, CARD_FIRST_MS);
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        work.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok && cardShown && cardItem != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Intent intent = ContinueRow.intent(this, cardItem);
                if (intent != null) {
                    try {
                        startActivity(intent);
                        LastOpened.record(this, cardItem.pkg);
                    } catch (ActivityNotFoundException | SecurityException ignored) {
                        // just wake up
                    }
                }
                finish();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    // ---- small helpers ------------------------------------------------------------------------------------------

    private void style(TextView t, float sp, int color, int fontRes) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(this, fontRes));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
