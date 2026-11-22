// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * Background for the app side: a gradient in the theme's colours (Look) that follow the time of day, a soft
 * "sun/moon" glow that drifts across the top through the day, a weather mood (greyer when
 * overcast or raining), Aurora's ribbons of light, a few dp of parallax driven by focus, and fine grain to stop the
 * dark gradient banding on the panel. Nothing animates on its own: it changes once a minute,
 * when the weather updates, or briefly when focus moves.
 */
final class Sky extends Drawable {
    static final float PARALLAX_X_DP = 14f, PARALLAX_Y_DP = 9f;

    // Colour keys (from the Look): gradient top-left, gradient bottom-right, glow colour, glow alpha (0-255), for
    // midnight, late night, first light, sunrise, morning, solar noon, late afternoon, sunset, dark, late evening,
    // midnight. Their times come from the day's sun events (see placeKeys), so dusk is at the real dusk.
    private int[][] colours = Look.TWILIGHT.sky;
    private int[] ribbonColours = new int[0];
    private float ribbonStrength;
    private boolean ribbonsAtNight;
    private final int[] keyAt = new int[colours.length];   // minute of day for each colours row
    private int sunrise, sunset;
    private int overcast = Look.TWILIGHT.overcast;

    private final Paint base = new Paint(), glow = new Paint(), grain = new Paint();
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap cache;
    private boolean dirty = true;
    private Paint[] ribbons = new Paint[0];
    private float night;           // 0 by day .. 1 once it's dark: how bright the ribbons are
    private final Matrix m = new Matrix();
    private int minute = 12 * 60;
    private float mood;            // 0 clear .. 1 overcast/rain
    private float px, py;          // parallax offset, px
    private int top, bottom, glowColor, glowAlpha;
    private float gx, gy;          // glow centre as a fraction of the bounds

    Sky() {
        grain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        placeKeys(5 * 60 + 50, 6 * 60 + 40, 19 * 60, 19 * 60 + 30);   // until setSun: a spring/summer evening
        recompute();
    }

    /** The theme's sky colours and aurora ribbons. */
    void setLook(Look look) {
        colours = look.sky;
        overcast = look.overcast;
        ribbonColours = look.ribbons;
        ribbonStrength = look.ribbonStrength;
        ribbonsAtNight = look.ribbonsAtNight;
        ribbons = new Paint[ribbonColours.length];
        for (int i = 0; i < ribbons.length; i++) ribbons[i] = new Paint();
        recompute(); rebuild(); invalidateSelf();
    }

    /** Minutes since midnight (local). */
    void setTime(int minuteOfDay) {
        if (minuteOfDay == minute) return;
        minute = minuteOfDay; recompute(); rebuild(); invalidateSelf();
    }

    /** Today's sun events, minutes since local midnight: nautical dawn, sunrise, sunset, nautical dusk. */
    void setSun(int dawn, int rise, int set, int dark) {
        if (rise == sunrise && set == sunset && keyAt[2] == dawn && keyAt[8] == dark) return;
        placeKeys(dawn, rise, set, dark); recompute(); rebuild(); invalidateSelf();
    }

    private void placeKeys(int dawn, int rise, int set, int dark) {
        sunrise = rise; sunset = set;
        int noon = (rise + set) / 2, end = 24 * 60;
        int[] at = {0, dawn - 50, dawn, rise, Math.min(rise + 140, noon - 30), noon, Math.max(set - 105, noon + 30), set,
                dark, Math.min(dark + 150, end - 10), end};
        for (int i = 1; i < at.length; i++) at[i] = Math.max(at[i], at[i - 1] + 1);   // keep them in order
        System.arraycopy(at, 0, keyAt, 0, at.length);
    }

    /** Sun times for the local date of `day` at (lat, lon), as minutes since midnight in `day`'s time zone. */
    void setSun(double lat, double lon, java.util.Calendar day) {
        long[] sun = Sun.crossings(lat, lon, day, Sun.SUNSET), dark = Sun.crossings(lat, lon, day, Sun.NAUTICAL);
        if (sun == null || dark == null) return;
        setSun(minuteOf(dark[0], day), minuteOf(sun[0], day), minuteOf(sun[1], day), minuteOf(dark[1], day));
    }

    private static int minuteOf(long ms, java.util.Calendar zone) {
        java.util.Calendar c = (java.util.Calendar) zone.clone();
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /** 0 = clear, 1 = overcast or wet. */
    void setMood(float m) {
        if (m == mood) return;
        mood = m; recompute(); rebuild(); invalidateSelf();
    }

    void setParallax(float x, float y) {
        if (Float.isNaN(x) || Float.isNaN(y)) return;   // a NaN offset makes the gradients draw black
        if (px == x && py == y) return;
        px = x; py = y; place(); dirty = true; invalidateSelf();
    }

    float parallaxX() { return px; }
    float parallaxY() { return py; }

    static float moodFor(int wmoCode) {
        if (wmoCode <= 1) return 0f;
        if (wmoCode == 2) return 0.35f;
        if (wmoCode == 3) return 0.7f;
        return 1f;   // fog, drizzle, rain, snow, storms
    }

    private void recompute() {
        int i = 0;
        while (i < keyAt.length - 2 && keyAt[i + 1] <= minute) i++;
        int[] a = colours[i], b = colours[i + 1];
        float t = Scene.smooth((minute - keyAt[i]) / (float) (keyAt[i + 1] - keyAt[i]));
        top = lerpColor(a[0], b[0], t);
        bottom = lerpColor(a[1], b[1], t);
        glowColor = lerpColor(a[2], b[2], t);
        glowAlpha = Math.round(a[3] + (b[3] - a[3]) * t);
        if (mood > 0) {
            glowColor = lerpColor(glowColor, overcast, 0.7f * mood);
            glowAlpha = Math.round(glowAlpha * (1f - 0.35f * mood));
            top = lerpColor(top, grey(top), 0.55f * mood);
        }
        // Night: up from sunset to dark, down from first light to sunrise.
        int dawn = keyAt[2], dark = keyAt[8];
        if (minute >= dark || minute < dawn) night = 1f;
        else if (minute >= sunset) night = Scene.smooth((minute - sunset) / (float) Math.max(1, dark - sunset));
        else if (minute < sunrise) night = 1f - Scene.smooth((minute - dawn) / (float) Math.max(1, sunrise - dawn));
        else night = 0f;
        // The sun tracks left to right from sunrise to sunset, highest at solar noon; at night a moon sits top-right.
        float f = (minute - sunrise) / (float) Math.max(1, sunset - sunrise);
        if (f >= 0 && f <= 1) {
            gx = 0.08f + 0.84f * f;
            gy = 0.10f - 0.16f * (float) Math.sin(Math.PI * f);
        } else { gx = 0.86f; gy = -0.04f; }
    }

    @Override protected void onBoundsChange(Rect b) { rebuild(); }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float w = b.width(), h = b.height();
        base.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
        int c = (glowColor & 0xFFFFFF) | (glowAlpha << 24);
        glow.setShader(new RadialGradient(0, 0, Math.max(w, h) * 0.85f,
                new int[]{c, (c & 0xFFFFFF) | ((glowAlpha / 3) << 24), c & 0xFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        // Ribbons: long soft ellipses of light, strongest at night (aurora) or by day (sun shafts), dimmed by cloud.
        float when = ribbonsAtNight ? night : 1f - night;
        float strength = ribbonStrength * (0.25f + 0.75f * when) * (1f - 0.6f * mood);
        for (int i = 0; i < ribbons.length; i++) {
            int a = Math.min(255, Math.round(RIBBONS[i % RIBBONS.length][5] * strength));
            int col = ribbonColours[i] & 0xFFFFFF;
            ribbons[i].setShader(new RadialGradient(0, 0, w * RIBBONS[i % RIBBONS.length][2],
                    new int[]{col | (a << 24), col | ((a * 2 / 5) << 24), col}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        place();
        dirty = true;
    }

    private void place() {
        Rect b = getBounds();
        if (base.getShader() == null) return;
        m.setTranslate(b.left + px * 0.4f, b.top + py * 0.4f);
        base.getShader().setLocalMatrix(m);
        m.setTranslate(b.left + gx * b.width() + px, b.top + gy * b.height() + py);
        glow.getShader().setLocalMatrix(m);
        // Ribbons sit further back than the glow but nearer than the gradient, and sway a little through the day.
        float day = (float) (2 * Math.PI * minute / (24 * 60));
        for (int i = 0; i < ribbons.length; i++) {
            float[] r = RIBBONS[i % RIBBONS.length];
            if (ribbons[i].getShader() == null) continue;
            m.setScale(1f, r[3]);
            m.postRotate(r[4] + 4f * (float) Math.sin(day + i * 2.1f));
            m.postTranslate(b.left + (r[0] + 0.03f * (float) Math.sin(day * 2 + i)) * b.width() + px * 0.7f,
                    b.top + r[1] * b.height() + py * 0.7f);
            ribbons[i].getShader().setLocalMatrix(m);
        }
    }

    /** Aurora ribbons: centre x, y (fractions of the bounds), radius (of the width), squash, tilt (deg), peak alpha. */
    private static final float[][] RIBBONS = {
            {0.30f, 0.12f, 0.62f, 0.20f, -11f, 92f},
            {0.66f, 0.04f, 0.52f, 0.17f, 9f, 80f},
            {0.90f, 0.22f, 0.42f, 0.24f, -20f, 52f},
    };

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int cw = Math.max(1, b.width() / 2), ch = Math.max(1, b.height() / 2);
        if (dirty || cache == null || cache.getWidth() != cw || cache.getHeight() != ch) {
            if (cache == null || cache.getWidth() != cw || cache.getHeight() != ch) {
                if (cache != null) cache.recycle();
                cache = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            }
            Canvas cc = new Canvas(cache);
            cc.scale(cw / (float) b.width(), ch / (float) b.height());
            cc.translate(-b.left, -b.top);
            cc.drawRect(b, base);
            for (Paint r : ribbons) cc.drawRect(b, r);
            cc.drawRect(b, glow);
            cc.drawRect(b, grain);
            dirty = false;
        }
        c.drawBitmap(cache, null, b, blit);
    }

    /** Signed grain: tiny light and dark specks averaging out to no change in brightness. */
    private Bitmap noise() {
        int n = 128;
        int[] px = new int[n * n];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            double g = r.nextGaussian();
            int a = (int) Math.min(34, Math.abs(g) * 9);
            px[i] = g > 0 ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }


    private static int grey(int c) {
        int l = (int) (0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c));
        return Color.rgb(l, l, Math.min(255, l + 4));
    }

    static int lerpColor(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
