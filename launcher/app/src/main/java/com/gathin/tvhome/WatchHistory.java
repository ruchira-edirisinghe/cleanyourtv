package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What was left part-watched, for the Continue watching row (continue_watching). Two lists in the launcher's private
 * storage: the one {@link WatchListener} writes from the playback other apps report, and Stremio's own list as
 * {@link StremioSync} last fetched it. Both are small JSON files; every read and write goes through one lock, as the
 * listener writes from its own callbacks while Home reads.
 */
final class WatchHistory {
    static final String SOURCE_APP = "app", SOURCE_STREMIO = "stremio";
    /** Items kept per list, newest first. */
    static final int MAX = 20;
    private static final String TAG = "TvHomeWatch";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Runnable onChange;

    private WatchHistory() {
    }

    /** One part-watched show, video or film. */
    static final class Item {
        /** Unique within its list: "package|title" for an app's, the library id for Stremio's. */
        String key;
        String source = SOURCE_APP;
        String pkg;
        String title;
        /** The episode ("S1 E3") or the channel, or empty. */
        String subtitle = "";
        /** A picture: a file in the launcher's storage, or an http(s) link for Stremio; empty for none. */
        String art = "";
        /** A big cover picture from the web for the top banner (CoverArt), or empty. */
        String cover = "";
        /** An ACTION_VIEW link that opens it in its app (Stremio), or empty to open the app itself. */
        String link = "";
        long position, duration;
        /** A new episode of a series in the Stremio library, not yet watched: the card says so. */
        boolean isNew;
        /** When it was last played (a new episode: when it came out), in milliseconds since the epoch. */
        long updated;

        /** The share watched, 0 when the length is unknown. */
        float progress() {
            return duration > 0 ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("key", key).put("source", source).put("pkg", pkg).put("title", title)
                    .put("subtitle", subtitle).put("art", art).put("cover", cover).put("link", link).put("position", position)
                    .put("duration", duration).put("updated", updated).put("isNew", isNew);
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.key = o.optString("key");
            it.source = o.optString("source", SOURCE_APP);
            it.pkg = o.optString("pkg");
            it.title = o.optString("title");
            it.subtitle = o.optString("subtitle");
            it.art = o.optString("art");
            it.cover = o.optString("cover");
            it.link = o.optString("link");
            it.position = o.optLong("position");
            it.duration = o.optLong("duration");
            it.updated = o.optLong("updated");
            it.isNew = o.optBoolean("isNew");
            return it;
        }
    }

    static File appFile(Context ctx) {
        return new File(ctx.getFilesDir(), "continue_watching.json");
    }

    static File stremioFile(Context ctx) {
        return new File(ctx.getFilesDir(), "continue_stremio.json");
    }

    /** Where the pictures of the apps' items are kept. */
    static File artDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "continue_art");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "cannot make " + dir);
        return dir;
    }

    static List<Item> load(File file) {
        synchronized (LOCK) {
            List<Item> items = new ArrayList<>();
            if (!file.exists()) return items;
            try {
                JSONArray array = new JSONArray(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null) items.add(Item.fromJson(o));
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "cannot read " + file.getName(), e);
            }
            return items;
        }
    }

    static void save(File file, List<Item> items) {
        synchronized (LOCK) {
            AtomicFile af = new AtomicFile(file);
            FileOutputStream out = null;
            try {
                JSONArray array = new JSONArray();
                for (Item it : items) array.put(it.toJson());
                out = af.startWrite();
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
                af.finishWrite(out);
            } catch (IOException | JSONException e) {
                if (out != null) af.failWrite(out);
                Log.w(TAG, "cannot write " + file.getName(), e);
            }
        }
        changed();
    }

    /** Adds or replaces an app's item (by key) at the front, and drops the oldest past MAX with their pictures. */
    static void put(Context ctx, Item item) {
        synchronized (LOCK) {
            File file = appFile(ctx);
            List<Item> items = load(file);
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).key.equals(item.key)) items.remove(i);
            }
            items.add(0, item);
            while (items.size() > MAX) deleteArt(items.remove(items.size() - 1));
            save(file, items);
        }
    }

    /** Takes an app's item out of the row, with its picture; false when there was none. */
    static boolean remove(Context ctx, String key) {
        synchronized (LOCK) {
            File file = appFile(ctx);
            List<Item> items = load(file);
            boolean found = false;
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).key.equals(key)) {
                    deleteArt(items.remove(i));
                    found = true;
                }
            }
            if (found) save(file, items);
            return found;
        }
    }

    /** An app's item by key, or null. */
    static Item find(Context ctx, String key) {
        for (Item it : load(appFile(ctx))) if (it.key.equals(key)) return it;
        return null;
    }

    private static void deleteArt(Item item) {
        if (item.art.startsWith("/")) {
            File f = new File(item.art);
            if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
        }
    }

    /**
     * The row: both lists newest first, Stremio's taking the place of what the Stremio app itself reported (its
     * account list knows the episode and opens it), minus what the owner dismissed, at most max items.
     */
    static List<Item> shown(Context ctx, boolean stremioLinked, Set<String> dismissed, int max) {
        List<Item> all = new ArrayList<>();
        for (Item it : load(appFile(ctx))) {
            if (stremioLinked && it.pkg.equals(StremioSync.PACKAGE)) continue;
            all.add(it);
        }
        if (stremioLinked) all.addAll(load(stremioFile(ctx)));
        List<Item> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Collections.sort(all, (a, b) -> Long.compare(b.updated, a.updated));
        for (Item it : all) {
            if (dismissed.contains(dismissKey(it)) || !keys.add(it.source + '|' + it.key)) continue;
            out.add(it);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** A dismissed Stremio item comes back once it is watched again (its time changes). */
    static String dismissKey(Item it) {
        return it.source + '|' + it.key + '|' + it.updated;
    }

    /** Home's refresh while it is showing; called on the main thread after a change. */
    static void setOnChange(Runnable r) {
        onChange = r;
    }

    static void changed() {
        Runnable r = onChange;
        if (r != null) MAIN.post(r);
    }
}
