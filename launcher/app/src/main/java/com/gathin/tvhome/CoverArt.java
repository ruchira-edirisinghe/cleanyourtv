package com.gathin.tvhome;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Finds a big cover picture on the web for something an app reported playing, for the top banner when its Continue
 * watching card is focused. A YouTube video is found by a search for its title, which also gives its link (so the
 * card reopens that very video); a show or film from any other app is looked up by name in Cinemeta, Stremio's
 * public catalogue, for its backdrop. Blocking: call it off the main thread.
 */
final class CoverArt {
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/catalog/";

    private CoverArt() {
    }

    /** A found cover: the picture's link and, when it is known, a link that opens the item itself. */
    static final class Found {
        final String cover;
        final String link;

        Found(String cover, String link) {
            this.cover = cover;
            this.link = link == null ? "" : link;
        }
    }

    static Found find(String pkg, String title, String subtitle) {
        if (title == null || title.isEmpty()) return null;
        if (YOUTUBE.equals(pkg)) return youtube(title, subtitle);
        Found f = cinemeta(title);
        // Apps often put the episode in the title ("Show: S1:E3 Name" or "Show - Episode"): try the part before it.
        if (f == null) {
            String shorter = beforeAny(title, ": ", " - ", " | ", " (");
            if (!shorter.equals(title)) f = cinemeta(shorter);
        }
        return f;
    }

    /** The first YouTube search result for the title (and channel): its best thumbnail and its watch link. */
    static Found youtube(String title, String channel) {
        String query = channel == null || channel.isEmpty() ? title : title + " " + channel;
        String page = Net.get("https://www.youtube.com/results?search_query=" + Uri.encode(query));
        String id = page == null ? null : firstVideoId(page);
        if (id == null) return null;
        return new Found("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg", "https://www.youtube.com/watch?v=" + id);
    }

    /** The first "videoId":"..." on a YouTube results page, or null. */
    static String firstVideoId(String page) {
        String marker = "\"videoId\":\"";
        int at = page.indexOf(marker);
        if (at < 0) return null;
        int start = at + marker.length();
        if (start + 11 > page.length()) return null;
        String id = page.substring(start, start + 11);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) return null;
        }
        return id;
    }

    /** A series or film of that name in Cinemeta, an exact name first: its large backdrop. */
    static Found cinemeta(String name) {
        JSONObject best = null;
        for (String type : new String[]{"series", "movie"}) {
            String json = Net.get(CINEMETA + type + "/top/search=" + Uri.encode(name) + ".json");
            if (json == null) continue;
            try {
                JSONArray metas = new JSONObject(json).optJSONArray("metas");
                if (metas == null) continue;
                for (int i = 0; i < metas.length(); i++) {
                    JSONObject m = metas.optJSONObject(i);
                    if (m == null) continue;
                    if (best == null) best = m;
                    if (same(m.optString("name"), name)) {
                        best = m;
                        return new Found(backdrop(best), null);
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        return best == null ? null : new Found(backdrop(best), null);
    }

    /** Cinemeta's own picture link is the medium size: the large one fills a TV screen. */
    static String backdrop(JSONObject meta) {
        String id = meta.optString("id");
        if (id.startsWith("tt")) return "https://images.metahub.space/background/large/" + id + "/img";
        String bg = meta.optString("background");
        return bg.isEmpty() ? meta.optString("poster") : bg;
    }

    private static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) out.append(c);
        return out.toString();
    }

    private static String beforeAny(String s, String... marks) {
        int cut = s.length();
        for (String m : marks) {
            int i = s.indexOf(m);
            if (i > 0) cut = Math.min(cut, i);
        }
        return s.substring(0, cut).trim();
    }
}
