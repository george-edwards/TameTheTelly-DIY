// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Canvas;
import android.graphics.Path;

/**
 * Steam train (the boys' scene): a little red steam engine chuffs along the track below the video, past a
 * signal, to its engine shed in the right-hand corner, and goes in for the night as it gets dark: the signal
 * turns red, the steam stops, the shed's windows light up. Snowy mountains and pine trees at the sides. The
 * minutes left are on the round plaque on the shed. All drawn in code (SkyScene), no artwork files.
 */
final class TrainScene extends SkyScene {
    private static final int[] SKY_TOP = {0xFF3D8FE8, 0xFF2F86E2, 0xFF4E92D8, 0xFFE6A468, 0xFFD87048, 0xFF4E3E78, 0xFF152048, 0xFF080D24};
    private static final int[] SKY_BOT = {0xFFB4DEFF, 0xFFA2D6FF, 0xFFC8E0F0, 0xFFFFD898, 0xFFFFA060, 0xFFE07E66, 0xFF3A3C70, 0xFF141A3A};
    private static final int[] MOUNTAIN = {0xFF8DA6CC, 0xFF85A0C8, 0xFF93A3C0, 0xFFB49CA4, 0xFF8E6C88, 0xFF4C4270, 0xFF262A4E, 0xFF161A34};
    private static final int[] HILL_BACK = {0xFF6FBF6A, 0xFF66B866, 0xFF74B060, 0xFF9AA058, 0xFF876060, 0xFF4E4068, 0xFF262848, 0xFF171932};
    private static final int[] HILL_FRONT = {0xFF4E9E48, 0xFF489846, 0xFF56903E, 0xFF7A8040, 0xFF664856, 0xFF383050, 0xFF1A1A36, 0xFF0F1028};
    /** Rail top, and the engine's front at the start, at the signal (p 0.9) and parked in the shed. */
    private static final float RAIL = 1042, START = 380, SIGNAL = 1600, BY_NIGHT = 1660, PARKED = 1925;
    private static final float[][] PEAKS = {{70, 600}, {230, 560}, {1700, 570}, {1870, 540}};
    private static final float[] PINES = {40, 105, 190, 1760, 1880};
    private static final float[] SHED_WINDOWS = {1818, 1868};

    private final Hill back = new Hill(905, 22, 200, 1.0f, 150, 120, 260, 120, 1820, 280);
    private final Path mountains = new Path(), snow = new Path(), front = new Path(),
            wall = new Path(), arch = new Path(), roof = new Path();

    TrainScene(Mode mode) {
        super(mode, SKY_TOP, SKY_BOT);
        // Mountains at the sides (the middle is behind the video anyway), with snowy tops.
        mountains.moveTo(0, H);
        mountains.lineTo(0, 700);
        float[][] line = {{70, 600}, {150, 690}, {230, 560}, {420, 700}, {560, 620}, {700, 720}, {860, 650}, {1020, 730}, {1180, 640}, {1340, 720}, {1480, 660}, {1580, 740}, {1700, 570}, {1780, 660}, {1870, 540}, {1920, 610}};
        for (float[] pt : line) mountains.lineTo(pt[0], pt[1]);
        mountains.lineTo(W, H);
        mountains.close();
        for (float[] pk : PEAKS) {
            snow.moveTo(pk[0], pk[1]);
            snow.lineTo(pk[0] + 34, pk[1] + 42);
            snow.lineTo(pk[0] + 14, pk[1] + 34);
            snow.lineTo(pk[0], pk[1] + 46);
            snow.lineTo(pk[0] - 14, pk[1] + 34);
            snow.lineTo(pk[0] - 34, pk[1] + 42);
            snow.close();
        }
        front.addRect(0, 1030, W, H, Path.Direction.CW);
        // The engine shed: brick wall with an arched doorway the track runs into, slate roof.
        wall.addRect(1700, 892, W, RAIL + 4, Path.Direction.CW);
        r.set(1712, 950, 1794, RAIL + 60);
        arch.addRoundRect(r, 41, 41, Path.Direction.CW);
        wall.op(arch, Path.Op.DIFFERENCE);
        roof.moveTo(1688, 896);
        roof.lineTo(1726, 850);
        roof.lineTo(W, 850);
        roof.lineTo(W, 896);
        roof.close();
    }

    /** Wheels and steam move: a little quicker than the sky alone needs. */
    @Override long frameMs() { return mode == Mode.GOODNIGHT ? 50 : 66; }

    /** The engine's front at progress q: along the track to the signal by nightfall, then into the shed. */
    static float frontAt(float q) {
        return START + (BY_NIGHT - START) * Math.min(1f, Math.max(0f, q) / 0.9f) + (PARKED - BY_NIGHT) * ramp(q, 0.9f, 1f);
    }

    @Override void drawLand(Canvas c, float q) {
        float night = ramp(q, 0.8f, 1f);
        int mountain = key(MOUNTAIN, q), hillFront = key(HILL_FRONT, q);
        fill.setColor(mountain);
        c.drawPath(mountains, fill);
        fill.setColor(Sky.lerpColor(0xFFF4F8FF, mountain, 0.15f + 0.55f * night));
        c.drawPath(snow, fill);
        fill.setColor(key(HILL_BACK, q));
        c.drawPath(back.path, fill);
        for (float x : PINES) pine(c, x, x > 1000 ? 860 : 930, x == 105 ? 1.25f : 1f, night, hillFront);
        fill.setColor(hillFront);
        c.drawPath(front, fill);
        drawTrack(c, night, hillFront);
        drawSignal(c, q);

        // Inside the shed, then the engine, then the shed's front wall over whatever's gone in.
        fill.setColor(Sky.lerpColor(0xFF3A2C26, 0xFF120E14, night));
        c.drawRect(1712, 950, 1794, RAIL + 4, fill);
        float steam = mode == Mode.GOODNIGHT ? 0 : 1 - ramp(q, 0.94f, 0.99f);
        drawEngine(c, mode == Mode.GOODNIGHT ? PARKED : frontAt(q), night, steam);
        drawShed(c, q, night, hillFront);
    }

    private void pine(Canvas c, float x, float base, float s, float night, int hillFront) {
        fill.setColor(Sky.lerpColor(0xFF5A3E2C, hillFront, 0.4f + 0.4f * night));
        c.drawRect(x - 5 * s, base - 22 * s, x + 5 * s, base, fill);
        fill.setColor(Sky.lerpColor(0xFF2F7A46, hillFront, 0.2f + 0.5f * night));
        for (int k = 0; k < 3; k++) {
            float top = base - (70 + k * 34) * s, w = (40 - k * 9) * s;
            tmp.reset();
            tmp.moveTo(x, top);
            tmp.lineTo(x + w, top + 60 * s);
            tmp.lineTo(x - w, top + 60 * s);
            tmp.close();
            c.drawPath(tmp, fill);
        }
    }

    private void drawTrack(Canvas c, float night, int hillFront) {
        fill.setColor(Sky.lerpColor(0xFF9A8C7C, hillFront, 0.6f * night));
        c.drawRect(0, RAIL + 6, W, RAIL + 22, fill);
        fill.setColor(Sky.lerpColor(0xFF5B4030, hillFront, 0.5f * night));
        for (float x = 6; x < W; x += 30) c.drawRect(x, RAIL + 3, x + 16, RAIL + 13, fill);
        fill.setColor(Sky.lerpColor(0xFF6E7078, hillFront, 0.4f * night));
        c.drawRect(0, RAIL, W, RAIL + 5, fill);
        fill.setColor(alpha(0xFFFFFFFF, 0.35f * (1 - night)));
        c.drawRect(0, RAIL, W, RAIL + 1.5f, fill);
    }

    /** Green by day; red for the night once the engine has gone by. */
    private void drawSignal(Canvas c, float q) {
        float red = mode == Mode.GOODNIGHT ? 1 : ramp(q, 0.88f, 0.92f);
        fill.setColor(0xFF3A3A40);
        c.drawRect(SIGNAL - 3, 952, SIGNAL + 3, RAIL, fill);
        r.set(SIGNAL - 11, 920, SIGNAL + 11, 968);
        c.drawRoundRect(r, 6, 6, fill);
        fill.setColor(Sky.lerpColor(0xFF4A1A1A, 0xFFFF4A3A, red));
        c.drawCircle(SIGNAL, 933, 6, fill);
        fill.setColor(Sky.lerpColor(0xFF4AFF7A, 0xFF1A3A22, red));
        c.drawCircle(SIGNAL, 955, 6, fill);
        dot(c, SIGNAL, red > 0.5f ? 933 : 955, 30, alpha(red > 0.5f ? 0xFFFF6A50 : 0xFF6AFF90, 0.35f + 0.3f * ramp(q, 0.75f, 0.9f)));
    }

    /** The engine and its tender, facing right, front buffer at fx. */
    private void drawEngine(Canvas c, float fx, float night, float steam) {
        int dim = 0xFF0A0C1C;
        float shade = 0.45f * night;
        // Tender with coal.
        fill.setColor(Sky.lerpColor(0xFF2C5BA6, dim, shade));
        c.drawRect(fx - 224, 990, fx - 178, 1030, fill);
        fill.setColor(Sky.lerpColor(0xFF22242A, dim, shade));
        for (int k = 0; k < 3; k++) c.drawCircle(fx - 214 + k * 13, 990, 8, fill);
        // Cab, roof and window (lit at night).
        fill.setColor(Sky.lerpColor(0xFF2C5BA6, dim, shade));
        c.drawRect(fx - 174, 950, fx - 112, 1030, fill);
        fill.setColor(Sky.lerpColor(0xFF23262E, dim, shade));
        c.drawRect(fx - 180, 942, fx - 106, 952, fill);
        fill.setColor(Sky.lerpColor(Sky.lerpColor(0xFFBFE3FF, 0xFF2A3050, night), 0xFFFFCF70, night * steam));
        r.set(fx - 162, 962, fx - 126, 990);
        c.drawRoundRect(r, 5, 5, fill);
        // Boiler with brass bands, dome, chimney, smokebox.
        fill.setColor(Sky.lerpColor(0xFFD8352C, dim, shade));
        r.set(fx - 116, 978, fx - 18, 1024);
        c.drawRoundRect(r, 10, 10, fill);
        fill.setColor(Sky.lerpColor(0xFFF2B636, dim, shade));
        c.drawRect(fx - 92, 978, fx - 86, 1024, fill);
        c.drawRect(fx - 56, 978, fx - 50, 1024, fill);
        r.set(fx - 84, 966, fx - 60, 990);
        c.drawArc(r, 180, 180, true, fill);
        fill.setColor(Sky.lerpColor(0xFF2E2F36, dim, shade));
        c.drawRect(fx - 44, 950, fx - 30, 980, fill);
        c.drawRect(fx - 48, 943, fx - 26, 952, fill);
        r.set(fx - 26, 974, fx - 4, 1027);
        c.drawRoundRect(r, 6, 6, fill);
        // Footplate, red buffer beam, buffers.
        fill.setColor(Sky.lerpColor(0xFF23262E, dim, shade));
        c.drawRect(fx - 180, 1024, fx - 2, 1032, fill);
        fill.setColor(Sky.lerpColor(0xFFD8352C, dim, shade));
        c.drawRect(fx - 5, 1016, fx + 4, 1032, fill);
        fill.setColor(Sky.lerpColor(0xFF2E2F36, dim, shade));
        c.drawRect(fx + 4, 1019, fx + 9, 1023, fill);
        c.drawRect(fx + 4, 1026, fx + 9, 1030, fill);
        // Wheels: three big drivers turning with a coupling rod, a small front wheel, two under the tender.
        float turn = t * 2.4f * steam;
        for (int k = 0; k < 3; k++) wheel(c, fx - 152 + k * 42, RAIL - 17, 17, shade);
        wheel(c, fx - 30, RAIL - 10, 10, shade);
        wheel(c, fx - 212, RAIL - 10, 10, shade);
        wheel(c, fx - 190, RAIL - 10, 10, shade);
        float px = 8 * (float) Math.cos(turn), py = 8 * (float) Math.sin(turn);
        stroke.setStrokeWidth(4);
        stroke.setColor(Sky.lerpColor(0xFFD7DCE4, dim, shade));
        c.drawLine(fx - 152 + px, RAIL - 17 + py, fx - 68 + px, RAIL - 17 + py, stroke);
        // Headlamp, lit as it gets dark.
        fill.setColor(Sky.lerpColor(0xFFE8E8E0, 0xFFFFE9A0, night));
        c.drawCircle(fx - 12, 966, 5, fill);
        if (night > 0) dot(c, fx + 10, 970, 80, alpha(0xFFFFE6A0, 0.5f * night * steam));
        // Steam puffs drift back from the chimney (they stop once it's parked).
        if (steam > 0) {
            int puff = Sky.lerpColor(0xFFFFFFFF, 0xFF9098B8, night);
            for (int k = 0; k < 6; k++) {
                float f = (t * 0.4f + k / 6f) % 1f;
                float x = fx - 37 - f * 150, y = 938 - 24 * (float) Math.sin(f * Math.PI / 2);
                fill.setColor(alpha(puff, steam * 0.75f * (1 - f)));
                c.drawCircle(x, y, 7 + 18 * f, fill);
            }
        }
    }

    private void wheel(Canvas c, float x, float y, float rad, float shade) {
        fill.setColor(Sky.lerpColor(0xFF24262C, 0xFF0A0C1C, shade));
        c.drawCircle(x, y, rad, fill);
        fill.setColor(Sky.lerpColor(0xFFC8302A, 0xFF0A0C1C, shade));
        c.drawCircle(x, y, rad * 0.4f, fill);
    }

    private void drawShed(Canvas c, float q, float night, int hillFront) {
        fill.setColor(Sky.lerpColor(0xFFB5543E, hillFront, 0.2f + 0.45f * night));
        c.drawPath(wall, fill);
        stroke.setStrokeWidth(6);
        stroke.setColor(Sky.lerpColor(0xFFE6D2B0, hillFront, 0.2f + 0.45f * night));
        c.save();
        c.clipRect(1700, 892, W, RAIL + 4);
        c.drawPath(arch, stroke);
        c.restore();
        fill.setColor(Sky.lerpColor(0xFF474C5C, hillFront, 0.2f + 0.4f * night));
        c.drawPath(roof, fill);
        // Windows: lit from inside at dusk, out after goodnight.
        float lit = windowLight(q);
        fill.setColor(Sky.lerpColor(0xFF39404E, 0xFFFFD27A, lit));
        for (float x : SHED_WINDOWS) {
            r.set(x, 968, x + 30, 998);
            c.drawRoundRect(r, 4, 4, fill);
        }
        if (lit > 0) dot(c, 1858, 984, 90, alpha(0xFFFFC870, 0.4f * lit));
        // The round plaque the minutes go on.
        fill.setColor(Sky.lerpColor(0xFFFFF4DE, 0xFFB8B0C8, 0.5f * night));
        c.drawCircle(1858, 924, 25, fill);
        stroke.setStrokeWidth(3);
        stroke.setColor(Sky.lerpColor(0xFF3A2A22, 0xFF22202C, night));
        c.drawCircle(1858, 924, 25, stroke);
    }

    @Override void drawMinutes(Canvas c, float q) {
        count.setColor(0xFF3A2A22);
        drawCenteredText(c, Integer.toString(minutesLeft), 1858, 924, count);
    }
}
