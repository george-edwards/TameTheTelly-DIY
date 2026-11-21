// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Australian postcode -> its suburbs, for Config > Weather. Built into the app (assets/au_postcodes.tsv, from
 * GeoNames, CC BY 4.0; rebuilt by launcher/postcodes.sh), so it works offline and only ever finds Australian places.
 */
final class Postcodes {
    private Postcodes() { }

    static boolean valid(String s) { return s != null && s.matches("\\d{4}"); }

    /** The suburbs with that postcode, A to Z (empty if none). Reads ~700 KB: call off the main thread. */
    static List<Weather.Place> find(Context c, String postcode) throws IOException {
        List<Weather.Place> out = new ArrayList<>();
        String prefix = postcode + "\t";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open("au_postcodes.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.startsWith(prefix)) { if (!out.isEmpty()) break; continue; }   // sorted by postcode
                String[] f = line.split("\t");
                if (f.length < 5) continue;
                out.add(new Weather.Place(f[1], f[1] + ", " + f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                        timeZone(postcode, f[2])));
            }
        }
        return out;
    }

    /** The state's time zone, with the two NSW places that keep their own: Broken Hill and Lord Howe Island. */
    static String timeZone(String postcode, String state) {
        if (postcode.equals("2880")) return "Australia/Broken_Hill";
        if (postcode.equals("2898")) return "Australia/Lord_Howe";
        switch (state) {
            case "VIC": return "Australia/Melbourne";
            case "QLD": return "Australia/Brisbane";
            case "SA": return "Australia/Adelaide";
            case "WA": return "Australia/Perth";
            case "TAS": return "Australia/Hobart";
            case "NT": return "Australia/Darwin";
            default: return "Australia/Sydney";   // NSW, ACT, Jervis Bay
        }
    }
}
