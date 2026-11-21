// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.util.Log;

/**
 * The TV's own Blue Light Filter (TCL Picture menu), reached through TCL's shared library
 * com.tcl.tvmanager by reflection, mirroring com.tcl.settings' PictureManager:
 *   TTvPictureManager.getInstance(ctx).setPqSpecialEffect(EN_PQ_EFFECT_LOW_BLUE_RAY, 0|1, EXECUTE_SAVE=2)
 * It filters the whole picture, so it also covers full-screen apps.
 */
final class TvBlueLight {
    private static final String TAG = "HomeBlueLight";
    private static final int EXECUTE_SAVE = 2;

    /** Current value (0 off, 1 on), or null if unavailable. */
    static Integer get(Context c) {
        try {
            Object[] mgrAndEffect = resolve(c);
            Object r = mgrAndEffect[0].getClass().getMethod("getPqSpecialEffect", mgrAndEffect[1].getClass())
                    .invoke(mgrAndEffect[0], mgrAndEffect[1]);
            return (Integer) r;
        } catch (Throwable t) {
            Log.w(TAG, "get failed", t);
            return null;
        }
    }

    /** Returns true if the TV accepted the change. */
    static boolean set(Context c, boolean on) {
        try {
            Object[] mgrAndEffect = resolve(c);
            Object ok = mgrAndEffect[0].getClass()
                    .getMethod("setPqSpecialEffect", mgrAndEffect[1].getClass(), int.class, int.class)
                    .invoke(mgrAndEffect[0], mgrAndEffect[1], on ? 1 : 0, EXECUTE_SAVE);
            Log.i(TAG, "set " + on + " -> " + ok);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            Log.w(TAG, "set failed", t);
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object[] resolve(Context c) throws Exception {
        Object inst = TvBacklight.mgr(c);
        Class eff = Class.forName("com.tcl.tvmanager.vo.EnTCLPqSpecialEffect");
        return new Object[]{inst, Enum.valueOf(eff, "EN_PQ_EFFECT_LOW_BLUE_RAY")};
    }
}
