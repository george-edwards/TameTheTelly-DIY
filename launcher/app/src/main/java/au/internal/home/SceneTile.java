// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * The scene tile (config.xml scene_tile_package, normally YouTube): a miniature of the chosen scene, with a play
 * badge and the app's name. The journey and dusk palette follow the same allowance as the full scene.
 */
final class SceneTile extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final String name;
    private final int badge;
    final Scene scene;
    private Bitmap cache;
    private boolean dirty = true;

    /** scene: a Scene in TILE mode; badge: the play badge's colour. */
    SceneTile(String name, Typeface font, Scene scene, int badge) {
        this.scene = scene;
        this.name = name;
        this.badge = badge;
        text.setTypeface(font);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.LEFT);
    }

    void setProgress(float p) {
        p = Scene.clamp(p);
        if (Math.abs(p - scene.p) < 0.005f && !dirty) return;
        scene.p = p;
        dirty = true;
        invalidateSelf();
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        int bw = b.width(), bh = b.height();
        if (bw <= 0 || bh <= 0) return;
        if (dirty || cache == null || cache.getWidth() != bw || cache.getHeight() != bh) {
            if (cache == null || cache.getWidth() != bw || cache.getHeight() != bh) {
                if (cache != null) cache.recycle();
                cache = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            }
            drawInto(new Canvas(cache), bw, bh);
            dirty = false;
        }
        c.drawBitmap(cache, b.left, b.top, null);
    }

    private void drawInto(Canvas c, int bwPx, int bhPx) {
        float w = bwPx, h = bhPx;
        scene.draw(c, bwPx, bhPx);
        // A quiet panel keeps the title readable over the painted world.
        fill.setColor(scene.tilePanel());
        r.set(w * .045f, h * .55f, w * .70f, h * .94f);
        c.drawRoundRect(r, h * .07f, h * .07f, fill);

        // Play badge + the app's name, bottom left.
        float th = h * 0.25f;
        text.setTextSize(th);
        text.setShadowLayer(h * 0.03f, 0, h * 0.01f, 0x66000000);
        float bh = th * 0.72f, bw = bh * 1.42f, bx = w * 0.07f, by = h * 0.86f - bh;
        r.set(bx, by, bx + bw, by + bh);
        fill.setColor(badge);
        c.drawRoundRect(r, bh * 0.28f, bh * 0.28f, fill);
        path.reset();
        float cx = r.centerX(), cy = r.centerY(), t = bh * 0.22f;
        path.moveTo(cx - t * 0.8f, cy - t); path.lineTo(cx + t * 1.1f, cy); path.lineTo(cx - t * 0.8f, cy + t); path.close();
        fill.setColor(Color.WHITE);
        c.drawPath(path, fill);
        c.drawText(name, bx + bw + w * 0.035f, h * 0.86f - bh * 0.08f, text);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
