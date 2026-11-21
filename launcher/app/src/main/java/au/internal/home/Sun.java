// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise/sunset and twilight times, computed on the TV (no network) with the standard
 * sunrise equation; good to a minute or two, which is plenty for dimming a screen.
 */
final class Sun {
    static final double SUNSET = -0.833;   // upper limb on the horizon, with refraction
    static final double NAUTICAL = -12;    // "properly dark": horizon no longer visible, sky near black

    /** Morning and evening times (epoch ms) when the sun crosses `altitude` degrees on the local date of `day`,
     *  or null if it never does that day (polar; not an issue in NSW). */
    static long[] crossings(double lat, double lon, Calendar day, double altitude) {
        Calendar d = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        d.clear();
        d.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        double n = Math.floorDiv(d.getTimeInMillis(), 86_400_000L) - 10_957;      // days since 2000-01-01
        double jStar = n - lon / 360;
        double m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360);
        double c = 1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m);
        double lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372) % 360);
        double transit = 2451545.0 + jStar + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * lambda);
        double sinDec = Math.sin(lambda) * Math.sin(Math.toRadians(23.4397));
        double cosDec = Math.cos(Math.asin(sinDec));
        double phi = Math.toRadians(lat);
        double cosW = (Math.sin(Math.toRadians(altitude)) - Math.sin(phi) * sinDec) / (Math.cos(phi) * cosDec);
        if (cosW < -1 || cosW > 1) return null;
        double w = Math.toDegrees(Math.acos(cosW)) / 360;
        return new long[]{toMillis(transit - w), toMillis(transit + w)};
    }

    private static long toMillis(double julian) {
        return Math.round((julian - 2440587.5) * 86_400_000L);
    }
}
