package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The few web requests the Continue watching row makes (continue_watching): Stremio's account list, cover pictures
 * and the lookups that find them. Blocking calls with short timeouts, never on the main thread; any failure is null.
 */
final class Net {
    private static final String TAG = "TvHomeNet";
    private static final int TIMEOUT_MS = 10_000;
    /** Larger answers are cut off: a page or a picture, never more. */
    private static final int MAX_BYTES = 8 << 20;
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    private Net() {
    }

    static String get(String url) {
        byte[] b = request(url, null);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static String postJson(String url, String json) {
        byte[] b = request(url, json);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] request(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Language", "en");
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) break;
                }
                return buf.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "request failed: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Fetches a picture once into this file (kept for next time); false when it could not be had. */
    static boolean downloadTo(String url, File file) {
        if (file.exists() && file.length() > 0) return true;
        byte[] b = request(url, null);
        if (b == null || b.length == 0) return false;
        File tmp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(file);
    }

    /** A picture from the web, decoded no larger than needed for this width. */
    static Bitmap bitmap(String url, int width) {
        byte[] b = request(url, null);
        return b == null ? null : decode(b, width);
    }

    static Bitmap decode(byte[] b, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        o.inSampleSize = sample(o.outWidth, width);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    /** A picture file decoded no larger than needed for this width, or null. */
    static Bitmap decodeFile(File file, int width) {
        if (!file.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = sample(o.outWidth, width);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static int sample(int have, int want) {
        int s = 1;
        while (want > 0 && have / (s * 2) >= want) s *= 2;
        return s;
    }
}
