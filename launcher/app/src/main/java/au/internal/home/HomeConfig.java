// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

/**
 * This household's settings. config.xml holds the defaults; anything changed on the TV (top-corner menus, or adb
 * SET in DevReceiver) is saved here and wins over config.xml. Settings > "Reset these options to default" clears
 * the saved values, so config.xml applies again.
 */
final class HomeConfig {
    /** The bedtime scenes, by the id stored in settings (and in config.xml's <scene>). */
    static final String SCENE_SUN = "sun", SCENE_FAIRY = "fairy", SCENE_TRAIN = "train";
    static final String[] SCENES = {SCENE_SUN, SCENE_FAIRY, SCENE_TRAIN};
    static final String[] SCENE_NAMES = {"Sunset hills", "Fairy garden", "Steam train"};

    // Keys, also accepted by `adb ... SET --es key <key> --es value <value>`.
    static final String K_SCENE = "scene", K_CODE = "parent_code",
            K_MINUTES = "yt_minutes", K_FAMILY = "family_name",
            K_PLACE = "weather_place", K_POSTCODE = "weather_postcode", K_LAT = "weather_lat", K_LON = "weather_lon", K_TZ = "weather_tz",
            K_BINS_DAY = "bins_day", K_BINS_ANCHOR = "bins_recycling_on", K_BINS_EVERY = "bins_every_week",
            K_THEME = "theme", K_CAMERAS = "cameras", K_TIMED = "timed_apps",
            K_ADULT_START = "adult_start", K_ADULT_END = "adult_end",
            K_OVERLAY_CORNER = "overlay_corner", K_OVERLAY_SIZE = "overlay_size";
    static final String CORNER_TR = "top_right", CORNER_TL = "top_left", CORNER_BR = "bottom_right", CORNER_BL = "bottom_left";
    static final String[] OVERLAY_CORNERS = {CORNER_TR, CORNER_TL, CORNER_BR, CORNER_BL};
    static final String[] OVERLAY_CORNER_NAMES = {"Top right", "Top left", "Bottom right", "Bottom left"};
    static final String OVERLAY_SMALL = "small", OVERLAY_MEDIUM = "medium", OVERLAY_LARGE = "large";
    static final String[] OVERLAY_SIZES = {OVERLAY_SMALL, OVERLAY_MEDIUM, OVERLAY_LARGE};
    static final String[] OVERLAY_SIZE_NAMES = {"Small", "Medium", "Large"};
    /** Timed apps: every app on Home (TimedApps). */
    static final String TIMED_ALL = "all";
    /** Camera keys: K_CAM_NAME + "1" etc. A blank SD address hides that camera. */
    static final String K_CAM_NAME = "cam_name", K_CAM_URL = "cam_url", K_CAM_HD = "cam_url_hd";
    static final int CAMERAS = 2;

    private static final Object UNSET = new Object();

    private final SharedPreferences p;
    private final Resources r;
    private Object lastStartRaw = UNSET, lastEndRaw = UNSET;
    private int cachedStartMin = -1, cachedEndMin = -1;

    HomeConfig(Context c) {
        p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        r = c.getResources();
    }

    private String str(String key, int def) {
        String v = p.getString(key, null);
        return v != null ? v : r.getString(def);
    }
    private int num(String key, int def) { return p.getInt(key, r.getInteger(def)); }

    /** Home's theme (Look). */
    Look look() { return Look.byId(str(K_THEME, R.string.theme)); }
    /** Cameras shown on Home at all ("on"/"off"); off gives the apps the whole screen, addresses kept. */
    boolean camerasOn() { return !str(K_CAMERAS, R.string.cameras).equals("off"); }
    String overlayCorner() { return oneOf(str(K_OVERLAY_CORNER, R.string.overlay_corner), OVERLAY_CORNERS, CORNER_TR); }
    String overlaySize() { return oneOf(str(K_OVERLAY_SIZE, R.string.overlay_size), OVERLAY_SIZES, OVERLAY_MEDIUM); }
    static String overlayCornerName(String id) { int i = indexOf(OVERLAY_CORNERS, id); return i < 0 ? id : OVERLAY_CORNER_NAMES[i]; }
    static String overlaySizeName(String id) { int i = indexOf(OVERLAY_SIZES, id); return i < 0 ? id : OVERLAY_SIZE_NAMES[i]; }
    String scene() { return oneOf(str(K_SCENE, R.string.scene), SCENES, SCENE_SUN); }
    /** Arrow code, U/D/L/R. */
    String parentCode() {
        String s = str(K_CODE, R.string.parent_code);
        return validCode(s) ? s : r.getString(R.string.parent_code);
    }
    /** "all", or the timed apps' packages separated by commas. */
    String timedApps() { return str(K_TIMED, R.string.timed_apps).trim(); }
    boolean timedAll() { return timedApps().equals(TIMED_ALL); }
    java.util.Set<String> timedList() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (!timedAll()) for (String s : timedApps().split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
    int ytMinutes() { return Math.max(1, num(K_MINUTES, R.integer.yt_minutes)); }
    int adultStartMin() {
        String raw = p.getString(K_ADULT_START, null);
        if (raw != lastStartRaw) {
            lastStartRaw = raw;
            cachedStartMin = parseTimeMin(raw != null ? raw : r.getString(R.string.adult_start));
        }
        return cachedStartMin;
    }
    int adultEndMin() {
        String raw = p.getString(K_ADULT_END, null);
        if (raw != lastEndRaw) {
            lastEndRaw = raw;
            cachedEndMin = parseTimeMin(raw != null ? raw : r.getString(R.string.adult_end));
        }
        return cachedEndMin;
    }
    static boolean validWindow(int startMin, int endMin) { return startMin >= 0 && endMin >= 0 && startMin != endMin; }
    /** Formatted schedule range (e.g. "7:30 pm – 11:00 pm"), or null when off. */
    String adultScheduleText() {
        int s = adultStartMin(), e = adultEndMin();
        return validWindow(s, e) ? formatTime12(s) + " – " + formatTime12(e) : null;
    }
    void setAdultSchedule(String start, String end) {
        p.edit().putString(K_ADULT_START, start == null ? "" : start.trim())
                .putString(K_ADULT_END, end == null ? "" : end.trim()).apply();
    }
    String familyName() { return str(K_FAMILY, R.string.family_name); }
    String place() { return str(K_PLACE, R.string.weather_place); }
    /** The postcode the place was picked from (Settings > Weather starts there). */
    String postcode() { String s = str(K_POSTCODE, R.string.weather_postcode); return Postcodes.valid(s) ? s : r.getString(R.string.weather_postcode); }
    double lat() { return parse(str(K_LAT, R.string.weather_lat), R.string.weather_lat); }
    double lon() { return parse(str(K_LON, R.string.weather_lon), R.string.weather_lon); }
    String tz() { return str(K_TZ, R.string.weather_tz); }
    /** Calendar.DAY_OF_WEEK of bin night, or 0 for none. */
    int binsDay() { return num(K_BINS_DAY, R.integer.bins_day); }
    String binsAnchor() { return str(K_BINS_ANCHOR, R.string.bins_recycling_on); }
    /** The bin that goes out every week (Bins.GREEN, RED or YELLOW); the other two alternate. */
    String binsEvery() { String b = str(K_BINS_EVERY, R.string.bins_every_week); return Bins.valid(b) ? b : Bins.GREEN; }

    private static final int[] CAM_NAMES = {R.string.cam1_name, R.string.cam2_name};
    private static final int[] CAM_URLS = {R.string.cam1_url, R.string.cam2_url};
    private static final int[] CAM_HDS = {R.string.cam1_url_hd, R.string.cam2_url_hd};
    /** Camera i (0-based): name, low-res address, 1080p address (falls back to the low-res one). */
    String camName(int i) { return str(K_CAM_NAME + (i + 1), CAM_NAMES[i]); }
    String camUrl(int i) { return str(K_CAM_URL + (i + 1), CAM_URLS[i]).trim(); }
    String camHdUrl(int i) {
        String hd = str(K_CAM_HD + (i + 1), CAM_HDS[i]).trim();
        return hd.isEmpty() ? camUrl(i) : hd;
    }

    // ---------- changing things ----------

    void set(String key, String value) { p.edit().putString(key, value).apply(); }
    void set(String key, int value) { p.edit().putInt(key, value).apply(); }
    void setPlace(String postcode, String name, double lat, double lon, String tz) {
        p.edit().putString(K_POSTCODE, postcode).putString(K_PLACE, name).putString(K_LAT, Double.toString(lat)).putString(K_LON, Double.toString(lon))
                .putString(K_TZ, tz).apply();
    }
    /** Back to the defaults (config.xml) for everything. */
    void reset() { p.edit().clear().apply(); }

    /** Set from text (adb). Returns an error, or null if it was accepted. */
    String setFromText(String key, String value) {
        if (key == null || value == null) return "need --es key and --es value";
        switch (key) {
            case K_THEME: if (indexOf(Look.ids(), value) < 0) return "theme: " + String.join(", ", Look.ids()); break;
            case K_CAMERAS: if (!value.equals("on") && !value.equals("off")) return "cameras: on or off"; break;
            case K_OVERLAY_CORNER: if (indexOf(OVERLAY_CORNERS, value) < 0) return "overlay_corner: " + String.join(", ", OVERLAY_CORNERS); break;
            case K_OVERLAY_SIZE: if (indexOf(OVERLAY_SIZES, value) < 0) return "overlay_size: " + String.join(", ", OVERLAY_SIZES); break;
            case K_SCENE: if (indexOf(SCENES, value) < 0) return "scene: " + String.join(", ", SCENES); break;
            case K_CODE: if (!validCode(value)) return "parent_code: 2 to 8 of U D L R"; break;
            case K_MINUTES: case K_BINS_DAY:
                try {
                    int n = Integer.parseInt(value);
                    if (key.equals(K_BINS_DAY) ? n < 0 || n > 7 : n < 1) return key + ": out of range";
                    set(key, n);
                    return null;
                } catch (NumberFormatException e) { return key + ": a whole number"; }
            case K_BINS_EVERY: if (!Bins.valid(value)) return "bins_every_week: green, red or yellow"; break;
            case K_BINS_ANCHOR: if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return "bins_recycling_on: YYYY-MM-DD"; break;
            case K_POSTCODE: if (!Postcodes.valid(value)) return "weather_postcode: 4 digits"; break;
            case K_ADULT_START: case K_ADULT_END:
                if (!value.isEmpty() && parseTimeMin(value) < 0) return key + ": HH:MM (24-hour) or blank";
                break;
            case K_LAT: case K_LON:
                try { Double.parseDouble(value); } catch (NumberFormatException e) { return key + ": a number"; }
                break;
            case "reset": reset(); return null;
            default:
                if (!key.equals(K_FAMILY) && !key.equals(K_PLACE) && !key.equals(K_TIMED) && !key.equals(K_TZ)
                        && !key.matches("cam_(name|url|url_hd)[12]")) return "unknown key " + key;
        }
        set(key, value);
        return null;
    }

    static boolean validCode(String s) { return s != null && s.matches("[UDLR]{2,8}"); }

    /** Parse 24-hour "HH:MM" into minutes since midnight (0..1439), or -1 if blank/invalid. Zero allocations. */
    static int parseTimeMin(String hhmm) {
        if (hhmm == null) return -1;
        int len = hhmm.length();
        int a = 0, b = len;
        while (a < b && hhmm.charAt(a) <= ' ') a++;
        while (b > a && hhmm.charAt(b - 1) <= ' ') b--;
        int n = b - a;
        if (n != 4 && n != 5) return -1;
        int colon = n == 4 ? a + 1 : a + 2;
        if (hhmm.charAt(colon) != ':') return -1;
        int h = 0;
        for (int i = a; i < colon; i++) {
            int d = hhmm.charAt(i) - '0';
            if (d < 0 || d > 9) return -1;
            h = h * 10 + d;
        }
        int m1 = hhmm.charAt(colon + 1) - '0', m2 = hhmm.charAt(colon + 2) - '0';
        if (m1 < 0 || m1 > 9 || m2 < 0 || m2 > 9) return -1;
        int m = m1 * 10 + m2;
        return h < 24 && m < 60 ? h * 60 + m : -1;
    }

    static String formatTime24(int min) {
        int norm = Math.floorMod(min, 1440);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", norm / 60, norm % 60);
    }

    /** "7:30 pm", "11:00 pm", "12:00 am". */
    static String formatTime12(int min) {
        int norm = Math.floorMod(min, 1440);
        int h24 = norm / 60, m = norm % 60;
        int h12 = h24 % 12 == 0 ? 12 : h24 % 12;
        return String.format(java.util.Locale.ROOT, "%d:%02d %s", h12, m, h24 < 12 ? "am" : "pm");
    }

    /** n filled dots, so a code being typed or shown never gives its arrows away: ● ● ● */
    static String dots(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "●" : " ●");
        return sb.toString();
    }

    static String sceneName(String id) { int i = indexOf(SCENES, id); return i < 0 ? id : SCENE_NAMES[i]; }

    static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    private static String oneOf(String s, String[] allowed, String def) { return indexOf(allowed, s) >= 0 ? s : def; }

    private double parse(String s, int def) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.parseDouble(r.getString(def)); }
    }
}
