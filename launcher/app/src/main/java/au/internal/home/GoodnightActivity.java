// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.animation.LinearInterpolator;

/** Kitten is home and tucked in. OK or Back says goodnight and gently returns Home. */
public final class GoodnightActivity extends Activity {
    private static final long BYE_MS = 3_800, IDLE_MS = 15 * 60_000L;
    private SceneView view;
    private boolean leaving;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new SceneView(this, Scene.Mode.GOODNIGHT);
        setContentView(view);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(900).start();
    }

    @Override protected void onResume() {
        super.onResume();
        h.postDelayed(this::sayGoodnight, IDLE_MS);
    }

    @Override protected void onPause() {
        h.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BACK:
                sayGoodnight();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        return code == KeyEvent.KEYCODE_BACK || super.onKeyUp(code, e);   // Back handled on down
    }

    private void sayGoodnight() {
        if (leaving) return;
        leaving = true;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(BYE_MS);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(x -> view.scene.bye = (float) x.getAnimatedValue());
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                startActivity(MainActivity.home(GoodnightActivity.this));
                finish();
                overridePendingTransition(0, android.R.anim.fade_out);
            }
        });
        a.start();
    }
}
