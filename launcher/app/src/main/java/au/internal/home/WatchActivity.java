// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;

/**
 * Opens a timed app (Home's tiles, and any remote button mapped to a timed app in Config > Remote buttons;
 * extra "pkg" picks the app, otherwise it's config.xml's scene_tile_package).
 * Asleep -> Goodnight. Otherwise the app opens in a window sized so its picture lands exactly in the Scene's video
 * hole (window()). Needs freeform windows enabled on the TV (enable_freeform_support=1).
 *
 * The window is a "multi-window" one, which is freeform without the title bar: a freeform window's 42 dp bar is
 * drawn over the top of the app (Android 11 lays the app out under it), so tucking it behind the scene cropped the
 * app's top, and YouTube needed calibrated margins. Without the bar the window is the hole exactly, the app sees a
 * 16:9 screen and YouTube's picture fills it. Apps that hand themselves off to a new task as they start (YouTube)
 * lose the mode on the way; Framer moves them back into a window.
 *
 * The windowing mode isn't public API, but ActivityOptions travel as a Bundle and the system reads the
 * mode from this key, so no hidden-API access is needed.
 */
public final class WatchActivity extends Activity {
    private static final String KEY_WINDOWING_MODE = "android.activity.windowingMode";
    private static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScreenTimeService.start(this);
        String pkg = getIntent().getStringExtra("pkg");
        open(this, pkg != null ? pkg : getString(R.string.scene_tile_package));
        finish();
        overridePendingTransition(0, 0);
    }

    static void open(Activity a, String pkg) {
        ScreenTime st = new ScreenTime(a);
        long now = System.currentTimeMillis();
        if (st.grownUp(now)) { openFullScreen(a, pkg); return; }
        if (st.asleep(now)) {
            a.startActivity(new Intent(a, GoodnightActivity.class));
            return;
        }
        Intent i = AppStore.launchIntent(a.getPackageManager(), pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Ask for a window; Framer then sizes it to the scene's hole over Home's own adb connection.
        if (!a.getResources().getBoolean(R.bool.frame_via_adb)) { a.startActivity(i); return; }
        try { a.startActivity(i, windowOptions(a.getResources())); }
        catch (RuntimeException e) { Log.w(ScreenTimeService.TAG, "windowed launch failed", e); a.startActivity(i); }
    }

    /** Grown-up time: the app full screen, as it normally is (see Framer.fullScreen). */
    static void openFullScreen(Activity a, String pkg) {
        Intent i = AppStore.launchIntent(a.getPackageManager(), pkg);
        if (i == null) return;
        Intent plain = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!a.getResources().getBoolean(R.bool.frame_via_adb)) { a.startActivity(plain); return; }
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            if (!new Framer(app).fullScreen(pkg)) app.startActivity(plain);
        }, "fullscreen").start();
    }

    /** A timed app's window, in screen pixels: the scene's hole. */
    static Rect window(Resources r) {
        float sx = r.getDisplayMetrics().widthPixels / (float) Scene.W, sy = r.getDisplayMetrics().heightPixels / (float) Scene.H;
        return new Rect(Math.round(Scene.HOLE.left * sx), Math.round(Scene.HOLE.top * sy),
                Math.round(Scene.HOLE.right * sx), Math.round(Scene.HOLE.bottom * sy));
    }

    /** Start in a multi-window task in the hole. */
    static Bundle windowOptions(Resources r) {
        Bundle o = ActivityOptions.makeBasic().setLaunchBounds(window(r)).toBundle();
        o.putInt(KEY_WINDOWING_MODE, WINDOWING_MODE_MULTI_WINDOW);
        return o;
    }
}
