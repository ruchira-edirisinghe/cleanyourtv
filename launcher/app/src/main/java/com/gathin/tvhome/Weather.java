package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * saver_weather: the weather now, for the live screensaver's clock. From Open-Meteo (no account or key); the place is
 * weather_location ("Colombo", or "6.93,79.85"), or else the one the TV's internet address points to. The last reading
 * is kept, so the clock shows it at once while a new one is fetched; the place is looked up again once a day.
 */
final class Weather {
    static final long FRESH_MS = 20 * 60_000L;
    private static final long PLACE_MS = 24 * 3_600_000L;
    private static final String PREFS = "weather";

    /** One reading. */
    static final class Now {
        final double temperature;
        final int code;
        final boolean day;
        final String place;
        final long at;

        Now(double temperature, int code, boolean day, String place, long at) {
            this.temperature = temperature;
            this.code = code;
            this.day = day;
            this.place = place;
            this.at = at;
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < FRESH_MS;
        }
    }

    private Weather() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The last reading for this setting, or null. */
    static Now last(Context ctx, String location, boolean fahrenheit) {
        SharedPreferences p = prefs(ctx);
        if (!key(location, fahrenheit).equals(p.getString("key", "")) || !p.contains("at")) return null;
        return new Now(p.getFloat("temperature", 0f), p.getInt("code", 0), p.getBoolean("day", true),
                p.getString("place", ""), p.getLong("at", 0));
    }

    /** Fetches a new reading (off the main thread); null when offline or the place cannot be found. */
    static Now fetch(Context ctx, String location, boolean fahrenheit) {
        try {
            double[] at = new double[2];
            String place = place(ctx, location, at);
            if (place == null) return null;
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,is_day&timezone=auto%s", at[0], at[1],
                    fahrenheit ? "&temperature_unit=fahrenheit" : "");
            String body = Net.get(url);
            if (body == null) return null;
            JSONObject current = new JSONObject(body).getJSONObject("current");
            Now now = new Now(current.getDouble("temperature_2m"), current.getInt("weather_code"),
                    current.optInt("is_day", 1) == 1, place, System.currentTimeMillis());
            prefs(ctx).edit().putString("key", key(location, fahrenheit)).putFloat("temperature", (float) now.temperature)
                    .putInt("code", now.code).putBoolean("day", now.day).putString("place", now.place)
                    .putLong("at", now.at).apply();
            return now;
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(String location, boolean fahrenheit) {
        return location.trim().toLowerCase(Locale.ROOT) + (fahrenheit ? "|F" : "|C");
    }

    /** Fills latLon and returns the place's name: from the setting, the cache, a name search or the address. */
    private static String place(Context ctx, String location, double[] latLon) throws Exception {
        SharedPreferences p = prefs(ctx);
        String wanted = location.trim();
        if (wanted.equals(p.getString("placeFor", null)) && System.currentTimeMillis() - p.getLong("placeAt", 0) < PLACE_MS) {
            latLon[0] = Double.longBitsToDouble(p.getLong("lat", 0));
            latLon[1] = Double.longBitsToDouble(p.getLong("lon", 0));
            return p.getString("placeName", "");
        }
        String name;
        String[] parts = wanted.split(",");
        if (parts.length == 2 && parts[0].trim().matches("-?[0-9.]+") && parts[1].trim().matches("-?[0-9.]+")) {
            latLon[0] = Double.parseDouble(parts[0].trim());
            latLon[1] = Double.parseDouble(parts[1].trim());
            name = "";
        } else if (!wanted.isEmpty()) {
            String body = Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(wanted));
            JSONArray results = body == null ? null : new JSONObject(body).optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject r = results.getJSONObject(0);
            latLon[0] = r.getDouble("latitude");
            latLon[1] = r.getDouble("longitude");
            name = r.optString("name");
        } else {
            String body = Net.get("https://ipwho.is/?fields=success,city,latitude,longitude");
            JSONObject r = body == null ? null : new JSONObject(body);
            if (r == null || !r.optBoolean("success")) return null;
            latLon[0] = r.getDouble("latitude");
            latLon[1] = r.getDouble("longitude");
            name = r.optString("city");
        }
        p.edit().putString("placeFor", wanted).putLong("placeAt", System.currentTimeMillis())
                .putLong("lat", Double.doubleToLongBits(latLon[0])).putLong("lon", Double.doubleToLongBits(latLon[1]))
                .putString("placeName", name).apply();
        return name;
    }

    /** "Partly cloudy" and the like for a WMO weather code. */
    static int describe(int code) {
        if (code <= 1) return R.string.weather_clear;
        if (code == 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.string.weather_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }
}
