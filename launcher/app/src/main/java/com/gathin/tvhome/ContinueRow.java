package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Continue watching row (continue_watching): cards under the dock, four to a line and as many lines as it takes
 * (Home scrolls down to them), laid over the room DockDecoration leaves below the dock's band, so the grid's positions and its navigation stay as they are. Each card
 * is a wide banner: the title's backdrop with its logo over it (a video's own picture for YouTube and the like), the
 * app's icon and how far in it is, with its name and the time left under it; Stremio's new episodes say so. Beside
 * the row's name, at the right end of its line, chips pick what it shows: All, Stremio, YouTube or Other; a chip
 * filters the row as soon as it is focused, and one with nothing to show says so.
 * OK opens it, holding OK offers to remove it. Pictures load off the main thread and are kept on disk.
 */
final class ContinueRow {

    interface Host {
        /** A card took focus: its cover goes in the top banner and the row scrolls into view. Null for the Connect card. */
        void onContinueFocused(View card, WatchHistory.Item item);

        /** Up from the row: to the dock tile nearest this screen x. */
        void onContinueUp(float screenX);

        /** Down from the row: into the grid's first row, nearest this screen x. */
        void onContinueDown(float screenX);

        /** The row appeared or went away: the room under the dock changes. */
        void onContinueShownChanged(boolean shown);
    }

    static final int MAX_CARDS = 12;
    /** The filter chips, in order. */
    private static final int ALL = 0, STREMIO = 1, YOUTUBE = 2, OTHER = 3;
    private static final int[] FILTER_LABELS = {R.string.continue_filter_all, R.string.continue_filter_stremio,
            R.string.continue_filter_youtube, R.string.continue_filter_other};
    /** Banner-shaped cards, 16:9, four to a line as wide as the dock's tiles reach, with this much between lines. */
    private static final float CARD_W_DP = 186f, CARD_H_DP = 105f, GAP_DP = 20f, LINE_GAP_DP = 18f;
    private static final int PER_LINE = 4;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int STREMIO_PURPLE = 0xFF7B5BF5;

    private final Activity activity;
    private final Host host;
    private final float density;
    private final int cardW, cardH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private final ExecutorService sync = Executors.newSingleThreadExecutor();
    /** The last few covers decoded for the banner, by picture link. */
    private final Map<String, Bitmap> covers = new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 3;
        }
    };

    final LinearLayout view;
    private final LinearLayout headerRow;
    private final TextView header;
    private final TextView[] chips = new TextView[FILTER_LABELS.length];
    /** Everything the row has, the Connect card, and the chip that picks what of it shows. */
    private final List<WatchHistory.Item> all = new ArrayList<>();
    private boolean connect;
    private int filter = ALL;
    /** setDisplayFonts: the cards use the display faces. */
    private boolean display;
    /** The lines of cards, one under another. */
    private final LinearLayout strip;
    private final List<WatchHistory.Item> items = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private String signature = "";
    private boolean shown;
    private boolean syncing;
    private AlertDialog linkDialog;

    ContinueRow(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.cardW = dp(CARD_W_DP);
        this.cardH = dp(CARD_H_DP);

        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.setVisibility(View.GONE);

        header = new TextView(activity);
        header.setText(activity.getString(R.string.continue_watching).toUpperCase(java.util.Locale.getDefault()));
        header.setTextColor(0xB3FFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        header.setLetterSpacing(0.14f);
        header.setTypeface(UiFont.get(activity, R.string.font_medium));
        headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setClipChildren(false);
        headerRow.setClipToPadding(false);
        headerRow.setMinimumHeight(dp(30));
        headerRow.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The chips stand at the line's right end, level with the dock's last tile.
        headerRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        for (int i = 0; i < chips.length; i++) {
            chips[i] = makeChip(i);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            clp.leftMargin = dp(i == 0 ? 24 : 8);
            headerRow.addView(chips[i], clp);
        }
        view.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        // Room above and below for the focus zoom, which a scroll view would otherwise cut off.
        strip.setPadding(0, dp(8), dp(40), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        view.addView(strip, lp);
    }

    /** liquid_glass: Fraunces for the titles and Space Grotesk for the header and the lines under them. */
    void setDisplayFonts() {
        display = true;
        header.setTypeface(UiFont.get(activity, R.string.font_label_bold));
        header.setLetterSpacing(0.2f);
        for (TextView chip : chips) chip.setTypeface(UiFont.get(activity, R.string.font_label_bold));
        signature = "";   // the cards are made again with the new faces at the next refresh
    }

    /** The row's height as laid out, for the room under the dock. */
    int heightPx() {
        int lines = Math.max(1, (cards.size() + PER_LINE - 1) / PER_LINE);
        // header with its chips (30dp) + gap + zoom room, then each line: card + two text lines + their gaps
        float line = CARD_H_DP + 10 + 20 + 17;
        return dp(30 + 4 + 8 + lines * line + (lines - 1) * LINE_GAP_DP + 4 + 24);
    }

    boolean shown() {
        return shown;
    }

    /** Lines the row up with the dock's first tile (px from the row's parent's left edge), and the chips with its last. */
    void setStartInset(int px) {
        if (headerRow.getPaddingLeft() == px) return;
        headerRow.setPadding(px, 0, px, 0);   // the dock stands in the middle: as far in from the right
        strip.setPadding(px, strip.getPaddingTop(), strip.getPaddingRight(), strip.getPaddingBottom());
    }

    boolean hasFocus() {
        return view.hasFocus();
    }

    private void applyShown() {
        boolean nowShown = !all.isEmpty() || connect;
        view.setVisibility(nowShown ? View.VISIBLE : View.GONE);
        if (nowShown != shown) {
            shown = nowShown;
            host.onContinueShownChanged(shown);
        }
    }

    // ---- data ---------------------------------------------------------------------------------------------------

    /** Reads both lists again and rebuilds the cards if anything changed; asks Stremio again when it is time. */
    void refresh() {
        boolean linked = StremioSync.linked(activity);
        List<WatchHistory.Item> next = WatchHistory.shown(activity, linked, StremioSync.dismissed(activity), MAX_CARDS);
        boolean connect = !linked && !StremioSync.connectHidden(activity) && installed(StremioSync.PACKAGE);
        StringBuilder sig = new StringBuilder(connect ? "connect;" : "");
        for (WatchHistory.Item it : next) {
            sig.append(it.source).append('|').append(it.key).append('|').append(it.updated).append('|')
                    .append(it.position).append('|').append(it.art).append(';');
        }
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            all.clear();
            all.addAll(next);
            this.connect = connect;
            rebuild();
        }
        if (linked && !syncing && StremioSync.due(activity)) {
            syncing = true;
            sync.execute(() -> {
                boolean ok = StremioSync.refresh(activity);
                main.post(() -> {
                    syncing = false;
                    if (ok && !activity.isDestroyed()) refresh();
                });
            });
        }
    }

    // ---- filter ---------------------------------------------------------------------------------------------------

    /** Which chip an item belongs to: Stremio's list and the Stremio app, any YouTube app, or the rest. */
    private static int kind(WatchHistory.Item it) {
        if (WatchHistory.SOURCE_STREMIO.equals(it.source) || StremioSync.PACKAGE.equals(it.pkg)) return STREMIO;
        if (it.pkg.contains("youtube")) return YOUTUBE;
        return OTHER;
    }

    /** A chip: clear glass with a thin rim, a soft fill once picked, white with dark text while focused. */
    private TextView makeChip(int index) {
        TextView chip = text(12f, 0xE6FFFFFF, true);
        chip.setText(FILTER_LABELS[index]);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setFocusable(true);
        chip.setClickable(true);
        float r = dp(14);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(r);
        focused.setColor(0xF2FFFFFF);
        GradientDrawable picked = new GradientDrawable();
        picked.setCornerRadius(r);
        picked.setColor(0x40FFFFFF);
        picked.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        GradientDrawable plain = new GradientDrawable();
        plain.setCornerRadius(r);
        plain.setColor(0x0FFFFFFF);
        plain.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_activated}, picked);
        bg.addState(new int[0], plain);
        bg.setEnterFadeDuration(120);
        bg.setExitFadeDuration(160);
        chip.setBackground(bg);
        chip.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF15151A, 0xE6FFFFFF}));
        chip.setActivated(index == ALL);
        chip.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            setFilter(index);
            host.onContinueFocused(v, null);   // the row scrolls into view, the banner clears
        });
        chip.setOnClickListener(v -> setFilter(index));
        chip.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    focusChip(index, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    focusChip(index, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.onContinueUp(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!cards.isEmpty()) focusNearest(centerX(v));
                    return true;
                default:
                    return false;
            }
        });
        return chip;
    }

    /** The next shown chip to the left (-1) or right (1) of this one takes focus; at the end nothing moves. */
    private void focusChip(int from, int step) {
        for (int i = from + step; i >= 0 && i < chips.length; i += step) {
            if (chips[i].getVisibility() == View.VISIBLE) {
                chips[i].requestFocus();
                return;
            }
        }
    }

    private boolean chipsShown() {
        return chips[ALL].getVisibility() == View.VISIBLE;
    }

    private void setFilter(int index) {
        if (filter == index) return;
        filter = index;
        for (int i = 0; i < chips.length; i++) chips[i].setActivated(i == index);
        rebuild();
    }

    /** The chips (whenever the row has anything), and the cards the picked one shows. */
    private void rebuild() {
        boolean showChips = !all.isEmpty();
        for (TextView chip : chips) chip.setVisibility(showChips ? View.VISIBLE : View.GONE);
        if (!showChips && filter != ALL) {
            filter = ALL;
            for (int i = 0; i < chips.length; i++) chips[i].setActivated(i == ALL);
        }
        List<WatchHistory.Item> shownItems = new ArrayList<>();
        for (WatchHistory.Item it : all) if (filter == ALL || kind(it) == filter) shownItems.add(it);
        rebuild(shownItems, connect && (filter == ALL || filter == STREMIO));
    }

    private void rebuild(List<WatchHistory.Item> next, boolean connect) {
        // Keep focus on the same item (or the same place) when the cards are made again under it.
        int focusedIndex = -1;
        String focusedKey = null;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).hasFocus()) {
                focusedIndex = i;
                focusedKey = i < items.size() ? items.get(i).source + items.get(i).key : "connect";
            }
        }
        int linesBefore = (cards.size() + PER_LINE - 1) / PER_LINE;
        items.clear();
        items.addAll(next);
        cards.clear();
        strip.removeAllViews();
        for (int i = 0; i < items.size(); i++) addCard(items.get(i), i);
        if (connect) addCard(null, items.size());
        if (cards.isEmpty() && !all.isEmpty()) {
            // A chip with nothing to show: say so, where its cards would be.
            TextView none = text(14f, 0x99FFFFFF, false);
            none.setText(R.string.continue_filter_none);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(16);
            strip.addView(none, nlp);
        }
        boolean wasShown = shown;
        applyShown();
        // Another number of lines: the room under the dock changes too.
        if (shown && wasShown && (cards.size() + PER_LINE - 1) / PER_LINE != linesBefore) host.onContinueShownChanged(true);
        if (focusedIndex >= 0 && shown) {
            int target = Math.min(focusedIndex, cards.size() - 1);
            for (int i = 0; i < items.size(); i++) {
                if ((items.get(i).source + items.get(i).key).equals(focusedKey)) target = i;
            }
            cards.get(target).requestFocus();
        }
    }

    private boolean installed(String pkg) {
        try {
            activity.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- cards --------------------------------------------------------------------------------------------------

    private void addCard(WatchHistory.Item item, int index) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        FrameLayout card = new FrameLayout(activity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                item == null ? new int[]{STREMIO_PURPLE, 0xFF2A1E66} : new int[]{0xFF26262E, 0xFF15151A});
        card.setBackground(fill);
        final float radius = 12 * density;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text(14.5f, 0xF2FFFFFF, true);
        if (display) {
            title.setTypeface(UiFont.get(activity, R.string.font_title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        }
        TextView meta = text(12f, 0xA6FFFFFF, false);

        if (item == null) {
            TextView mark = text(15f, 0xFFFFFFFF, true);
            mark.setText(R.string.continue_connect_title);
            mark.setGravity(Gravity.CENTER);
            card.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            title.setText("Stremio");
            meta.setText(R.string.continue_connect_caption);
        } else {
            // A shade along the bottom, so the progress bar reads over a bright picture.
            View shade = new View(activity);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB3000000}));
            FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH / 2, Gravity.BOTTOM);
            card.addView(shade, shadeLp);

            ImageView badge = new ImageView(activity);
            Drawable icon = appIcon(item.pkg);
            if (icon != null) {
                badge.setImageDrawable(icon);
                badge.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 5 * density);
                    }
                });
                badge.setClipToOutline(true);
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.START);
                badgeLp.setMargins(dp(8), dp(8), 0, 0);
                card.addView(badge, badgeLp);
            }

            if (item.isNew) {
                TextView chip = text(9.5f, 0xFFFFFFFF, true);
                chip.setText(activity.getString(R.string.continue_new_episode).toUpperCase(java.util.Locale.getDefault()));
                chip.setLetterSpacing(0.08f);
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setColor(STREMIO_PURPLE);
                chipBg.setCornerRadius(dp(4));
                chip.setBackground(chipBg);
                chip.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
                chipLp.setMargins(dp(8), 0, 0, dp(8));
                card.addView(chip, chipLp);
            } else if (item.duration > 0) {
                FrameLayout track = new FrameLayout(activity);
                GradientDrawable trackBg = new GradientDrawable();
                trackBg.setColor(0x4DFFFFFF);
                trackBg.setCornerRadius(dp(2));
                track.setBackground(trackBg);
                View bar = new View(activity);
                GradientDrawable barBg = new GradientDrawable();
                barBg.setColor(0xFFFFFFFF);
                barBg.setCornerRadius(dp(2));
                bar.setBackground(barBg);
                int trackW = cardW - dp(20);
                track.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(4), Math.round(trackW * item.progress())), dp(3)));
                FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(trackW, dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                trackLp.bottomMargin = dp(8);
                card.addView(track, trackLp);
            }
            // The title's logo over its backdrop, at the bottom left, above the bar or the new episode chip.
            String imdb = WatchHistory.imdbOf(item);
            if (!imdb.isEmpty()) {
                ImageView logo = new ImageView(activity);
                logo.setAdjustViewBounds(true);
                logo.setMaxWidth(Math.round(cardW * 0.6f));
                logo.setScaleType(ImageView.ScaleType.FIT_START);
                FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(36), Gravity.BOTTOM | Gravity.START);
                logoLp.setMargins(dp(10), 0, 0, dp(item.isNew ? 32 : 18));
                card.addView(logo, logoLp);
                loadLogo("https://images.metahub.space/logo/medium/" + imdb + "/img", logo);
            }
            title.setText(item.title);
            meta.setText(metaLine(activity, item));
            loadCardArt(item, art, card);
        }

        column.addView(card, new LinearLayout.LayoutParams(cardW, cardH));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        column.addView(title, titleLp);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(2);
        column.addView(meta, metaLp);
        title.setAlpha(0.8f);
        meta.setAlpha(0.8f);

        LinearLayout line;
        if (index % PER_LINE == 0) {
            line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            line.setClipToPadding(false);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) lineLp.topMargin = dp(LINE_GAP_DP);
            strip.addView(line, lineLp);
        } else {
            line = (LinearLayout) strip.getChildAt(strip.getChildCount() - 1);
        }
        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index % PER_LINE > 0) columnLp.leftMargin = dp(GAP_DP);
        line.addView(column, columnLp);
        cards.add(card);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(2.5f), 0xFFFFFFFF);
        ring.setColor(0x00000000);
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                    .translationZ(focused ? 8 * density : 0f).setDuration(focused ? 200 : 150)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            card.setForeground(focused ? ring : null);
            title.setAlpha(focused ? 1f : 0.8f);
            meta.setAlpha(focused ? 1f : 0.8f);
            if (focused) host.onContinueFocused(v, item);
        });
        card.setOnClickListener(v -> {
            if (item == null) showLinkDialog();
            else open(item);
        });
        card.setOnLongClickListener(v -> {
            showMenu(item);
            return true;
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int at = cards.indexOf(v);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (at >= PER_LINE) cards.get(at - PER_LINE).requestFocus();
                    else if (chipsShown()) chips[filter].requestFocus();
                    else host.onContinueUp(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // The card under this one, or the last card when the line below is shorter; past the last line, on.
                    if (at / PER_LINE < (cards.size() - 1) / PER_LINE) {
                        cards.get(Math.min(at + PER_LINE, cards.size() - 1)).requestFocus();
                    } else {
                        host.onContinueDown(centerX(v));
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (at % PER_LINE > 0) cards.get(at - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (at % PER_LINE < PER_LINE - 1 && at + 1 < cards.size()) cards.get(at + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (event.getRepeatCount() == 0) showMenu(item);
                    return true;
                default:
                    return false;
            }
        });
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTypeface(UiFont.get(activity, medium ? (display ? R.string.font_label_bold : R.string.font_medium)
                : (display ? R.string.font_label : R.string.font_body)));
        return t;
    }

    /** "S1 E3 · 23 min left", "Channel · 1 h 5 min left", or a new episode's "S2 E6 · Name". */
    static String metaLine(Context ctx, WatchHistory.Item item) {
        if (item.isNew) return item.subtitle;
        String left = "";
        if (item.duration > 0) {
            long min = Math.max(1, (item.duration - item.position) / 60_000L);
            left = min >= 60 ? ctx.getString(R.string.continue_hours_left, (int) (min / 60), (int) (min % 60))
                    : ctx.getString(R.string.continue_min_left, (int) min);
        }
        if (item.subtitle.isEmpty()) return left;
        return left.isEmpty() ? item.subtitle : item.subtitle + " · " + left;
    }

    private Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Drawable appBanner(String pkg) {
        try {
            return activity.getPackageManager().getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private float centerX(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return at[0] + v.getWidth() / 2f;
    }

    /** Focuses the card of the first line nearest this screen x. */
    boolean focusNearest(float screenX) {
        if (cards.isEmpty()) return false;
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (View c : cards.subList(0, Math.min(PER_LINE, cards.size()))) {
            float d = Math.abs(centerX(c) - screenX);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best != null) best.requestFocus();
        return best != null;
    }

    // ---- pictures -----------------------------------------------------------------------------------------------

    private void loadCardArt(WatchHistory.Item item, ImageView art, View card) {
        // A wide picture: the title's backdrop, else the app's own picture of it (a YouTube video's), else its cover.
        String imdb = WatchHistory.imdbOf(item);
        String src = !imdb.isEmpty() ? "https://images.metahub.space/background/medium/" + imdb + "/img"
                : !item.art.isEmpty() ? item.art : !item.cover.isEmpty() ? item.cover : WatchHistory.posterOf(item);
        art.setTag(src);
        if (src.isEmpty()) {
            Drawable banner = appBanner(item.pkg);
            if (banner != null) art.setImageDrawable(banner);
            return;
        }
        pictures.execute(() -> {
            Bitmap b = picture(activity, src, cardW);
            main.post(() -> {
                if (!src.equals(art.getTag())) return;
                if (b != null) {
                    art.setAlpha(0f);
                    art.setImageBitmap(b);
                    art.animate().alpha(1f).setDuration(220).start();
                } else {
                    Drawable banner = appBanner(item.pkg);
                    if (banner != null) art.setImageDrawable(banner);
                }
            });
        });
    }

    /** A title's logo for its card, off the main thread; a title with none keeps its name under the card only. */
    private void loadLogo(String src, ImageView logo) {
        logo.setTag(src);
        pictures.execute(() -> {
            Bitmap b = picture(activity, src, Math.round(cardW * 0.6f));
            main.post(() -> {
                if (b == null || !src.equals(logo.getTag())) return;
                logo.setAlpha(0f);
                logo.setImageBitmap(b);
                logo.animate().alpha(1f).setDuration(220).start();
            });
        });
    }

    /** The cover for the top banner, at the screen's width: from memory, the disk or the web. */
    void cover(WatchHistory.Item item, int width, Consumer<Bitmap> done) {
        String src = !item.cover.isEmpty() ? item.cover : item.art;
        if (src.isEmpty()) {
            done.accept(null);
            return;
        }
        Bitmap cached = covers.get(src);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        pictures.execute(() -> {
            Bitmap b = picture(activity, src, width);
            main.post(() -> {
                if (b != null) covers.put(src, b);
                done.accept(b);
            });
        });
    }

    /** A picture by file path or web link, decoded for this width; web pictures are kept in the cache folder. */
    static Bitmap picture(Context ctx, String src, int width) {
        if (src.startsWith("/")) return Net.decodeFile(new File(src), width);
        if (!src.startsWith("http")) return null;
        File dir = new File(ctx.getCacheDir(), "continue_pictures");
        if (!dir.isDirectory() && !dir.mkdirs()) return Net.bitmap(src, width);
        File file = new File(dir, Integer.toHexString(src.hashCode()) + "_" + src.length() + ".img");
        if (!Net.downloadTo(src, file) && src.contains("/maxresdefault.jpg")) {
            // Older or smaller YouTube videos have no full-size thumbnail: the next size down always exists.
            String hq = src.replace("/maxresdefault.jpg", "/hqdefault.jpg");
            if (!Net.downloadTo(hq, file)) return null;
        }
        return Net.decodeFile(file, width);
    }

    /** Home is in the background and memory is short: let go of the decoded pictures. */
    void release() {
        covers.clear();
        signature = "";
        for (View c : cards) {
            ImageView art = (ImageView) ((ViewGroup) c).getChildAt(0);
            art.setImageDrawable(null);
        }
    }

    // ---- actions ------------------------------------------------------------------------------------------------

    private void open(WatchHistory.Item item) {
        Intent intent = intent(activity, item);
        if (intent == null) return;
        try {
            activity.startActivity(intent);
            LastOpened.record(activity, item.pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
        }
    }

    /** What opens the item: its link in its app (YouTube at the time it was left), or else the app; null if neither. */
    static Intent intent(Context ctx, WatchHistory.Item item) {
        Intent intent = null;
        if (!item.link.isEmpty()) {
            String link = item.link;
            // A YouTube video starts where it was left.
            if (link.startsWith("https://www.youtube.com/watch") && item.position > 0) {
                link += "&t=" + (item.position / 1000) + "s";
            }
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(item.pkg);
            if (ctx.getPackageManager().resolveActivity(intent, 0) == null) intent = null;
        }
        if (intent == null) {
            PackageManager pm = ctx.getPackageManager();
            intent = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(item.pkg);
        }
        if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void showMenu(WatchHistory.Item item) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (item == null) {
            labels.add(activity.getString(R.string.continue_connect_title));
            actions.add(this::showLinkDialog);
            labels.add(activity.getString(R.string.continue_hide_connect));
            actions.add(() -> {
                StremioSync.hideConnect(activity);
                refresh();
            });
        } else {
            CharSequence app = item.pkg;
            try {
                PackageManager pm = activity.getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(item.pkg, 0));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            labels.add(activity.getString(R.string.continue_remove));
            actions.add(() -> {
                if (WatchHistory.SOURCE_STREMIO.equals(item.source)) {
                    StremioSync.dismiss(activity, item);
                    refresh();
                } else {
                    sync.execute(() -> WatchHistory.remove(activity, item.key));   // the change refreshes the row
                }
            });
            labels.add(activity.getString(R.string.continue_open_app, app));
            actions.add(() -> {
                WatchHistory.Item plain = new WatchHistory.Item();
                plain.pkg = item.pkg;
                plain.title = item.title;
                open(plain);
            });
            if (WatchHistory.SOURCE_STREMIO.equals(item.source)) {
                labels.add(activity.getString(R.string.continue_unlink));
                actions.add(() -> sync.execute(() -> {
                    StremioSync.unlink(activity);
                    main.post(this::refresh);
                }));
            }
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item == null ? "Stremio" : item.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    // ---- linking Stremio ----------------------------------------------------------------------------------------

    /** Hold OK on the Stremio tile: the Connect card comes back (if it was hidden) and the link code shows. */
    void connectStremio() {
        StremioSync.showConnect(activity);
        refresh();
        showLinkDialog();
    }

    /** Shows a link code and its QR code, and waits (up to the code's ten minutes) for the owner to approve it. */
    private void showLinkDialog() {
        if (linkDialog != null && linkDialog.isShowing()) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(8), dp(28), dp(8));
        TextView steps = text(14f, 0xDDFFFFFF, false);
        steps.setSingleLine(false);
        steps.setGravity(Gravity.CENTER);
        steps.setLineSpacing(0, 1.2f);
        steps.setText(R.string.stremio_link_steps);
        box.addView(steps, new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView code = text(40f, 0xFFFFFFFF, true);
        code.setLetterSpacing(0.3f);
        code.setText("…");
        code.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(14);
        box.addView(code, codeLp);
        ImageView qr = new ImageView(activity);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(150), dp(150));
        qrLp.topMargin = dp(12);
        box.addView(qr, qrLp);
        TextView status = text(13f, 0xA6FFFFFF, false);
        status.setText(R.string.stremio_link_waiting);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(12);
        box.addView(status, statusLp);

        final boolean[] open = {true};
        linkDialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.stremio_link_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> open[0] = false)
                .show();

        Thread waiter = new Thread(() -> {
            String[] made = StremioSync.newCode();
            if (made == null) {
                main.post(() -> status.setText(R.string.stremio_link_failed));
                return;
            }
            Bitmap qrImage = made[2].isEmpty() ? null : Net.bitmap(made[2], dp(150));
            main.post(() -> {
                code.setText(made[0]);
                if (qrImage != null) qr.setImageBitmap(qrImage);
            });
            long until = System.currentTimeMillis() + 10 * 60_000L;
            while (open[0] && System.currentTimeMillis() < until) {
                if (StremioSync.poll(activity, made[0])) {
                    StremioSync.refresh(activity);
                    main.post(() -> {
                        if (linkDialog != null) linkDialog.dismiss();
                        Toast.makeText(activity, R.string.stremio_link_done, Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                    return;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (open[0]) main.post(() -> status.setText(R.string.stremio_link_expired));
        }, "stremio-link");
        waiter.setDaemon(true);
        waiter.start();
    }

    void destroy() {
        if (linkDialog != null) linkDialog.dismiss();
        pictures.shutdownNow();
        sync.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
