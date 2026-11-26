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

    /**
     * Subsolar point {declinationDeg, subsolarLonDeg} at `epochMs` (UTC).
     * Good to ~0.3°, plenty for a live world day/night terminator plot.
     */
    static double[] subsolar(long epochMs) {
        double jd = epochMs / 86_400_000.0 + 2440587.5;
        double n = jd - 2451545.0;
        double l = (280.460 + 0.9856474 * n) % 360.0;
        if (l < 0) l += 360.0;
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
        double lambda = Math.toRadians(l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double declDeg = Math.toDegrees(Math.asin(Math.sin(eps) * Math.sin(lambda)));

        // Greenwich Mean Sidereal Time / Equation of Time approximation via UTC hours
        double utcHours = ((epochMs % 86_400_000L) + 86_400_000L) % 86_400_000L / 3_600_000.0;
        double ra = Math.toDegrees(Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)));
        double eotDeg = ((l - ra + 540.0) % 360.0) - 180.0;
        double subLon = 180.0 - utcHours * 15.0 - eotDeg;
        subLon = ((subLon + 540.0) % 360.0) - 180.0;
        return new double[]{declDeg, subLon};
    }

    /** Solar altitude in degrees [-90, +90] at (latDeg, lonDeg) given subsolar (declDeg, subLonDeg). */
    static double altitude(double latDeg, double lonDeg, double declDeg, double subLonDeg) {
        double lat = Math.toRadians(latDeg);
        double decl = Math.toRadians(declDeg);
        double dLon = Math.toRadians(lonDeg - subLonDeg);
        double sinAlt = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(dLon);
        if (sinAlt > 1.0) sinAlt = 1.0;
        else if (sinAlt < -1.0) sinAlt = -1.0;
        return Math.toDegrees(Math.asin(sinAlt));
    }
}
