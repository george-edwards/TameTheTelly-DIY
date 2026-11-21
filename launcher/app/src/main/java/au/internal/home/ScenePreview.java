// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Config > Timed apps theme: a large card on the left of the screen showing a scene as the kids will see it,
 * round a stand-in for the app, going through a whole day (morning to nightfall, minutes counting down) every
 * DAY_MS. The list of scenes sits to its right; show() swaps the scene as each one is highlighted.
 */
final class ScenePreview {
    private static final long DAY_MS = 14_000;
    private static final int MINUTES = 30;

    private final Activity a;
    private final FrameLayout backdrop;
    private final AspectFrameLayout card;
    private SceneView view;
    private final long t0 = SystemClock.uptimeMillis();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (view == null) return;
            update();
            backdrop.postDelayed(this, 80);
        }
    };

    private void update() {
        float p = ((SystemClock.uptimeMillis() - t0) % DAY_MS) / (float) DAY_MS * .98f;
        view.scene.p = p;
        view.scene.minutesLeft = Math.max(0, Math.round((1 - p) * MINUTES));
    }

    ScenePreview(Activity a) {
        this.a = a;
        float d = a.getResources().getDisplayMetrics().density;
        backdrop = new FrameLayout(a);
        backdrop.setBackgroundColor(0xE6000000);
        card = new AspectFrameLayout(a);
        GradientDrawable round = new GradientDrawable();
        round.setColor(0xFF000000);
        round.setCornerRadius(18 * d);
        card.setBackground(round);
        card.setElevation(12 * d);
        // Where the app would be: a quiet stand-in behind the scene's window.
        TextView app = new TextView(a);
        app.setText("The app shows here");
        app.setTextColor(a.getColor(R.color.faint));
        app.setTextSize(16);
        app.setGravity(Gravity.CENTER);
        card.addView(app, new FrameLayout.LayoutParams(-1, -1));
        // The left 60% of the screen, leaving the right for the list of scenes (SettingsMenu.toSide: 30%).
        int w = Math.round(a.getResources().getDisplayMetrics().widthPixels * .60f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = Math.round(40 * d);
        backdrop.addView(card, lp);
    }

    /** Puts the card on screen (over Home, under the dialog). */
    void open() {
        ((ViewGroup) a.getWindow().getDecorView()).addView(backdrop, new ViewGroup.LayoutParams(-1, -1));
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(250).start();
        backdrop.post(tick);
    }

    void show(String sceneId) {
        if (view != null) card.removeView(view);
        view = new SceneView(a, Scene.Mode.OVERLAY, sceneId);
        card.addView(view, new FrameLayout.LayoutParams(-1, -1));
        update();
    }

    void close() {
        view = null;
        backdrop.removeCallbacks(tick);
        View b = backdrop;
        ViewGroup parent = (ViewGroup) b.getParent();
        if (parent != null) parent.removeView(b);
    }
}
