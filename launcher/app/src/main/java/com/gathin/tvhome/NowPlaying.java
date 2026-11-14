package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * now_playing: follows the media sessions of the music apps in now_playing_apps (Spotify) and tells its listener what
 * plays: the song, the artist, the cover and where playback stands, or null once nothing plays. A short pause between
 * two songs is not reported, so a card that shows the song does not blink. Reads the sessions with the Continue
 * watching listener's access (cmd notification allow_listener com.gathin.tvhome/.WatchListener); without it, nothing
 * is ever reported. Start it while something shows the song, and stop it when that goes away.
 */
final class NowPlaying {

    interface Listener {
        /** What plays now, or null when nothing does. */
        void onNowPlaying(Track track);
    }

    /** A song as its app reports it. */
    static final class Track {
        String pkg = "", title = "", artist = "", album = "";
        Bitmap art;
        long duration, position, positionAt;
        float speed;

        /** Where playback is now: the reported position, moved on by the time played since. */
        long positionNow() {
            long p = position;
            if (positionAt > 0 && speed > 0) p += (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
            return duration > 0 ? Math.max(0, Math.min(p, duration)) : Math.max(0, p);
        }

        /** The same song, picture and all: only the position moved. */
        boolean sameSong(Track o) {
            return o != null && pkg.equals(o.pkg) && title.equals(o.title) && artist.equals(o.artist)
                    && art == o.art && duration == o.duration;
        }
    }

    /** Silence shorter than this (a skip, a buffer) leaves the song up. */
    private static final long GONE_AFTER_MS = 4_000;
    private static final int ART_PX = 320;

    private final Context context;
    private final Listener listener;
    private final Set<String> apps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Follow> follows = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::attach;
    private ExecutorService io;
    private MediaSessionManager sessions;
    private Track shown;
    /** A cover link already being fetched, and the last one fetched with its picture. */
    private String fetching, fetchedUri;
    private Bitmap fetched;
    private boolean running;

    private final Runnable gone = () -> publish(null);

    NowPlaying(Context context, List<String> apps, Listener listener) {
        this.context = context.getApplicationContext();
        this.apps = new HashSet<>(apps);
        this.listener = listener;
    }

    /** The song shown now, or null. */
    Track current() {
        return shown;
    }

    void start() {
        if (running || apps.isEmpty()) return;
        running = true;
        io = Executors.newSingleThreadExecutor();
        sessions = context.getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(context, WatchListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            attach(sessions.getActiveSessions(me));
        } catch (SecurityException | NullPointerException e) {
            sessions = null;   // the listener is not allowed: nothing to show
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacksAndMessages(null);
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(changed);
            } catch (RuntimeException ignored) {
            }
        }
        for (Follow f : follows) f.controller.unregisterCallback(f);
        follows.clear();
        sessions = null;
        io.shutdownNow();
        fetching = null;
        if (shown != null) {
            shown = null;
            listener.onNowPlaying(null);
        }
    }

    private void attach(List<MediaController> controllers) {
        if (!running) return;
        for (Follow f : follows) f.controller.unregisterCallback(f);
        follows.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c == null || !apps.contains(c.getPackageName())) continue;
                Follow f = new Follow(c);
                follows.add(f);
                c.registerCallback(f, main);
            }
        }
        update();
    }

    /** Finds the session that plays (the first, if several do) and reports its song. */
    private void update() {
        if (!running) return;
        MediaController playing = null;
        for (Follow f : follows) {
            if (plays(f.controller.getPlaybackState())) {
                playing = f.controller;
                break;
            }
        }
        if (playing == null) {
            if (shown != null) {
                main.removeCallbacks(gone);
                main.postDelayed(gone, GONE_AFTER_MS);
            }
            return;
        }
        main.removeCallbacks(gone);
        Track t = read(playing);
        if (t == null) return;
        publish(t);
    }

    private void publish(Track t) {
        main.removeCallbacks(gone);
        Track before = shown;
        shown = t;
        if (t == null && before == null) return;
        listener.onNowPlaying(t);
    }

    private static boolean plays(PlaybackState s) {
        if (s == null) return false;
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    private Track read(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return null;
        Track t = new Track();
        t.pkg = c.getPackageName();
        t.title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (t.title.isEmpty()) return null;
        t.artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        t.album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        t.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        PlaybackState s = c.getPlaybackState();
        if (s != null) {
            t.position = s.getPosition();
            t.positionAt = s.getLastPositionUpdateTime();
            t.speed = s.getState() == PlaybackState.STATE_PLAYING ? s.getPlaybackSpeed() : 0f;
        }
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) {
            t.art = art;
        } else {
            String uri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (!uri.isEmpty()) {
                if (uri.equals(fetchedUri)) t.art = fetched;
                else fetch(uri);
            }
        }
        // The same song keeps the same Bitmap object, so a card can tell a new song from a moved position.
        if (shown != null && t.art != null && shown.art != null && shown.title.equals(t.title)
                && shown.art.getWidth() == t.art.getWidth() && shown.art.getHeight() == t.art.getHeight()) {
            t.art = shown.art;
        }
        return t;
    }

    /** A cover given only as a link: fetched once off the main thread, then the song is reported again with it. */
    private void fetch(String uri) {
        if (uri.equals(fetching)) return;
        fetching = uri;
        io.execute(() -> {
            Bitmap b = load(uri);
            main.post(() -> {
                if (!running || !uri.equals(fetching)) return;
                fetching = null;
                fetchedUri = uri;
                fetched = b;
                update();
            });
        });
    }

    private Bitmap load(String uri) {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) return Net.bitmap(uri, ART_PX);
        try (InputStream in = context.getContentResolver().openInputStream(u)) {
            return in == null ? null : BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    private final class Follow extends MediaController.Callback {
        final MediaController controller;

        Follow(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            controller.unregisterCallback(this);
            follows.remove(this);
            update();
        }
    }
}
