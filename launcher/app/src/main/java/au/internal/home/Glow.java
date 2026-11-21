// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Map;

/**
 * Soft coloured light behind a raised tile, in that app's own colour. Strength follows the tile's
 * scale, so it rises and settles with the focus spring for free. The blurred shape is rendered once
 * per tile size (two sizes in practice) at quarter resolution and tinted at draw time.
 */
final class Glow extends RecyclerView.ItemDecoration {
    private static final int SCALE_DOWN = 4;
    private static final float FULL = 0.08f;          // tile scale growth at which the glow is at full strength
    private final float spread, radius, drop;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<Long, Bitmap> shapes = new HashMap<>();
    private final SparseArray<PorterDuffColorFilter> tints = new SparseArray<>();
    private final RectF dst = new RectF();
    private float strength = 1f;
    private int fixed;              // 0: each app's own colour

    /** The theme's glow: strength 0..1, in one colour, or (0) each app's own. */
    void setLook(Look look) { strength = look.glow; fixed = look.glowColour; }

    Glow(float density) {
        spread = 28 * density;
        radius = 10 * density;
        drop = 5 * density;
    }

    @Override public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State s) {
        if (strength <= 0f) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            Object tag = v.getTag(R.id.accent);
            if (!(tag instanceof Integer)) continue;
            float k = (v.getScaleX() - 1f) / FULL;
            if (k <= 0.02f) continue;
            int w = v.getWidth(), h = v.getHeight();
            if (w == 0 || h == 0) continue;
            int accent = fixed != 0 ? fixed : (Integer) tag;
            PorterDuffColorFilter tint = tints.get(accent);
            if (tint == null) tints.put(accent, tint = new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            paint.setColorFilter(tint);
            paint.setAlpha((int) (Math.min(1f, k) * 205 * strength * v.getAlpha()));
            float sx = v.getScaleX(), sy = v.getScaleY();
            float cx = v.getLeft() + v.getTranslationX() + w / 2f, cy = v.getTop() + v.getTranslationY() + h / 2f + drop * Math.min(1f, k);
            float hw = w * sx / 2f + spread, hh = h * sy / 2f + spread;
            dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
            c.drawBitmap(shape(w, h), null, dst, paint);
        }
    }

    private Bitmap shape(int w, int h) {
        long key = ((long) w << 32) | h;
        Bitmap b = shapes.get(key);
        if (b != null) return b;
        float sp = spread / SCALE_DOWN;
        int bw = (int) Math.ceil(w / (float) SCALE_DOWN + 2 * sp), bh = (int) Math.ceil(h / (float) SCALE_DOWN + 2 * sp);
        b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(sp * 0.6f, BlurMaskFilter.Blur.NORMAL));
        float rr = radius / SCALE_DOWN;
        new Canvas(b).drawRoundRect(sp, sp, bw - sp, bh - sp, rr, rr, p);
        shapes.put(key, b);
        return b;
    }
}
