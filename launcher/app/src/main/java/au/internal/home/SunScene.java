// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.graphics.Canvas;
import android.graphics.Path;

/**
 * Sunset hills (the first scene, v2.4 – v2.8): rolling hills, a house and a tree under the travelling sun
 * (SkyScene). The house's window lights up at dusk. The minutes left ride on the sun, then hang in the sky above
 * the house once it has set.
 */
final class SunScene extends SkyScene {
    private static final int[] SKY_TOP = {0xFF4FA9F0, 0xFF3E9BEA, 0xFF5B9FE0, 0xFFE9A86E, 0xFFDB7A5E, 0xFF5E4686, 0xFF1C2052, 0xFF0A0E2A};
    private static final int[] SKY_BOT = {0xFFBFE6FF, 0xFFA9DDFF, 0xFFCFE3F2, 0xFFFFE0A3, 0xFFFFB070, 0xFFF08A7A, 0xFF4A3A78, 0xFF1A1F45};
    private static final int[] HILL_BACK = {0xFF86CB82, 0xFF7CC47F, 0xFF8BBF72, 0xFFB0A862, 0xFF9A6A68, 0xFF5A4670, 0xFF2C2B50, 0xFF1A1B38};
    private static final int[] HILL_FRONT = {0xFF55AD5C, 0xFF4FA65A, 0xFF5E9F50, 0xFF84904A, 0xFF6E4E5C, 0xFF3E3458, 0xFF1C1C38, 0xFF10112A};
    private static final float HOUSE_X = 1790;

    private final Hill back = new Hill(872, 30, 230, 2f, 240, 60, 300, 262, 1880, 330);
    private final Hill front = new Hill(978, 26, 170, 0.5f, 215, 100, 230, 165, 1810, 250);
    private final Path roof = new Path();

    SunScene(Mode mode) {
        super(mode, SKY_TOP, SKY_BOT);
        float hy = front.y(HOUSE_X);
        roof.moveTo(HOUSE_X - 58, hy - 62);
        roof.lineTo(HOUSE_X, hy - 112);
        roof.lineTo(HOUSE_X + 58, hy - 62);
        roof.close();
    }

    @Override void drawLand(Canvas c, float q) {
        fill.setColor(key(HILL_BACK, q));
        c.drawPath(back.path, fill);
        drawTree(c, 150, q);
        fill.setColor(key(HILL_FRONT, q));
        c.drawPath(front.path, fill);
        drawHouse(c, q);
    }

    private void drawTree(Canvas c, float x, float q) {
        float base = back.y(x) + 30;
        int hillFront = key(HILL_FRONT, q);
        fill.setColor(Sky.lerpColor(hillFront, 0xFF000000, 0.35f));
        c.drawRect(x - 7, base - 70, x + 7, base + 10, fill);
        fill.setColor(Sky.lerpColor(hillFront, key(HILL_BACK, q), 0.3f));
        c.drawCircle(x, base - 100, 46, fill);
        c.drawCircle(x - 32, base - 74, 34, fill);
        c.drawCircle(x + 32, base - 76, 34, fill);
    }

    private void drawHouse(Canvas c, float q) {
        float g = front.y(HOUSE_X) + 8;
        int hillFront = key(HILL_FRONT, q);
        int wall = Sky.lerpColor(0xFFF3E6D2, hillFront, 0.55f + 0.3f * ramp(q, 0.8f, 1f));
        int roofC = Sky.lerpColor(0xFFC8574A, hillFront, 0.45f + 0.35f * ramp(q, 0.8f, 1f));
        fill.setColor(wall);
        c.drawRect(HOUSE_X - 48, g - 66, HOUSE_X + 48, g + 6, fill);
        fill.setColor(roofC);
        c.drawPath(roof, fill);
        c.drawRect(HOUSE_X + 20, g - 112, HOUSE_X + 34, g - 80, fill);   // chimney
        float lit = windowLight(q);
        fill.setColor(Sky.lerpColor(Sky.lerpColor(wall, 0xFF000000, 0.25f), 0xFFFFD27A, lit));
        r.set(HOUSE_X - 30, g - 50, HOUSE_X - 6, g - 26);
        c.drawRoundRect(r, 3, 3, fill);
        if (lit > 0) dot(c, HOUSE_X - 18, g - 38, 60, alpha(0xFFFFC870, 0.45f * lit));
        fill.setColor(Sky.lerpColor(roofC, 0xFF000000, 0.2f));
        c.drawRect(HOUSE_X + 10, g - 38, HOUSE_X + 30, g + 6, fill);   // door
    }
}
