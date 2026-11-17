package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The immersive Home's song card stays up after the music stops: the last song a music app played (its lines in the
 * preferences, its cover as a small PNG), so it is still there after a restart. Hold OK on the card to hide it; opening
 * that app from Home again brings it back.
 */
final class LastSong {
    private static final String PREFS = "last_song";
    private static final String COVER = "last_song.png";
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    /** The cover written last, so a song's position updates do not write it again. */
    private static Bitmap savedArt;

    private LastSong() {
    }

    /** Notes the song that plays now; the cover is written off the main thread when it changed. */
    static void save(Context ctx, NowPlaying.Track t) {
        prefs(ctx).edit()
                .putString("pkg", t.pkg)
                .putString("title", t.title)
                .putString("artist", t.artist)
                .putString("album", t.album)
                .putLong("duration", t.duration)
                .putLong("position", t.positionNow())
                .putBoolean("art", t.art != null)
                .apply();
        if (t.art == null || t.art == savedArt) return;
        savedArt = t.art;
        final Bitmap art = t.art;
        final File file = new File(ctx.getFilesDir(), COVER);
        writer.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                art.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(file)) tmp.delete();
        });
    }

    /** The last song, stopped where it was, or null when none was ever seen. Reads a small file: call off the main thread. */
    static NowPlaying.Track load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String title = p.getString("title", "");
        if (title.isEmpty()) return null;
        NowPlaying.Track t = new NowPlaying.Track();
        t.pkg = p.getString("pkg", "");
        t.title = title;
        t.artist = p.getString("artist", "");
        t.album = p.getString("album", "");
        t.duration = p.getLong("duration", 0L);
        t.position = p.getLong("position", 0L);
        if (p.getBoolean("art", false)) {
            File file = new File(ctx.getFilesDir(), COVER);
            if (file.isFile()) t.art = BitmapFactory.decodeFile(file.getPath());
        }
        return t;
    }

    /** The same song, held still where it is now (the card shows it stopped). */
    static NowPlaying.Track stopped(NowPlaying.Track t) {
        if (t == null) return null;
        NowPlaying.Track s = new NowPlaying.Track();
        s.pkg = t.pkg;
        s.title = t.title;
        s.artist = t.artist;
        s.album = t.album;
        s.art = t.art;
        s.duration = t.duration;
        s.position = t.positionNow();
        return s;
    }

    static boolean hidden(Context ctx) {
        return prefs(ctx).getBoolean("hidden", false);
    }

    static void hide(Context ctx) {
        prefs(ctx).edit().putBoolean("hidden", true).apply();
    }

    /** Home opened this app: if it is the card's music app, the card comes back. */
    static void opened(Context ctx, String pkg) {
        SharedPreferences p = prefs(ctx);
        if (pkg != null && p.getBoolean("hidden", false) && pkg.equals(p.getString("pkg", ""))) {
            p.edit().putBoolean("hidden", false).apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
