package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * now_playing: keeps the TV from going to sleep while the screensaver shows a song that plays. Android ends a
 * screensaver, and puts the TV to sleep, once the sleep timeout (Settings.Secure "sleep_timeout", the TV's "turn off
 * after") has passed since the last key, whatever plays; a window that asks to keep the screen on counts only before
 * the screensaver starts. So while the song plays the timeout is set to never, and the owner's own value goes back
 * when it stops or the screensaver ends. The value is saved before it is changed, so a launcher that dies holding it
 * puts it back the next time Home starts. Needs WRITE_SECURE_SETTINGS (granted over adb); without it, nothing changes.
 */
final class SleepHold {
    private static final String KEY = "sleep_timeout";
    private static final String PREFS = "sleep_hold", SAVED = "saved_sleep_timeout";
    /** A screensaver of this process holds it now. */
    private static boolean held;

    private SleepHold() {
    }

    /** The TV no longer sleeps by itself; the owner's timeout is kept to put back. */
    static void hold(Context context) {
        if (held) return;
        ContentResolver cr = context.getContentResolver();
        SharedPreferences prefs = prefs(context);
        try {
            String now = Settings.Secure.getString(cr, KEY);
            if (!prefs.contains(SAVED)) {
                if (now == null || parse(now) <= 0) return;   // it never sleeps anyway
                prefs.edit().putString(SAVED, now).commit();
            }
            Settings.Secure.putString(cr, KEY, "-1");
            held = true;
        } catch (SecurityException e) {
            prefs.edit().remove(SAVED).apply();
        }
    }

    /** Puts the owner's sleep timeout back, unless they changed it in the meantime. */
    static void release(Context context) {
        held = false;
        SharedPreferences prefs = prefs(context);
        String saved = prefs.getString(SAVED, null);
        if (saved == null) return;
        ContentResolver cr = context.getContentResolver();
        try {
            if ("-1".equals(Settings.Secure.getString(cr, KEY))) Settings.Secure.putString(cr, KEY, saved);
        } catch (SecurityException ignored) {
            // nothing was changed without it either
        }
        prefs.edit().remove(SAVED).apply();
    }

    /** Home starting: a timeout left at never by a launcher that died holding it goes back. */
    static void repair(Context context) {
        if (!held) release(context);
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
