// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.SparseArray;

/**
 * The little world around the timed app's window. Everything follows allowance progress p (0..1+), never animation
 * time: 0 – 0.7 day, 0.7 – 0.9 golden hour then twilight, 0.9 – 1 nightfall, 1 asleep.
 * Drawn in a 1920x1080 reference space and scaled to the canvas. Which scene is chosen in HomeConfig.
 */
abstract class Scene {
    static final int W = 1920, H = 1080;
    /** Where the video shows through: the timed app's window (WatchActivity.window), the same for every scene. */
    static final RectF HOLE = new RectF(240, 100, 1680, 910);
    static final float HOLE_R = 26;
    /** OVERLAY: world with the video hole. GOODNIGHT: the sleeping close-up. TILE: world only, for the Home tile. */
    enum Mode { OVERLAY, GOODNIGHT, TILE }

    float p;        // allowance progress
    float t;        // seconds, for gentle motion
    float close;    // 0..1: the night closing over the video just before Goodnight (overlay)
    float bye;      // 0..1: after OK is pressed on the Goodnight screen
    /** Minutes of kids' time left, shown somewhere in the scene beside the video; negative hides it. */
    int minutesLeft = -1;
    final Mode mode;

    final Paint count = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hole = new RectF();
    private final Matrix glowMatrix = new Matrix();
    /** One unit-radius gradient per (colour, stop profile); position, size and alpha are applied per draw. */
    private final SparseArray<RadialGradient> glows = new SparseArray<>();
    private RadialGradient vignetteShader;
    private int vignetteColor;

    Scene(Mode mode) {
        this.mode = mode;
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        count.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        count.setTextAlign(Paint.Align.CENTER);
        count.setTextSize(32);
    }

    /** The scene chosen in settings. */
    static Scene create(Context c, Mode mode) { return create(c, mode, new HomeConfig(c).scene()); }

    /** A scene by its id (HomeConfig.SCENES): Kids' time's preview shows each one as it's highlighted. */
    static Scene create(Context c, Mode mode, String id) {
        switch (id) {
            case HomeConfig.SCENE_FAIRY: return new FairyScene(mode);
            case HomeConfig.SCENE_TRAIN: return new TrainScene(mode);
            default: return new SunScene(mode);
        }
    }

    abstract void draw(Canvas c, int w, int h);

    /** The sky's colour at progress p, for the Home tile's time-left bar. */
    abstract int barColour(float p);

    /** The translucent panel behind the app's name on the scene tile (SceneTile). */
    abstract int tilePanel();

    /** How often to redraw. */
    long frameMs() { return 80; }

    /** The overlay's own darkness over the video: 0 by day, darker as the night closes in. */
    float videoDim() { return Math.max(.32f * ramp(p, .9f, 1), clamp(close)); }

    /** Soft round glow of colour (alpha included), radius rad, at (x, y). Cached unit shader per RGB. */
    final void drawGlow(Canvas c, float x, float y, float radius, int color, boolean threeStop) {
        int a = Color.alpha(color);
        if (a == 0) return;
        int rgb = color & 0xffffff;
        int key = rgb | (threeStop ? 0x01000000 : 0);
        RadialGradient g = glows.get(key);
        if (g == null) {
            g = threeStop
                    ? new RadialGradient(0, 0, 1, new int[]{rgb | 0xff000000, rgb | 0x66000000, rgb},
                            new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP)
                    : new RadialGradient(0, 0, 1, new int[]{rgb | 0xff000000, rgb}, null, Shader.TileMode.CLAMP);
            glows.put(key, g);
        }
        glowMatrix.setScale(radius, radius);
        glowMatrix.postTranslate(x, y);
        g.setLocalMatrix(glowMatrix);
        glowPaint.setColor(Color.WHITE);
        glowPaint.setAlpha(a);
        glowPaint.setShader(g);
        c.drawCircle(x, y, radius, glowPaint);
        glowPaint.setShader(null);
    }

    /** Cut out the video window, draw its shadow, dim/vignette it as night arrives, and rim it. */
    final void drawVideoWindow(Canvas c, Paint fill, Paint stroke, int shadowColor, float shadowRadiusStep,
                               int dimColor, float vig, float rimWidth, int rimColor) {
        hole.set(HOLE);
        for (int i = 3; i >= 1; i--) {
            stroke.setStrokeWidth(i * 7);
            stroke.setColor(shadowColor);
            float rr = HOLE_R + i * shadowRadiusStep;
            c.drawRoundRect(hole, rr, rr, stroke);
        }
        c.drawRoundRect(hole, HOLE_R, HOLE_R, clear);
        float dim = videoDim();
        if (dim > 0) {
            fill.setColor(alpha(dimColor, dim));
            c.drawRoundRect(hole, HOLE_R, HOLE_R, fill);
        }
        float v = clamp(vig);
        if (v > 0) {
            if (vignetteShader == null || vignetteColor != dimColor) {
                vignetteColor = dimColor;
                int rgb = dimColor & 0xffffff;
                vignetteShader = new RadialGradient(hole.centerX(), hole.centerY(), hole.width() * .62f,
                        new int[]{rgb, rgb, rgb | 0xff000000}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
            }
            fill.setColor(Color.WHITE);
            fill.setAlpha(Math.round(255 * v));
            fill.setShader(vignetteShader);
            c.drawRoundRect(hole, HOLE_R, HOLE_R, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        stroke.setStrokeWidth(rimWidth);
        stroke.setColor(rimColor);
        c.drawRoundRect(hole, HOLE_R, HOLE_R, stroke);
    }

    static void drawCenteredText(Canvas c, String text, float x, float y, Paint p) {
        c.drawText(text, x, y - (p.ascent() + p.descent()) / 2f, p);
    }

    static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    /** Clamped smoothstep: 0..1 eased in and out. */
    static float smooth(float v) { v = clamp(v); return v * v * (3 - 2 * v); }
    /** Smoothstep of v as it goes from a to b. */
    static float ramp(float v, float a, float b) { return smooth((v - a) / (b - a)); }
    static int alpha(int color, float amount) {
        return (color & 0xffffff) | (Math.round(Color.alpha(color) * clamp(amount)) << 24);
    }
}
