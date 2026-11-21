// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Bin night: two of the three bins go out each week. One goes out every week (green at Lake Macquarie,
 * red in Newcastle); the other two take turns. Which week is which is anchored on one known bin night
 * when the first of the pair went out (pair()[0]), so it only needs changing if the council shifts the
 * schedule.
 */
final class Bins {
    static final String GREEN = "green", YELLOW = "yellow", RED = "red";
    static final String[] ALL = {GREEN, RED, YELLOW};

    private Bins() { }

    static boolean valid(String bin) { return GREEN.equals(bin) || YELLOW.equals(bin) || RED.equals(bin); }

    /** The two bins that take turns when `every` goes out every week. The anchor date is a night pair[0] went out. */
    static String[] pair(String every) {
        if (YELLOW.equals(every)) return new String[]{GREEN, RED};
        if (RED.equals(every)) return new String[]{YELLOW, GREEN};
        return new String[]{YELLOW, RED};
    }

    /** "Green (garden)" etc. */
    static String label(String bin) {
        switch (bin) {
            case YELLOW: return "Yellow (recycling)";
            case RED: return "Red (rubbish)";
            default: return "Green (garden)";
        }
    }

    static String name(String bin) {
        switch (bin) {
            case YELLOW: return "recycling";
            case RED: return "rubbish";
            default: return "garden";
        }
    }

    /** The two bins going out if `now` falls on bin night ({every week, this week's turn}), else null. */
    static String[] tonight(Calendar now, int dayOfWeek, String anchor, String every) {
        if (now.get(Calendar.DAY_OF_WEEK) != dayOfWeek) return null;
        String[] p = anchor.split("-");
        Calendar a = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        a.clear();
        a.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        Calendar d = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        d.clear();
        d.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        long weeks = Math.floorDiv(d.getTimeInMillis() - a.getTimeInMillis(), 7L * 24 * 3600 * 1000);
        return new String[]{every, pair(every)[(int) Math.floorMod(weeks, 2L)]};
    }

    private static int drawable(String bin) {
        switch (bin) {
            case YELLOW: return R.drawable.bin_yellow;
            case RED: return R.drawable.bin_red;
            default: return R.drawable.bin_green;
        }
    }

    /** Show or hide the header's bin line (R.id.bins); `out` is tonight()'s answer. */
    static void show(View bins, String[] out) {
        bins.setVisibility(out == null ? View.GONE : View.VISIBLE);
        if (out == null) return;
        ((ImageView) bins.findViewById(R.id.bin1)).setImageResource(drawable(out[0]));
        ((ImageView) bins.findViewById(R.id.bin2)).setImageResource(drawable(out[1]));
        ((TextView) bins.findViewById(R.id.binsText)).setText("Bin night");
        bins.setContentDescription("Bin night: " + name(out[0]) + " and " + name(out[1]));
    }
}
