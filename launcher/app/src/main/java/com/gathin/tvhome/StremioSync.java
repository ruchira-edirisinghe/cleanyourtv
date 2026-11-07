package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stremio's own Continue watching list, from the owner's Stremio account, for the Continue watching row. The account
 * is linked the way Stremio's TV app links one: the TV shows a short code, the owner approves it at
 * link.stremio.com on a phone, and Stremio hands back a key. Only that key is kept, in the launcher's private
 * storage; no password ever passes through the launcher. Blocking calls: run them off the main thread.
 */
final class StremioSync {
    static final String PACKAGE = "com.stremio.one";
    private static final String PREFS = "continue_watching";
    private static final String KEY_AUTH = "stremio_auth";
    private static final String KEY_FETCHED = "stremio_fetched";
    private static final String KEY_CONNECT_HIDDEN = "stremio_connect_hidden";
    private static final String KEY_DISMISSED = "dismissed";
    private static final String API = "https://api.strem.io/api/";
    private static final String LINK = "https://link.stremio.com/api/v2/";
    /** Home asks again at most this often when it comes back. */
    static final long REFRESH_MS = 2 * 60_000L;

    private StremioSync() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean linked(Context ctx) {
        return !prefs(ctx).getString(KEY_AUTH, "").isEmpty();
    }

    static boolean due(Context ctx) {
        return linked(ctx) && System.currentTimeMillis() - prefs(ctx).getLong(KEY_FETCHED, 0L) > REFRESH_MS;
    }

    static void unlink(Context ctx) {
        prefs(ctx).edit().remove(KEY_AUTH).remove(KEY_FETCHED).apply();
        WatchHistory.save(WatchHistory.stremioFile(ctx), new ArrayList<>());
    }

    /** The "Connect Stremio" card: hidden once the owner says so, or once linked. */
    static boolean connectHidden(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CONNECT_HIDDEN, false);
    }

    static void hideConnect(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_CONNECT_HIDDEN, true).apply();
    }

    /** Brings the "Connect Stremio" card back after it was hidden. */
    static void showConnect(Context ctx) {
        prefs(ctx).edit().remove(KEY_CONNECT_HIDDEN).apply();
    }

    static Set<String> dismissed(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_DISMISSED, new HashSet<>()));
    }

    /** Takes an item out of the row until it is watched again. */
    static void dismiss(Context ctx, WatchHistory.Item item) {
        Set<String> set = dismissed(ctx);
        set.add(WatchHistory.dismissKey(item));
        // Only the newest few hundred are kept: older ones have long since been watched again or forgotten.
        if (set.size() > 300) set.remove(set.iterator().next());
        prefs(ctx).edit().putStringSet(KEY_DISMISSED, set).apply();
    }

    /** Fetches the account's list into the Stremio file; false when the key no longer works or it could not ask. */
    static boolean refresh(Context ctx) {
        String auth = prefs(ctx).getString(KEY_AUTH, "");
        if (auth.isEmpty()) return false;
        String body;
        try {
            body = new JSONObject().put("authKey", auth).put("collection", "libraryItem")
                    .put("ids", new JSONArray()).put("all", true).toString();
        } catch (JSONException e) {
            return false;
        }
        String answer = Net.postJson(API + "datastoreGet", body);
        if (answer == null) return false;
        try {
            JSONObject o = new JSONObject(answer);
            JSONObject error = o.optJSONObject("error");
            if (error != null) {
                // Code 1, "Session does not exist": the key was revoked (logged out everywhere). Unlink, so the
                // Connect card comes back.
                if (error.optInt("code") == 1) unlink(ctx);
                return false;
            }
            JSONArray library = o.optJSONArray("result");
            List<WatchHistory.Item> items = parse(library);
            items.addAll(newEpisodes(library, System.currentTimeMillis()));
            items.sort((a, b) -> Long.compare(b.updated, a.updated));
            WatchHistory.save(WatchHistory.stremioFile(ctx), items);
            prefs(ctx).edit().putLong(KEY_FETCHED, System.currentTimeMillis()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Library items Stremio itself shows under Continue watching: not "other", not removed (or only temporarily in
     * the library), and stopped part of the way in. Newest first.
     */
    static List<WatchHistory.Item> parse(JSONArray result) {
        List<WatchHistory.Item> items = new ArrayList<>();
        if (result == null) return items;
        for (int i = 0; i < result.length(); i++) {
            JSONObject li = result.optJSONObject(i);
            if (li == null) continue;
            String type = li.optString("type");
            JSONObject state = li.optJSONObject("state");
            if (state == null || "other".equals(type)) continue;
            if (li.optBoolean("removed") && !li.optBoolean("temp")) continue;
            long offset = state.optLong("timeOffset");
            if (offset <= 0) continue;
            String id = li.optString("_id");
            String videoId = state.optString("video_id", "");
            if ("null".equals(videoId)) videoId = "";
            WatchHistory.Item it = new WatchHistory.Item();
            it.key = id;
            it.source = WatchHistory.SOURCE_STREMIO;
            it.pkg = PACKAGE;
            it.title = li.optString("name");
            it.subtitle = episode(videoId);
            it.position = offset;
            it.duration = state.optLong("duration");
            it.updated = time(state.optString("lastWatched"), li.optString("_mtime"));
            it.art = id.startsWith("tt") ? "https://images.metahub.space/background/medium/" + id + "/img" : li.optString("poster");
            it.cover = id.startsWith("tt") ? "https://images.metahub.space/background/large/" + id + "/img" : it.art;
            it.poster = id.startsWith("tt") ? "https://images.metahub.space/poster/medium/" + id + "/img" : li.optString("poster");
            it.link = "stremio:///detail/" + Uri.encode(type) + "/" + Uri.encode(id)
                    + (videoId.isEmpty() ? "" : "/" + Uri.encode(videoId));
            items.add(it);
        }
        items.sort((a, b) -> Long.compare(b.updated, a.updated));
        while (items.size() > WatchHistory.MAX) items.remove(items.size() - 1);
        return items;
    }

    /** A new episode shows for this long after it came out, unless watched or dismissed before. */
    static final long NEW_WINDOW_MS = 30L * 24 * 60 * 60_000L;
    /** Series asked about per request: the ids go in the address. */
    private static final int BATCH = 20;

    /**
     * The newest episode of each series in the library that came out within NEW_WINDOW_MS, after the series was last
     * watched (or added), and later than the episode last watched: Stremio's own "new episode" notifications, with
     * the release dates from Cinemeta's last-videos list. Series whose notifications are off are left out.
     */
    static List<WatchHistory.Item> newEpisodes(JSONArray library, long now) {
        List<WatchHistory.Item> out = new ArrayList<>();
        if (library == null) return out;
        java.util.Map<String, JSONObject> series = new java.util.LinkedHashMap<>();
        for (int i = 0; i < library.length(); i++) {
            JSONObject li = library.optJSONObject(i);
            if (li == null || !"series".equals(li.optString("type")) || li.optBoolean("removed")) continue;
            JSONObject state = li.optJSONObject("state");
            if (state != null && state.optBoolean("noNotif")) continue;
            String id = li.optString("_id");
            if (id.startsWith("tt")) series.put(id, li);
        }
        List<String> ids = new ArrayList<>(series.keySet());
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            String json = Net.get("https://v3-cinemeta.strem.io/catalog/series/last-videos/lastVideosIds="
                    + String.join(",", batch) + ".json");
            if (json == null) continue;
            try {
                JSONArray metas = new JSONObject(json).optJSONArray("metasDetailed");
                if (metas == null) continue;
                for (int i = 0; i < metas.length(); i++) {
                    JSONObject meta = metas.optJSONObject(i);
                    JSONObject li = meta == null ? null : series.get(meta.optString("id"));
                    if (li == null) continue;
                    WatchHistory.Item it = newEpisode(li, meta, now);
                    if (it != null) out.add(it);
                }
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    static WatchHistory.Item newEpisode(JSONObject li, JSONObject meta, long now) {
        JSONArray videos = meta.optJSONArray("videos");
        if (videos == null) return null;
        JSONObject state = li.optJSONObject("state");
        long seen = state == null ? 0L : time(state.optString("lastWatched"));
        if (seen == 0L) seen = time(li.optString("_ctime"));
        int[] watched = state == null ? null : seasonEpisode(state.optString("video_id", ""));
        JSONObject newest = null;
        long newestAt = 0L;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if (v == null || v.optInt("season") <= 0) continue;   // specials are no new episode
            long at = time(v.optString("released"));
            if (at <= 0 || at > now || now - at > NEW_WINDOW_MS || at <= seen) continue;
            if (watched != null && compare(v.optInt("season"), v.optInt("episode"), watched[0], watched[1]) <= 0) continue;
            if (at > newestAt) {
                newest = v;
                newestAt = at;
            }
        }
        if (newest == null) return null;
        String id = li.optString("_id");
        String videoId = newest.optString("id");
        WatchHistory.Item it = new WatchHistory.Item();
        it.key = id + "#new";
        it.source = WatchHistory.SOURCE_STREMIO;
        it.pkg = PACKAGE;
        it.title = li.optString("name");
        String name = newest.optString("name", newest.optString("title"));
        it.subtitle = "S" + newest.optInt("season") + " E" + newest.optInt("episode")
                + (name.isEmpty() || "null".equals(name) ? "" : " · " + name);
        it.isNew = true;
        it.updated = newestAt;
        String thumb = newest.optString("thumbnail");
        it.art = thumb.startsWith("http") ? thumb : "https://images.metahub.space/background/medium/" + id + "/img";
        it.cover = "https://images.metahub.space/background/large/" + id + "/img";
        it.poster = id.startsWith("tt") ? "https://images.metahub.space/poster/medium/" + id + "/img" : li.optString("poster");
        it.link = "stremio:///detail/series/" + Uri.encode(id) + "/" + Uri.encode(videoId);
        return it;
    }

    /** {season, episode} of "tt0903747:2:5", or null. */
    static int[] seasonEpisode(String videoId) {
        String[] parts = videoId.split(":");
        if (parts.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[parts.length - 2]), Integer.parseInt(parts[parts.length - 1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compare(int s1, int e1, int s2, int e2) {
        return s1 != s2 ? Integer.compare(s1, s2) : Integer.compare(e1, e2);
    }

    /** "tt0903747:2:5" is season 2, episode 5; a film's id has no episode. */
    static String episode(String videoId) {
        String[] parts = videoId.split(":");
        if (parts.length < 3) return "";
        try {
            return "S" + Integer.parseInt(parts[parts.length - 2]) + " E" + Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static long time(String... isoTimes) {
        for (String t : isoTimes) {
            if (t == null || t.isEmpty() || "null".equals(t)) continue;
            try {
                return Instant.parse(t).toEpochMilli();
            } catch (RuntimeException ignored) {
            }
        }
        return 0L;
    }

    // ---- linking the account -------------------------------------------------------------------------------------

    /** A new link code: {code, link, qrcode} or null. */
    static String[] newCode() {
        String answer = Net.get(LINK + "create?type=Create");
        if (answer == null) return null;
        try {
            JSONObject r = new JSONObject(answer).getJSONObject("result");
            return new String[]{r.getString("code"), r.optString("link"), r.optString("qrcode")};
        } catch (JSONException e) {
            return null;
        }
    }

    /** Once the owner approved the code: keeps the key and returns true. False while still waiting. */
    static boolean poll(Context ctx, String code) {
        String answer = Net.get(LINK + "read?type=Read&code=" + Uri.encode(code));
        if (answer == null) return false;
        try {
            JSONObject o = new JSONObject(answer);
            JSONObject r = o.optJSONObject("result");
            String key = r != null ? r.optString("authKey") : o.optString("authKey");
            if (key == null || key.isEmpty()) return false;
            prefs(ctx).edit().putString(KEY_AUTH, key).remove(KEY_FETCHED).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
}
