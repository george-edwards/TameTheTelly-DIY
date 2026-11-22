// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Focus motion for app tiles (springs) and picking each app's accent colour from its banner. */
final class TileFx {
    static final float FOCUS = 1.08f, MOVING = 1.12f, LIFT_DP = 10f;
    static final float STIFFNESS = 1400f, DAMPING = 0.90f;
    static final int FALLBACK = 0xFFA9C8F0;      // soft blue-white for monochrome banners

    private TileFx() { }

    /** Spring the tile to its focused/idle size. The parent is redrawn on each frame so the glow follows. */
    static void animate(View v, float scale, float lift) {
        SpringAnimation[] s = (SpringAnimation[]) v.getTag(R.id.springs);
        if (s == null) {
            s = new SpringAnimation[]{spring(v, DynamicAnimation.SCALE_X), spring(v, DynamicAnimation.SCALE_Y),
                    spring(v, DynamicAnimation.TRANSLATION_Z)};
            if (v.getTag(R.id.accent) != null) {
                s[0].addUpdateListener((a, val, vel) -> { if (v.getParent() instanceof View) ((View) v.getParent()).invalidate(); });
                s[0].addEndListener((a, c, val, vel) -> { if (v.getParent() instanceof View) ((View) v.getParent()).invalidate(); });
            }
            v.setTag(R.id.springs, s);
        }
        s[0].animateToFinalPosition(scale);
        s[1].animateToFinalPosition(scale);
        if (lift != 0f || v.getTranslationZ() != 0f) s[2].animateToFinalPosition(lift);
    }

    /** Jump straight to a state (used for renders, and when a recycled view is rebound). */
    static void set(View v, float scale, float lift) {
        SpringAnimation[] s = (SpringAnimation[]) v.getTag(R.id.springs);
        if (s != null) for (SpringAnimation a : s) a.cancel();
        v.setScaleX(scale); v.setScaleY(scale); v.setTranslationZ(lift);
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty p) {
        SpringAnimation a = new SpringAnimation(v, p);
        a.setSpring(new SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        a.setMinimumVisibleChange(p == DynamicAnimation.TRANSLATION_Z ? 1f : DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        return a;
    }

    /**
     * The most characterful colour in a banner: pixels are bucketed by hue, weighted by how saturated
     * and bright they are, and the winning bucket's average is lifted to glow brightness. Mostly
     * black/white/grey banners get a neutral fallback.
     */
    static int accentOf(Drawable d) {
        if (d == null) return FALLBACK;
        int w = 48, h = 27;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable c = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        c.setBounds(0, 0, w, h);
        c.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        float[] wt = new float[12], r = new float[12], g = new float[12], bl = new float[12];
        float[] hsv = new float[3];
        float total = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;
            float weight = hsv[1] * hsv[1] * hsv[2];
            int k = ((int) (hsv[0] / 30f)) % 12;
            wt[k] += weight; r[k] += Color.red(p) * weight; g[k] += Color.green(p) * weight; bl[k] += Color.blue(p) * weight;
            total += weight;
        }
        int best = 0;
        for (int k = 1; k < 12; k++) if (wt[k] > wt[best]) best = k;
        if (total < px.length * 0.012f || wt[best] == 0) return FALLBACK;
        int avg = Color.rgb((int) (r[best] / wt[best]), (int) (g[best] / wt[best]), (int) (bl[best] / wt[best]));
        Color.colorToHSV(avg, hsv);
        hsv[1] = Math.max(0.45f, Math.min(0.85f, hsv[1]));
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }
}
