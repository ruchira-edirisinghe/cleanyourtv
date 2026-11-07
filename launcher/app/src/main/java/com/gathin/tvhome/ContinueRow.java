package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.HorizontalScrollView;
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
 * The Continue watching row (continue_watching): a sideways row of cards under the dock, laid over the room
 * DockDecoration leaves below the dock's band, so the grid's positions and its navigation stay as they are. Each card
 * shows its picture, the app's icon, how far in it is, its name and the time left; Stremio's new episodes say so.
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
    private static final float CARD_W_DP = 156f, CARD_H_DP = 88f, GAP_DP = 22f;
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
    private final TextView header;
    private final HorizontalScrollView scroller;
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
        view.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);
        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        // Room above and below for the focus zoom, which a scroll view would otherwise cut off.
        strip.setPadding(0, dp(8), dp(40), dp(4));
        scroller.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        view.addView(scroller, lp);
    }

    /** The row's height as laid out, for the room under the dock. */
    int heightPx() {
        // header (about 18dp) + gap + zoom room + card + two text lines + their gaps
        return dp(18 + 4 + 8 + CARD_H_DP + 10 + 20 + 17 + 4 + 14);
    }

    boolean shown() {
        return shown;
    }

    /** Lines the row up with the dock's first tile (px from the row's parent's left edge). */
    void setStartInset(int px) {
        if (header.getPaddingLeft() == px) return;
        header.setPadding(px, 0, 0, 0);
        strip.setPadding(px, strip.getPaddingTop(), strip.getPaddingRight(), strip.getPaddingBottom());
    }

    boolean hasFocus() {
        return view.hasFocus();
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
            rebuild(next, connect);
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
        items.clear();
        items.addAll(next);
        cards.clear();
        strip.removeAllViews();
        for (int i = 0; i < items.size(); i++) addCard(items.get(i), i);
        if (connect) addCard(null, items.size());
        boolean nowShown = !cards.isEmpty();
        view.setVisibility(nowShown ? View.VISIBLE : View.GONE);
        if (nowShown != shown) {
            shown = nowShown;
            host.onContinueShownChanged(shown);
        }
        if (focusedIndex >= 0 && !cards.isEmpty()) {
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
            title.setText(item.title);
            meta.setText(metaLine(item));
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

        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) columnLp.leftMargin = dp(GAP_DP);
        strip.addView(column, columnLp);
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
                    host.onContinueUp(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.onContinueDown(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return at <= 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return at >= cards.size() - 1;
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
        t.setTypeface(UiFont.get(activity, medium ? R.string.font_medium : R.string.font_body));
        return t;
    }

    /** "S1 E3 · 23 min left", "Channel · 1 h 5 min left", or a new episode's "S2 E6 · Name". */
    private String metaLine(WatchHistory.Item item) {
        if (item.isNew) return item.subtitle;
        String left = "";
        if (item.duration > 0) {
            long min = Math.max(1, (item.duration - item.position) / 60_000L);
            left = min >= 60 ? activity.getString(R.string.continue_hours_left, (int) (min / 60), (int) (min % 60))
                    : activity.getString(R.string.continue_min_left, (int) min);
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

    boolean focusNearest(float screenX) {
        if (cards.isEmpty()) return false;
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (View c : cards) {
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
        String src = !item.art.isEmpty() ? item.art : item.cover;
        art.setTag(src);
        if (src.isEmpty()) {
            Drawable banner = appBanner(item.pkg);
            if (banner != null) art.setImageDrawable(banner);
            return;
        }
        pictures.execute(() -> {
            Bitmap b = picture(src, cardW);
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
            Bitmap b = picture(src, width);
            main.post(() -> {
                if (b != null) covers.put(src, b);
                done.accept(b);
            });
        });
    }

    /** A picture by file path or web link, decoded for this width; web pictures are kept in the cache folder. */
    private Bitmap picture(String src, int width) {
        if (src.startsWith("/")) return Net.decodeFile(new File(src), width);
        if (!src.startsWith("http")) return null;
        File dir = new File(activity.getCacheDir(), "continue_pictures");
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
        Intent intent = null;
        if (!item.link.isEmpty()) {
            String link = item.link;
            // A YouTube video starts where it was left.
            if (link.startsWith("https://www.youtube.com/watch") && item.position > 0) {
                link += "&t=" + (item.position / 1000) + "s";
            }
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(item.pkg);
            if (activity.getPackageManager().resolveActivity(intent, 0) == null) intent = null;
        }
        if (intent == null) {
            PackageManager pm = activity.getPackageManager();
            intent = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(item.pkg);
        }
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            LastOpened.record(activity, item.pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
        }
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
