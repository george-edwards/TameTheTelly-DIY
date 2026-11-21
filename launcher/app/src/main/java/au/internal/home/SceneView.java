// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

/** Hosts a Scene and redraws it at the scene's own gentle frame rate while attached. */
final class SceneView extends View {
    final Scene scene;
    private final long t0 = SystemClock.uptimeMillis();
    private boolean running;

    SceneView(Context c, Scene.Mode mode) { this(c, mode, new HomeConfig(c).scene()); }

    SceneView(Context c, Scene.Mode mode, String id) {
        super(c);
        scene = Scene.create(c, mode, id);
        // A layer of its own so the video hole can be cut out with an anti-aliased CLEAR (Goodnight has no hole).
        if (mode == Scene.Mode.OVERLAY) setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, scene.frameMs());
        }
    };

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; post(frame); }
    @Override protected void onDetachedFromWindow() { running = false; removeCallbacks(frame); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        scene.t = (SystemClock.uptimeMillis() - t0) / 1000f;
        scene.draw(c, getWidth(), getHeight());
    }
}
