package com.gathin.tvhome;

import android.content.Context;
import android.media.tv.TvInputManager;

/**
 * input_tiles: which TV input a tile opens. A value lists input ids separated by "|", most specific first: a
 * console's CEC input (HDMI100004), which also wakes the console, then its HDMI port, which is there even while
 * the console is unplugged and the CEC input is gone. The first one the TV has wins; with none found, the first.
 */
final class InputTiles {
    private InputTiles() { }

    static String pick(Context ctx, String value) {
        String[] ids = value.split("\\|");
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (String raw : ids) {
                String id = raw.trim();
                if (id.isEmpty()) continue;
                try {
                    if (tim.getTvInputInfo(id) != null) return id;
                } catch (RuntimeException ignored) {
                    // a TV input service that is restarting: try the next id
                }
            }
        }
        return ids[0].trim();
    }
}
