package com.gathin.tvhome;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Notes what other apps play, for the Continue watching row (continue_watching). Apps report their playback to
 * Android as a media session (the title, often a picture, the length and the position); a notification listener may
 * read those. When playback pauses, stops or moves on, and once a minute while it plays, the item is written to
 * {@link WatchHistory}; one watched to the end leaves the row. No notification is read.
 * Enable with {@code cmd notification allow_listener com.gathin.tvhome/.WatchListener}.
 */
public class WatchListener extends NotificationListenerService {
    private static final String TAG = "TvHomeWatch";
    /** Less than this in, it was a trailer or a look: not worth a place in the row. */
    static final long MIN_WATCHED_MS = 60_000L;
    /** This far through counts as finished: the credits are rolling. */
    static final float FINISHED_SHARE = 0.95f;
    private static final long TICK_MS = 60_000L;
    private static final int ART_WIDTH = 960;

    private MediaSessionManager sessions;
    private Handler handler;
    private ExecutorService io;
    private final Set<String> skip = new HashSet<>();
    private final Map<MediaSession.Token, Watched> watched = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::attach;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean playing = false;
            for (Watched w : watched.values()) {
                if (w.state == PlaybackState.STATE_PLAYING) {
                    w.commit();
                    playing = true;
                }
            }
            ticking = playing;
            if (playing) handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        Config cfg = Config.load(this);
        if (!cfg.continueWatching || sessions != null) return;
        handler = new Handler(Looper.getMainLooper());
        io = Executors.newSingleThreadExecutor();
        skip.clear();
        skip.addAll(cfg.continueSkip);
        skip.add(getPackageName());
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, WatchListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            attach(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "no access to media sessions", e);
            sessions = null;
        }
    }

    @Override
    public void onListenerDisconnected() {
        stop();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (sessions == null) return;
        try {
            sessions.removeOnActiveSessionsChangedListener(changed);
        } catch (RuntimeException ignored) {
        }
        for (Watched w : watched.values()) {
            w.commit();
            w.controller.unregisterCallback(w);
        }
        watched.clear();
        handler.removeCallbacks(tick);
        ticking = false;
        sessions = null;
        io.shutdown();
    }

    /** Follows the sessions now active; one that went away is noted where it stopped. */
    private void attach(List<MediaController> controllers) {
        Set<MediaSession.Token> live = new HashSet<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c == null || skip.contains(c.getPackageName())) continue;
                MediaSession.Token token = c.getSessionToken();
                live.add(token);
                if (watched.containsKey(token)) continue;
                Watched w = new Watched(c);
                watched.put(token, w);
                c.registerCallback(w, handler);
                w.onMetadataChanged(c.getMetadata());
                w.onPlaybackStateChanged(c.getPlaybackState());
            }
        }
        Iterator<Map.Entry<MediaSession.Token, Watched>> it = watched.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MediaSession.Token, Watched> e = it.next();
            if (live.contains(e.getKey())) continue;
            e.getValue().commit();
            e.getValue().controller.unregisterCallback(e.getValue());
            it.remove();
        }
    }

    private void startTick() {
        if (ticking) return;
        ticking = true;
        handler.postDelayed(tick, TICK_MS);
    }

    /** One app's session: its item now and where playback stood when it last reported. */
    private final class Watched extends MediaController.Callback {
        final MediaController controller;
        final String pkg;
        String title, subtitle = "";
        long duration;
        Bitmap art;
        Uri artUri;
        long position, positionAt;
        float speed;
        int state = PlaybackState.STATE_NONE;

        Watched(MediaController controller) {
            this.controller = controller;
            this.pkg = controller.getPackageName();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            String next = m == null ? null : text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            // The next episode or video began: note the one before where it ended.
            if (title != null && !title.equals(next)) commit();
            title = next;
            if (m == null) return;
            String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            subtitle = sub == null ? "" : sub;
            duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String uri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            artUri = uri == null ? null : Uri.parse(uri);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            if (s == null) return;
            boolean wasPlaying = state == PlaybackState.STATE_PLAYING;
            state = s.getState();
            position = s.getPosition();
            positionAt = s.getLastPositionUpdateTime();
            speed = s.getPlaybackSpeed();
            if (state == PlaybackState.STATE_PLAYING) startTick();
            else if (wasPlaying) commit();   // paused or stopped: note where
        }

        @Override
        public void onSessionDestroyed() {
            commit();
            controller.unregisterCallback(this);
            watched.remove(controller.getSessionToken());
        }

        /** Where playback is now: the reported position, moved on by the time played since it was reported. */
        long positionNow() {
            if (state == PlaybackState.STATE_PLAYING && positionAt > 0 && speed > 0) {
                return position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
            }
            return position;
        }

        /** Writes the item to the row, or takes it out when it was watched to the end. Live streams have no length. */
        void commit() {
            if (title == null || title.isEmpty() || duration <= 0) return;
            long pos = Math.min(positionNow(), duration);
            String key = pkg + '|' + title;
            if (pos >= duration * FINISHED_SHARE) {
                io.execute(() -> WatchHistory.remove(WatchListener.this, key));
                return;
            }
            if (pos < MIN_WATCHED_MS) return;
            WatchHistory.Item item = new WatchHistory.Item();
            item.key = key;
            item.source = WatchHistory.SOURCE_APP;
            item.pkg = pkg;
            item.title = title;
            item.subtitle = subtitle;
            item.position = pos;
            item.duration = duration;
            item.updated = System.currentTimeMillis();
            final Bitmap picture = art;
            final Uri link = artUri;
            io.execute(() -> {
                item.art = saveArt(key, picture, link);
                WatchHistory.put(WatchListener.this, item);
            });
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    /** The item's picture as a file of ours, made once per item: from the session's bitmap or its link. */
    private String saveArt(String key, Bitmap bitmap, Uri uri) {
        File file = new File(WatchHistory.artDir(this), Integer.toHexString(key.hashCode()) + ".jpg");
        if (file.exists()) return file.getAbsolutePath();
        Bitmap b = bitmap;
        if (b == null && uri != null) b = readArt(uri);
        if (b == null) return "";
        try (FileOutputStream out = new FileOutputStream(file)) {
            Bitmap scaled = b.getWidth() > ART_WIDTH
                    ? Bitmap.createScaledBitmap(b, ART_WIDTH, Math.round(b.getHeight() * ART_WIDTH / (float) b.getWidth()), true)
                    : b;
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return file.getAbsolutePath();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot save picture for " + key, e);
            return "";
        }
    }

    private Bitmap readArt(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) return Net.bitmap(uri.toString(), ART_WIDTH);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
