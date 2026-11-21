// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Canvas;
import android.graphics.Path;

import java.util.Random;

/**
 * Fairy garden (the girls' scene): a little fairy flies along the flower meadow below the video, leaving a
 * trail of sparkles, to her pink toadstool house in the right-hand corner, and goes in at nightfall. A cherry
 * tree on the left, a pink-and-lilac sky, glitter instead of fireflies. The minutes left are written on the
 * big white spot of the toadstool's cap. All drawn in code (SkyScene), no artwork files.
 */
final class FairyScene extends SkyScene {
    private static final int[] SKY_TOP = {0xFF9CC6F4, 0xFF92BEF2, 0xFFAFB6EE, 0xFFF0A6CC, 0xFFDE7EB2, 0xFF7A4A9E, 0xFF2C1C5C, 0xFF140E34};
    private static final int[] SKY_BOT = {0xFFFFE6F2, 0xFFFFDCEE, 0xFFFFDEEC, 0xFFFFD4BC, 0xFFFFB0BE, 0xFFE68ABC, 0xFF583A82, 0xFF22184A};
    private static final int[] CLOUD = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFF4FA, 0xFFFFE4F0, 0xFFF8AACA, 0xFFAA7CBC, 0xFF4A3A74, 0xFF2E2658};
    private static final int[] RIM = {0x66FFFFFF, 0x66FFFFFF, 0x60FFF0F8, 0x66FFE0EE, 0x60FFC4DC, 0x50D8B8FF, 0x40B8A8F0, 0x38A49AE0};
    private static final int[] HILL_BACK = {0xFFA9DDB2, 0xFF9FD6AA, 0xFFA8D0A4, 0xFFC4BE96, 0xFFB48CA2, 0xFF70548E, 0xFF3A2E60, 0xFF221C42};
    private static final int[] HILL_FRONT = {0xFF80CC92, 0xFF78C48A, 0xFF80BC80, 0xFF9EAA76, 0xFF8C6C88, 0xFF503E72, 0xFF2A224A, 0xFF181434};
    private static final int[] PETALS = {0xFFFF8CC0, 0xFFC9A2FF, 0xFFFFFFFF, 0xFFFFB38A, 0xFFFF6FA8};
    private static final float[][] PUFFS = {{0, -140, 46}, {-40, -108, 36}, {40, -112, 38}, {-12, -178, 32}, {34, -160, 30}, {-46, -150, 28}};
    private static final float[][] SPOTS = {{-72, -120, 10}, {-50, -160, 8}, {68, -122, 11}, {48, -164, 8}, {-94, -106, 5}, {95, -108, 5}};
    private static final float[] PETAL_COS = new float[5], PETAL_SIN = new float[5];
    static {
        for (int k = 0; k < 5; k++) {
            double a = k * 2 * Math.PI / 5;
            PETAL_COS[k] = (float) Math.cos(a);
            PETAL_SIN[k] = (float) Math.sin(a);
        }
    }
    private static final float HOUSE_X = 1800, START_X = 150, END_X = 1715, SCALE = 1.6f;

    private final Hill back = new Hill(884, 26, 210, 1.2f, 230, 80, 300, 150, 1850, 320);
    private final Hill front = new Hill(992, 20, 160, 0.3f, 190, 110, 230, 70, 1800, 260);
    private final Path cap = new Path(), dress = new Path(), wing = new Path();
    private final float[] flowers = new float[46 * 4];   // x, y, size, colour index
    private final float[] fairyPos = new float[4], sparklePos = new float[4];
    private final float ground;                            // where the toadstool stands

    FairyScene(Mode mode) {
        super(mode, SKY_TOP, SKY_BOT, CLOUD, RIM, 0xFFFFD6F0);
        ground = front.y(HOUSE_X) + 6;
        // The cap: a dome over the stem.
        r.set(HOUSE_X - 108, ground - 190, HOUSE_X + 108, ground - 10);
        cap.addArc(r, 180, 180);
        cap.close();
        Random rnd = new Random(23);
        for (int i = 0; i < flowers.length; i += 4) {
            float x = rnd.nextFloat() * W;
            if (x > 1690 && x < 1910) x = rnd.nextBoolean() ? 1660 - rnd.nextFloat() * 40 : 1910;   // not on the house
            flowers[i] = x;
            float fy = Math.max(front.y(x) + 14, 940);
            flowers[i + 1] = fy + rnd.nextFloat() * (1068 - fy);
            flowers[i + 2] = 0.7f + rnd.nextFloat() * 0.6f;
            flowers[i + 3] = rnd.nextInt(PETALS.length);
        }
    }

    @Override int tilePanel() { return 0xB04A2C5E; }   // plum
    /** Wings flutter: a little quicker than the sky alone needs. */
    @Override long frameMs() { return mode == Mode.GOODNIGHT ? 50 : 66; }

    @Override void drawLand(Canvas c, float q) {
        float night = ramp(q, 0.8f, 1f);
        int hillBack = key(HILL_BACK, q), hillFront = key(HILL_FRONT, q);
        fill.setColor(hillBack);
        c.drawPath(back.path, fill);
        drawTree(c, 135, night, hillBack, hillFront);
        fill.setColor(hillFront);
        c.drawPath(front.path, fill);
        drawFlowers(c, night, hillFront);
        drawToadstool(c, q, night, hillFront);
        if (mode != Mode.GOODNIGHT) drawJourney(c, q);
    }

    private void drawTree(Canvas c, float x, float night, int hillBack, int hillFront) {
        float base = back.y(x) + 34;
        stroke.setStrokeWidth(16);
        stroke.setColor(Sky.lerpColor(0xFF8A5A4A, hillFront, 0.3f + 0.5f * night));
        tmp.reset();
        tmp.moveTo(x, base + 6);
        tmp.quadTo(x - 6, base - 50, x + 4, base - 96);
        c.drawPath(tmp, stroke);
        stroke.setStrokeWidth(8);
        c.drawLine(x + 2, base - 70, x + 34, base - 104, stroke);
        int blossom = Sky.lerpColor(0xFFFFB5D3, hillBack, 0.15f + 0.6f * night);
        int blossom2 = Sky.lerpColor(0xFFFF97C1, hillBack, 0.15f + 0.6f * night);
        for (int k = 0; k < PUFFS.length; k++) {
            fill.setColor(k % 2 == 0 ? blossom : blossom2);
            c.drawCircle(x + PUFFS[k][0], base + PUFFS[k][1], PUFFS[k][2], fill);
        }
        // A few petals drifting down in the breeze.
        for (int k = 0; k < 4; k++) {
            float f = (t * 0.05f + k / 4f) % 1f;
            fill.setColor(alpha(blossom, (1 - night * 0.6f) * (float) Math.sin(Math.PI * f)));
            c.drawCircle(x - 30 + 70 * f + 12 * (float) Math.sin(t * 0.8f + k * 2), base - 120 + 150 * f, 4, fill);
        }
    }

    private void drawFlowers(Canvas c, float night, int hillFront) {
        int centre = Sky.lerpColor(0xFFFFD84A, hillFront, 0.6f * night);
        for (int i = 0; i < flowers.length; i += 4) {
            float x = flowers[i], y = flowers[i + 1], s = flowers[i + 2] * 6;
            fill.setColor(Sky.lerpColor(PETALS[(int) flowers[i + 3]], hillFront, 0.65f * night));
            for (int k = 0; k < 5; k++) {
                c.drawCircle(x + s * PETAL_COS[k], y + s * PETAL_SIN[k], s * 0.75f, fill);
            }
            fill.setColor(centre);
            c.drawCircle(x, y, s * 0.55f, fill);
        }
    }

    private void drawToadstool(Canvas c, float q, float night, int hillFront) {
        float g = ground, x = HOUSE_X;
        int stem = Sky.lerpColor(0xFFFFF1DE, hillFront, 0.15f + 0.45f * night);
        int capC = Sky.lerpColor(0xFFFF6FA8, hillFront, 0.1f + 0.45f * night);
        int spot = Sky.lerpColor(0xFFFFFFFF, capC, 0.12f + 0.25f * night);
        fill.setColor(stem);
        r.set(x - 32, g - 112, x + 32, g + 4);
        c.drawRoundRect(r, 20, 20, fill);
        // Door and round window: lit from inside at dusk.
        float lit = windowLight(q);
        int dark = Sky.lerpColor(stem, 0xFF6A3A50, 0.55f);
        fill.setColor(Sky.lerpColor(dark, 0xFFFFD08A, lit * 0.8f));
        r.set(x - 13, g - 48, x + 13, g + 2);
        c.drawRoundRect(r, 13, 13, fill);
        fill.setColor(Sky.lerpColor(dark, 0xFFFFD27A, lit));
        c.drawCircle(x, g - 78, 10, fill);
        if (lit > 0) dot(c, x, g - 60, 70, alpha(0xFFFFC8A0, 0.45f * lit));
        // Cap, its frilly underside and its spots; the minutes go on the big spot.
        fill.setColor(Sky.lerpColor(capC, 0xFFFFE6EE, 0.45f));
        r.set(x - 96, g - 110, x + 96, g - 88);
        c.drawOval(r, fill);
        fill.setColor(capC);
        c.drawPath(cap, fill);
        fill.setColor(spot);
        for (float[] s : SPOTS) c.drawCircle(x + s[0], g + s[1], s[2], fill);
        c.drawCircle(x, g - 144, 27, fill);
    }

    @Override void drawMinutes(Canvas c, float q) {
        count.setColor(Sky.lerpColor(0xFFA0306E, 0xFF5A2A58, ramp(q, 0.8f, 1f)));
        drawCenteredText(c, Integer.toString(minutesLeft), HOUSE_X, ground - 144, count);
    }

    // ---------- the fairy ----------

    /** Where she is at progress q: along the meadow, then up to the toadstool's door. Writes {x, y, scale, alpha}. */
    private void fairyAt(float q, float[] out) {
        float u = Math.min(1f, q / 0.92f);
        float x = START_X + (END_X - START_X) * u;
        float y = 1004 + 14 * (float) Math.sin(x / 120f);
        float v = ramp(q, 0.92f, 1f);
        x += (HOUSE_X - x) * v;
        y += (ground - 26 - y) * v;
        out[0] = x; out[1] = y; out[2] = SCALE * (1 - 0.45f * v); out[3] = 1 - ramp(q, 0.975f, 1f);
    }

    private void drawJourney(Canvas c, float q) {
        fairyAt(q, fairyPos);
        if (fairyPos[3] <= 0) return;
        float bob = 5 * (float) Math.sin(t * 2.1f) * (1 - ramp(q, 0.92f, 1f));
        // A trail of sparkles where she's just been.
        for (int k = 1; k <= 9; k++) {
            float pq = q - k * 0.0045f;
            if (pq < 0) break;
            fairyAt(pq, sparklePos);
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 3 + k * 1.7f);
            int col = k % 3 == 0 ? 0xFFFFF4B0 : k % 3 == 1 ? 0xFFFFB8DE : 0xFFFFFFFF;
            float sx = sparklePos[0] - 18 + 6 * (float) Math.sin(k * 2.3f + t), sy = sparklePos[1] - 20 + 8 * (float) Math.cos(k * 1.9f + t * 0.7f);
            dot(c, sx, sy, 12 - k * 0.6f, alpha(col, fairyPos[3] * (1 - k / 10f) * (0.4f + 0.6f * tw)));
        }
        drawFairy(c, fairyPos[0], fairyPos[1] + bob, fairyPos[2], fairyPos[3]);
    }

    /** The fairy, facing right, centred at (x, y) (her waist), at scale s, faded by a. */
    private void drawFairy(Canvas c, float x, float y, float s, float a) {
        int save = c.save();
        c.translate(x, y);
        c.scale(s, s);
        // Wings: two pairs, fluttering from the shoulders.
        float flap = 0.55f + 0.45f * Math.abs((float) Math.sin(t * 7));
        for (int pair = 0; pair < 2; pair++) {
            int wsave = c.save();
            c.translate(-3 - pair * 3, -15);
            c.scale(flap, 1);
            wing.reset();
            r.set(-30, -24, 0, -2); wing.addOval(r, Path.Direction.CW);
            r.set(-24, -4, -1, 12); wing.addOval(r, Path.Direction.CW);
            fill.setColor(alpha(pair == 0 ? 0xFFDDF2FF : 0xFFF2DDFF, a * (pair == 0 ? 0.55f : 0.75f)));
            c.drawPath(wing, fill);
            stroke.setStrokeWidth(1.3f);
            stroke.setColor(alpha(0xFFFFFFFF, a * 0.85f));
            c.drawPath(wing, stroke);
            c.restoreToCount(wsave);
        }
        // Legs, dress, arm and wand.
        int skin = alpha(0xFFFFDCC8, a);
        stroke.setStrokeWidth(3);
        stroke.setColor(skin);
        c.drawLine(-4, 8, -7, 20, stroke);
        c.drawLine(4, 8, 2, 19, stroke);
        dress.reset();
        dress.moveTo(-6, -21);
        dress.lineTo(6, -21);
        dress.lineTo(15, 8);
        dress.quadTo(0, 13, -15, 8);
        dress.close();
        fill.setColor(alpha(0xFFFF79B4, a));
        c.drawPath(dress, fill);
        fill.setColor(alpha(0xFFFFB3D6, a));
        c.drawCircle(0, -20, 4, fill);   // a little bow
        c.drawLine(4, -16, 16, -24, stroke);
        stroke.setStrokeWidth(2);
        stroke.setColor(alpha(0xFFFFF3C4, a));
        c.drawLine(16, -24, 24, -36, stroke);
        star(c, 25, -38, 6.5f, alpha(0xFFFFE27A, a));
        // Head: hair behind, face, ponytail, one eye and a rosy cheek (she's looking where she's going).
        fill.setColor(alpha(0xFFF5C454, a));
        c.drawCircle(-2, -33, 10.5f, fill);
        r.set(-20, -34, -6, -20);
        c.drawOval(r, fill);
        fill.setColor(skin);
        c.drawCircle(1, -30, 9.5f, fill);
        fill.setColor(alpha(0xFFF5C454, a));
        r.set(-8, -41, 9, -32);
        c.drawArc(r, 180, 180, true, fill);   // fringe
        fill.setColor(alpha(0xFF4A3048, a));
        c.drawCircle(5, -30, 1.5f, fill);
        fill.setColor(alpha(0xFFFF8AA8, a * 0.45f));
        c.drawCircle(5, -26, 2.4f, fill);
        c.restoreToCount(save);
        dot(c, x + 25 * s, y - 38 * s, 22, alpha(0xFFFFF0B0, a * (0.5f + 0.3f * (float) Math.sin(t * 4))));
    }

    /** A five-pointed star, radius rad. */
    private void star(Canvas c, float x, float y, float rad, int col) {
        tmp.reset();
        for (int k = 0; k < 10; k++) {
            double ang = -Math.PI / 2 + k * Math.PI / 5;
            float rr = k % 2 == 0 ? rad : rad * 0.45f;
            float px = x + rr * (float) Math.cos(ang), py = y + rr * (float) Math.sin(ang);
            if (k == 0) tmp.moveTo(px, py); else tmp.lineTo(px, py);
        }
        tmp.close();
        fill.setColor(col);
        c.drawPath(tmp, fill);
    }
}
