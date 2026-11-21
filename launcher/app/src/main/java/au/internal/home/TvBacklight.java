// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.util.Log;

/**
 * The TV panel's own backlight (TCL Picture menu → Backlight), via TCL's shared library
 * com.tcl.tvmanager by reflection, the same way TCL's Gallery does it:
 *   TTvPictureManager.getInstance(ctx).getBacklight() / setBacklight(int)
 * Dims the whole screen (cameras and full-screen apps included), not just Home.
 */
final class TvBacklight {
    private static final String TAG = "HomeBacklight";

    /** Glide from `from` (the last level we set; null = unknown, so just set it) to `to` over about 2.5 s.
     *  Blocking; call off the UI thread. */
    static boolean fadeTo(Context c, Integer from, int to) {
        try {
            Object m = mgr(c);
            java.lang.reflect.Method set = m.getClass().getMethod("setBacklight", int.class);
            if (from == null || from <= 0 || from == to) from = to;
            int steps = Math.min(25, Math.max(1, Math.abs(to - from)));
            for (int i = 1; i <= steps; i++) {
                set.invoke(m, from + Math.round((to - from) * i / (float) steps));
                if (i < steps) Thread.sleep(100);
            }
            Log.i(TAG, "fade " + from + " -> " + to);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "set failed", t);
            return false;
        }
    }

    /** TCL's TTvPictureManager (also used by TvBlueLight). NB: its getBacklight() returns 0 to us whatever the
     *  real level is (seen 2026-11-02), so never fade from it. */
    static Object mgr(Context c) throws Exception {
        Class<?> mgr = Class.forName("com.tcl.tvmanager.TTvPictureManager");
        return mgr.getMethod("getInstance", Context.class).invoke(null, c.getApplicationContext());
    }
}
