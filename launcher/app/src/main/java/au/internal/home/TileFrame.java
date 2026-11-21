// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.PathInterpolator;

/** A tile's foreground: hairline border, a brighter tinted ring when focused, and a one-off light sweep on focus. */
final class TileFrame extends Drawable {
    private final float density, radius;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint();
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private boolean focused;
    private int ringColor = 0xF2FFFFFF, base = 0xFFFFFFFF, idle = 0x14FFFFFF;
    private float tint = 0.14f;
    private float sweep = -1f;      // 0..1 while the sweep runs, else -1
    private ValueAnimator anim;

    TileFrame(float density) {
        this.density = density;
        this.radius = 10 * density;
        ring.setStyle(Paint.Style.STROKE);
    }

    /** The theme's ring (focused) and hairline (idle) colours, and how much of the app's colour tints the ring. */
    void setLook(Look look) { base = look.ring | 0xFF000000; idle = look.idle; tint = look.ringAccent; }

    /** Ring colour: the theme's ring colour (mostly white) with a hint of the app's own colour. */
    void setAccent(int accent) {
        ringColor = (Sky.lerpColor(base, accent | 0xFF000000, tint) & 0xFFFFFF) | 0xF2000000;
        invalidateSelf();
    }

    void setFocused(boolean f) {
        if (focused == f) return;
        focused = f;
        invalidateSelf();
    }

    void playSweep() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(90);
        anim.setDuration(750);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(a -> setSweep((float) a.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { setSweep(-1f); }
        });
        anim.start();
    }

    void stopSweep() { if (anim != null) anim.cancel(); setSweep(-1f); }

    /** For renders: freeze the sweep at a given point. */
    void setSweep(float t) { if (sweep == t) return; sweep = t; invalidateSelf(); }

    @Override protected void onBoundsChange(Rect b) {
        float band = Math.max(b.width(), b.height()) * 0.28f;
        shine.setShader(new LinearGradient(-band, 0, band, 0,
                new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (sweep >= 0 && shine.getShader() != null) {
            float travel = b.width() + b.height();
            m.setRotate(20);
            m.postTranslate(b.left - b.height() * 0.5f + travel * sweep, b.top);
            shine.getShader().setLocalMatrix(m);
            c.drawRect(b, shine);
        }
        float w = focused ? 2.5f * density : 1f * density;
        ring.setStrokeWidth(w);
        ring.setColor(focused ? ringColor : idle);
        r.set(b);
        r.inset(w / 2, w / 2);
        c.drawRoundRect(r, radius - w / 2, radius - w / 2, ring);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
