// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * A painted bedtime world: a character travels along the bottom and up the right margin to its home as the
 * allowance is used, then sleeps there. Subclasses supply the artwork and palette touches; the video window
 * and Goodnight screen are shared.
 */
abstract class PaintedScene extends Scene {
    /** Background, travelling sprite and sleeping sprite. */
    static final class Art {
        final String id;
        final Bitmap world, riding, sleeping;
        Art(String id, Bitmap world, Bitmap riding, Bitmap sleeping) {
            this.id = id; this.world = world; this.riding = riding; this.sleeping = sleeping;
        }
    }
    /** One scene's artwork at a time, decoded once and shared by the overlay, Goodnight and the Home tile. */
    private static Art cachedArt;

    static Art art(String id, java.util.function.Supplier<Art> load) {
        synchronized (PaintedScene.class) {
            if (cachedArt == null || !cachedArt.id.equals(id)) cachedArt = load.get();
            return cachedArt;
        }
    }

    final Art art;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    private final PathMeasure journey;
    private final float[] position = new float[2];

    PaintedScene(Mode mode, Art art) {
        super(mode);
        this.art = art;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        Path route = new Path();
        // The last bend stays in the right margin so the traveller never disappears behind the video.
        route.moveTo(108, 1042);
        route.cubicTo(430, 1042, 650, 1052, 1000, 1040);
        route.cubicTo(1280, 1028, 1470, 1044, 1658, 1042);
        route.lineTo(1748, 1042);
        route.quadTo(1780, 1042, 1780, 1008);
        route.lineTo(1780, 886);
        journey = new PathMeasure(route, false);
    }

    // ---------- what the subclasses paint ----------

    /** Wash over the world as night falls (colour, strength at full night). */
    abstract int washColour();
    abstract float washAmount();
    /** The home's warm light at night. */
    abstract void homeGlow(Canvas c, float amount);
    /** Anything fixed on top of the world (e.g. a nest name plaque). */
    void decorate(Canvas c, float night) { }
    /** The traveller at (x, bottom y), faded by a. */
    abstract void drawRider(Canvas c, float x, float y, float a);
    /** The sleeper at home: centre x, bottom y, width. */
    abstract float[] sleepAt();
    /** The minutes left, somewhere in the margin by the home. */
    abstract void drawMinutes(Canvas c, float night);

    // ---------- shared ----------

    static Bitmap decodeFile(File f, boolean opaque) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        if (opaque) opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    static Bitmap decodeStream(InputStream in, boolean opaque) throws IOException {
        try (InputStream s = in) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            if (opaque) opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(s, null, opts);
        }
    }

    @Override long frameMs() { return mode == Mode.OVERLAY && p < .65f ? 125 : 80; }

    private void drawWorld(Canvas c) {
        boolean goodnight = mode == Mode.GOODNIGHT;
        float q = goodnight ? 1 : clamp(p), night = ramp(q, .65f, 1);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        rect.set(0, 0, W, H);
        if (art.world != null) c.drawBitmap(art.world, null, rect, paint);
        // A translucent wash preserves the painted texture through dusk.
        if (night > 0) {
            paint.setColor(alpha(washColour(), washAmount() * night));
            c.drawRect(0, 0, W, H, paint);
        }
        homeGlow(c, night * (1 - ramp(bye, .12f, .65f)));
        decorate(c, night);
        if (!goodnight) {
            float arriving = ramp(q, .975f, 1);
            journey.getPosTan(q * journey.getLength(), position, null);
            float bob = (1 - arriving) * 1.3f * (float) Math.sin(t * 1.6f);
            if (arriving < 1 && art.riding != null) drawRider(c, position[0], position[1] + bob, 1 - arriving);
            if (arriving > 0 && art.sleeping != null) {
                float[] s = sleepAt();
                sprite(c, art.sleeping, s[0], s[1], s[2], arriving);
            }
        }
        // Fixed, very gentle sparkles along the bottom. No blinking over the picture.
        if (night > 0) {
            for (int i = 0; i < 12; i++) {
                float x = 35 + (i * 173) % 1840;
                float y = 955 + (i * 29) % 100;
                float a = night * (.25f + .2f * (float) Math.sin(t * .7f + i));
                glow(c, x, y, 13, alpha(0xFFFFE5AC, a));
                paint.setColor(alpha(0xFFFFEDBB, a * 1.7f));
                c.drawCircle(x, y, 1.7f, paint);
            }
        }
    }

    void sprite(Canvas c, Bitmap bitmap, float x, float bottom, float width, float a) {
        if (bitmap == null) return;
        float height = width * bitmap.getHeight() / bitmap.getWidth();
        rect.set(x - width / 2, bottom - height, x + width / 2, bottom);
        paint.setShader(null);
        paint.setColor(alpha(Color.WHITE, a));
        c.drawBitmap(bitmap, null, rect, paint);
        paint.setColor(Color.WHITE);
    }

    void glow(Canvas c, float x, float y, float radius, int color) {
        drawGlow(c, x, y, radius, color, false);
    }

    /** The minutes as a number at (x, y), tilted by deg. */
    void number(Canvas c, float x, float y, float deg, int colour) {
        int save = c.save();
        c.rotate(deg, x, y);
        count.setColor(colour);
        drawCenteredText(c, Integer.toString(minutesLeft), x, y, count);
        c.restoreToCount(save);
    }

    @Override void draw(Canvas c, int w, int h) {
        int save = c.save();
        c.scale(w / (float) W, h / (float) H);
        drawWorld(c);
        if (mode == Mode.OVERLAY) drawWindow(c);
        else if (mode == Mode.GOODNIGHT) drawGoodnight(c);
        c.restoreToCount(save);
    }

    private void drawWindow(Canvas c) {
        float vignette = .6f * ramp(p, .86f, 1) + .4f * clamp(close);
        drawVideoWindow(c, paint, line, 0x10000000, 0f, 0xFF17192D, vignette, 2,
                alpha(0xFFFFF2D6, .55f - .25f * ramp(p, .7f, 1)));
        if (minutesLeft >= 0) drawMinutes(c, ramp(clamp(p), .65f, 1));
    }

    private void drawGoodnight(Canvas c) {
        // A close-up of the same sleeper, safely home. The remote's OK says goodnight.
        glow(c, 960, 420, 430, 0x30FFE4B4);
        float breathe = (float) Math.sin(t * 1.3f);
        sprite(c, art.sleeping, 960, 595 + breathe * 2, 500, 1);
        float a = 1 - ramp(bye, 0, .35f), bloom = ramp(bye, 0, .4f);
        glow(c, 960, 770, 125 + 12 * breathe + bloom * 120, alpha(0xFFFFE8B5, .18f * a));
        line.setStrokeWidth(5);
        line.setColor(alpha(0xFFFFEAC6, a * (.8f + .15f * breathe)));
        c.drawCircle(960, 770, 57 + 3 * breathe + bloom * 50, line);
        paint.setColor(alpha(0xFFFFEAC6, a));
        c.drawCircle(960, 770, 24, paint);
        if (bye > .45f) {
            paint.setColor(alpha(0xFF111525, ramp(bye, .45f, 1)));
            c.drawRect(0, 0, W, H, paint);
        }
    }
}
