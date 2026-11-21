// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * A drawn outdoor world around the video, with no artwork files: a sky that runs from morning through sunset to
 * night as the allowance is used, a sun that travels up the left side, across the top and down the right side,
 * drifting clouds, then stars, a rising moon and fireflies. GOODNIGHT mode is the same world at night with a big
 * sleeping moon and a softly breathing "OK" ring. Subclasses paint their own palette and land (drawLand), and
 * say where the minutes left go (drawMinutes; on the sun by default).
 *
 * Everything is drawn in a 1920x1080 reference space and scaled to the canvas, so it renders the same on the
 * TV, in Paparazzi and at any preview size.
 *
 * Progress p: 0..0.7 day, 0.7..0.9 golden hour -> twilight (sun sets ~0.83), 0.9..1 night, 1 asleep.
 */
abstract class SkyScene extends Scene {
    /** Palette keyframes: progress -> colour. Each palette array has one colour per keyframe. */
    static final float[] KP = {0f, 0.35f, 0.62f, 0.72f, 0.80f, 0.86f, 0.92f, 1f};
    static final int[] DEFAULT_CLOUD = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFF8F4EE, 0xFFFFE6C4, 0xFFF7A99E, 0xFF9C7AA8, 0xFF3A3D6A, 0xFF262A52};
    static final int[] DEFAULT_RIM = {0x66FFFFFF, 0x66FFFFFF, 0x60FFFFFF, 0x66FFE7B8, 0x60FFC7A0, 0x50C9B8FF, 0x40A9B4FF, 0x389AA6E8};
    static final int DEFAULT_FLY = 0xFFFFF1A0;

    /** A line of gentle hills along the bottom: holds both its geometry and its height function. */
    static final class Hill {
        private final float base, amp, wave, ph, h1, c1, w1, h2, c2, w2;
        final Path path = new Path();

        Hill(float base, float amp, float wave, float ph, float h1, float c1, float w1, float h2, float c2, float w2) {
            this.base = base; this.amp = amp; this.wave = wave; this.ph = ph;
            this.h1 = h1; this.c1 = c1; this.w1 = w1; this.h2 = h2; this.c2 = c2; this.w2 = w2;
            path.moveTo(0, H);
            for (int x = 0; x <= W; x += 16) path.lineTo(x, y(x));
            path.lineTo(W, H);
            path.close();
        }

        float y(float x) {
            return base + amp * (float) Math.sin(x / wave + ph) + amp * 0.6f * (float) Math.sin(x / (wave * 0.37f))
                    - h1 * bump(x, c1, w1) - h2 * bump(x, c2, w2);
        }
    }

    private final int[] skyTop, skyBot, cloud, rim;
    private final int flyColour;

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path tmp = new Path();
    private final Path sunPath = new Path(), moonPath = new Path(), moonBite = new Path();
    private final PathMeasure sunMeasure;
    final float[] pos = new float[2];
    final RectF r = new RectF();
    private final float[] stars = new float[130 * 4];     // x, y, size, phase
    private final float[] flies = new float[22 * 5];      // x, y, phase, speed, size
    private LinearGradient skyShader;
    private int lastSkyTop, lastSkyBot;
    private float lastMoonX = Float.NaN, lastMoonY, lastMoonR;
    private static final float[][] CLOUDS = {   // x, y, scale, speed px/s
            {300, 48, 0.85f, 6}, {1150, 62, 1.0f, 4.5f}, {80, 330, 0.9f, 3.5f}, {1830, 470, 0.8f, 5}, {720, 30, 0.6f, 7}};

    SkyScene(Mode mode, int[] skyTop, int[] skyBot) {
        this(mode, skyTop, skyBot, DEFAULT_CLOUD, DEFAULT_RIM, DEFAULT_FLY);
    }

    /** Palettes: sky top and bottom, clouds and the video window's rim, one colour per KP keyframe. */
    SkyScene(Mode mode, int[] skyTop, int[] skyBot, int[] cloud, int[] rim, int flyColour) {
        super(mode);
        this.skyTop = skyTop; this.skyBot = skyBot; this.cloud = cloud; this.rim = rim; this.flyColour = flyColour;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        // Sun: up the left side, over the top of the video, down the right side behind the land.
        sunPath.moveTo(118, 920);
        sunPath.lineTo(118, 240);
        sunPath.quadTo(118, 50, 330, 50);
        sunPath.lineTo(1590, 50);
        sunPath.quadTo(1802, 50, 1802, 240);
        sunPath.lineTo(1802, 1010);
        sunMeasure = new PathMeasure(sunPath, false);

        Random rnd = new Random(11);
        for (int i = 0; i < stars.length; i += 4) {
            stars[i] = rnd.nextFloat() * W;
            stars[i + 1] = rnd.nextFloat() * 820;
            stars[i + 2] = 1.2f + rnd.nextFloat() * rnd.nextFloat() * 3.2f;
            stars[i + 3] = rnd.nextFloat() * 6.283f;
        }
        for (int i = 0; i < flies.length; i += 5) {
            boolean side = rnd.nextFloat() < 0.45f;
            flies[i] = side ? (rnd.nextBoolean() ? 30 + rnd.nextFloat() * 180 : 1710 + rnd.nextFloat() * 180) : rnd.nextFloat() * W;
            flies[i + 1] = side ? 420 + rnd.nextFloat() * 560 : 930 + rnd.nextFloat() * 130;
            flies[i + 2] = rnd.nextFloat() * 6.283f;
            flies[i + 3] = 0.6f + rnd.nextFloat() * 0.8f;
            flies[i + 4] = 3 + rnd.nextFloat() * 2.5f;
        }
    }

    // ---------- what the subclasses paint ----------

    /** Hills, buildings and the traveller, between the sky and the fireflies. q is the progress being drawn
     *  (1 on the Goodnight screen). */
    abstract void drawLand(Canvas c, float q);

    /** The minutes left (minutesLeft), beside the video. By default they ride on the sun, and once it has set
     *  they hang in the sky where it went down, in moonlight. */
    void drawMinutes(Canvas c, float q) {
        sunAt(q);
        float x = pos[0], y = Math.min(pos[1], 560);
        float set = ramp(pos[1], 470, 560);
        if (set > 0) dot(c, x, y, 70, alpha(0xFFB9C6FF, 0.3f * set));
        count.setColor(Sky.lerpColor(0xFF8A4A1C, 0xFFFFF4D6, set));
        drawCenteredText(c, Integer.toString(minutesLeft), x, y, count);
    }

    // ---------- helpers for subclasses ----------

    /** Colour of a palette at progress q. */
    static int key(int[] cols, float q) {
        float c = clamp(q);
        int i = 0;
        while (i < KP.length - 2 && KP[i + 1] <= c) i++;
        float f = (c - KP[i]) / (KP[i + 1] - KP[i]);
        return Sky.lerpColor(cols[i], cols[i + 1], smooth(f));
    }

    /** Soft round glow of colour col (alpha included), radius rad, at (x, y). */
    void dot(Canvas c, float x, float y, float rad, int col) {
        drawGlow(c, x, y, rad, col, true);
    }

    /** The sun's position at progress q, into pos. */
    void sunAt(float q) {
        float s = 0.1f + 0.9f * Math.min(1f, q / 0.9f);
        sunMeasure.getPosTan(s * sunMeasure.getLength(), pos, null);
    }

    /** How lit a home's window is: on at dusk; on the Goodnight screen it goes out after OK is pressed. */
    float windowLight(float q) { return mode == Mode.GOODNIGHT ? 1f - ramp(bye, 0.12f, 0.2f) : ramp(q, 0.76f, 0.84f); }

    private static float bump(float x, float c, float w) { float d = (x - c) / w; return (float) Math.exp(-d * d); }

    // ---------- public bits ----------

    @Override int barColour(float p) {
        return Sky.lerpColor(key(skyTop, p), key(skyBot, p), 0.6f);
    }

    @Override int tilePanel() { return 0x99203048; }

    /** How often to redraw: twinkles and fireflies want ~20 fps, drifting clouds are fine at ~8. */
    @Override long frameMs() { return mode == Mode.GOODNIGHT || p >= 0.78f ? 50 : 125; }

    @Override void draw(Canvas c, int w, int h) {
        int save = c.save();
        c.scale(w / (float) W, h / (float) H);
        float q = mode == Mode.GOODNIGHT ? 1f : p;

        // Sky
        int top = key(skyTop, q), bot = key(skyBot, q);
        if (skyShader == null || top != lastSkyTop || bot != lastSkyBot) {
            lastSkyTop = top; lastSkyBot = bot;
            skyShader = new LinearGradient(0, 0, 0, H, top, bot, Shader.TileMode.CLAMP);
        }
        fill.setColor(0xFFFFFFFF);   // a shader is modulated by the paint's alpha: don't inherit last frame's
        fill.setShader(skyShader);
        c.drawRect(0, 0, W, H, fill);
        fill.setShader(null);

        // Stars, twinkling
        float starA = mode == Mode.GOODNIGHT ? 1f : ramp(q, 0.8f, 0.93f);
        if (starA > 0) {
            for (int i = 0; i < stars.length; i += 4) {
                float tw = 0.55f + 0.45f * (float) Math.sin(t * (0.6f + (i % 7) * 0.13f) + stars[i + 3]);
                fill.setColor(alpha(0xFFFFF8E8, starA * tw));
                c.drawCircle(stars[i], stars[i + 1], stars[i + 2], fill);
            }
        }

        if (mode != Mode.GOODNIGHT) drawSun(c, q);
        drawMoon(c, q);
        drawClouds(c, q);

        drawLand(c, q);

        // Fireflies
        float flyA = mode == Mode.GOODNIGHT ? 1f - ramp(bye, 0.3f, 0.8f) : ramp(q, 0.87f, 0.95f);
        if (flyA > 0) {
            for (int i = 0; i < flies.length; i += 5) {
                float ph = flies[i + 2], sp = flies[i + 3];
                float x = flies[i] + 34 * (float) Math.sin(t * 0.23f * sp + ph) + 12 * (float) Math.sin(t * 0.71f + ph * 2);
                float y = flies[i + 1] + 16 * (float) Math.sin(t * 0.37f * sp + ph * 1.3f);
                float blink = (float) Math.pow(0.5f + 0.5f * Math.sin(t * 1.3f * sp + ph * 3), 2);
                dot(c, x, y, flies[i + 4] * 6, alpha(flyColour, flyA * (0.25f + 0.75f * blink)));
            }
        }

        if (mode == Mode.OVERLAY) drawWindow(c, q);
        else if (mode == Mode.GOODNIGHT) drawGoodnight(c);

        c.restoreToCount(save);
    }

    private void drawSun(Canvas c, float q) {
        // 0.1 of the way at p=0 (morning, already up over the left hills), behind the right hills by ~0.85.
        sunAt(q);
        float x = pos[0], y = pos[1];
        float warm = ramp(q, 0.62f, 0.84f);
        int core = Sky.lerpColor(0xFFFFE15A, 0xFFFF7A40, warm);
        int halo = Sky.lerpColor(0xFFFFF4B8, 0xFFFFA060, warm);
        dot(c, x, y, 190 + 60 * warm, alpha(halo, 0.55f + 0.25f * warm));
        fill.setColor(core);
        c.drawCircle(x, y, 44, fill);
    }

    private void drawMoon(Canvas c, float q) {
        float a, x, y, rad;
        if (mode == Mode.GOODNIGHT) { a = 1f; x = 960; y = 330; rad = 118; }
        else {
            a = ramp(q, 0.89f, 0.97f);
            if (a <= 0) return;
            x = 118; y = 720 - 440 * ramp(q, 0.89f, 1f); rad = 40;
        }
        dot(c, x, y, rad * 3.4f, alpha(0xFFB9C6FF, 0.32f * a));
        // Crescent: full disc with a sky-coloured bite out of it would show the stars through; use a path op.
        if (x != lastMoonX || y != lastMoonY || rad != lastMoonR) {
            lastMoonX = x; lastMoonY = y; lastMoonR = rad;
            moonPath.reset();
            moonPath.addCircle(x, y, rad, Path.Direction.CW);
            moonBite.reset();
            moonBite.addCircle(x + rad * 0.42f, y - rad * 0.22f, rad * 0.86f, Path.Direction.CW);
            moonPath.op(moonBite, Path.Op.DIFFERENCE);
        }
        fill.setColor(alpha(0xFFFFF4D6, a));
        c.drawPath(moonPath, fill);
        if (mode == Mode.GOODNIGHT) {
            // A sleeping face on the crescent: closed eye and a little smile, then Zzz drifting up.
            stroke.setStrokeWidth(6);
            stroke.setColor(0xFF6A5A8A);
            r.set(x - rad * 0.78f, y - rad * 0.30f, x - rad * 0.42f, y - rad * 0.02f);
            c.drawArc(r, 20, 140, false, stroke);
            r.set(x - rad * 0.80f, y + rad * 0.18f, x - rad * 0.46f, y + rad * 0.46f);
            c.drawArc(r, 30, 110, false, stroke);
            fill.setColor(0x55FF9AA8);
            c.drawCircle(x - rad * 0.50f, y + rad * 0.12f, rad * 0.10f, fill);
            float zA = 1f - ramp(bye, 0.1f, 0.5f);
            for (int k = 0; k < 3; k++) {
                float f = ((t * 0.18f + k / 3f) % 1f);
                float zx = x + rad * 0.9f + f * 120 + 14 * (float) Math.sin(t + k);
                float zy = y - rad * 0.6f - f * 190;
                float size = 22 + 26 * f;
                stroke.setStrokeWidth(5 + 2 * f);
                stroke.setColor(alpha(0xFFE8E4FF, zA * (float) Math.sin(Math.PI * f)));
                tmp.reset();
                tmp.moveTo(zx, zy); tmp.lineTo(zx + size, zy); tmp.lineTo(zx, zy + size); tmp.lineTo(zx + size, zy + size);
                c.drawPath(tmp, stroke);
            }
        }
    }

    private void drawClouds(Canvas c, float q) {
        int col = key(cloud, q);
        float a = mode == Mode.GOODNIGHT ? 0.55f : 1f - 0.45f * ramp(q, 0.9f, 1f);
        fill.setColor(alpha(Sky.lerpColor(key(skyTop, q), col, 0.92f), a));
        for (float[] cl : CLOUDS) {
            float span = W + 400;
            float x = ((cl[0] + cl[3] * t) % span + span) % span - 200, y = cl[1], k = cl[2];
            // One path so overlapping puffs don't double up their alpha.
            tmp.reset();
            tmp.addCircle(x, y, 34 * k, Path.Direction.CW);
            tmp.addCircle(x + 42 * k, y - 16 * k, 42 * k, Path.Direction.CW);
            tmp.addCircle(x + 92 * k, y - 4 * k, 34 * k, Path.Direction.CW);
            tmp.addCircle(x + 126 * k, y + 8 * k, 24 * k, Path.Direction.CW);
            r.set(x - 10 * k, y, x + 140 * k, y + 32 * k);
            tmp.addRoundRect(r, 16 * k, 16 * k, Path.Direction.CW);
            tmp.setFillType(Path.FillType.WINDING);
            c.drawPath(tmp, fill);
        }
    }

    /** The video window: cut it out, rim it, then dim and vignette it as the night arrives. */
    private void drawWindow(Canvas c, float q) {
        float vig = 0.6f * ramp(q, 0.86f, 1f) + 0.4f * close;
        drawVideoWindow(c, fill, stroke, alpha(0xFF000000, 0.07f), 2f, 0xFF060918, vig, 3, key(rim, q));
        if (minutesLeft >= 0) drawMinutes(c, q);
    }

    /** The "press OK" ring: breathes slowly, and when pressed blooms and fades with everything else. */
    private void drawGoodnight(Canvas c) {
        float cx = 960, cy = 760;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2.0f);
        float a = 1f - ramp(bye, 0f, 0.35f);
        float bloom = ramp(bye, 0f, 0.4f);
        dot(c, cx, cy, 150 + 40 * breathe + 220 * bloom, alpha(0xFFFFE7A8, (0.30f + 0.2f * breathe) * (1 - 0.5f * bloom)));
        stroke.setStrokeWidth(7);
        stroke.setColor(alpha(0xFFFFF3D0, a * (0.65f + 0.35f * breathe)));
        c.drawCircle(cx, cy, 64 + 6 * breathe + 60 * bloom, stroke);
        fill.setColor(alpha(0xFFFFF3D0, a * 0.95f));
        c.drawCircle(cx, cy, 30 + 3 * breathe, fill);
        // Then everything fades to night-black before Home comes back.
        float out = ramp(bye, 0.45f, 1f);
        if (out > 0) { fill.setColor(alpha(0xFF020308, out)); c.drawRect(0, 0, W, H, fill); }
    }
}
