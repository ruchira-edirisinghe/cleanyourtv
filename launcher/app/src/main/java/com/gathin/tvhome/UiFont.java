package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The typeface behind one of the font_* strings in res/values/fonts.xml, for views built in code. The string
 * points at a bundled font, or, in locales Outfit can't draw (values-vi), names a system family (#833). A font in
 * the theme folder named after the string (fonts/font_label.ttf, pushed through ThemeProvider) is used instead.
 */
final class UiFont {
    private UiFont() {}

    /** Theme fonts read so far, by path and the time the file was written, so a pushed font is read once. */
    private static final Map<String, Typeface> THEME = new HashMap<>();

    static Typeface get(Context context, int fontString) {
        Resources res = context.getResources();
        Typeface own = themeFont(context, res.getResourceEntryName(fontString));
        if (own != null) return own;
        TypedValue value = new TypedValue();
        res.getValue(fontString, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return res.getFont(value.resourceId);
        return Typeface.create(name, Typeface.NORMAL);
    }

    private static Typeface themeFont(Context context, String entry) {
        File dir = Config.dir(context);
        if (dir == null) return null;
        for (String ext : new String[]{".ttf", ".otf"}) {
            File f = new File(new File(dir, ThemeFiles.FONTS), entry + ext);
            if (!f.isFile()) continue;
            String key = f.getPath() + '@' + f.lastModified();
            synchronized (THEME) {
                Typeface t = THEME.get(key);
                if (t != null) return t;
                try {
                    t = Typeface.createFromFile(f);
                } catch (RuntimeException e) {
                    Log.w("TvHome", "theme font " + f.getName() + " could not be read", e);
                    return null;
                }
                THEME.put(key, t);
                return t;
            }
        }
        return null;
    }
}
