// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remaps buttons on the TV remote (Config > Remote buttons), replacing third-party key mappers.
 *
 * Each button is identified by its Android keyCode ("k:176", "k:4062") or, for unmapped Linux keys
 * where keyCode is 0, its hardware scanCode ("s:705"). Targets are either an app ("app:<pkg>", which
 * automatically honours Kids' time via WatchActivity and grown-ups-only apps via ParentGate) or one of
 * the built-in TV actions (Home, TV Settings, Picture settings, Screensaver, Do nothing).
 *
 * Keys are intercepted globally by RemoteKeyService (an AccessibilityService inside Home). Android TV
 * sometimes resets accessibility_enabled to 0 or leaves a service unbound after a reboot, so
 * ensureService() turns it on and self-heals it over Home's own adb connection (LocalAdb).
 */
final class RemoteKeys {
    static final String TAG = "HomeRemoteKeys";
    private static final String PREFS = "remote_keys";
    static final String SERVICE = "au.internal.home/au.internal.home.RemoteKeyService";
    private static final String SERVICE_SHORT = "au.internal.home/.RemoteKeyService";

    private static final String MAP_PREFIX = "map:";
    private static final String LABEL_PREFIX = "label:";

    static final String APP_PREFIX = "app:";
    static final String ACTION_HOME = "action:home";
    static final String ACTION_SETTINGS = "action:settings";
    static final String ACTION_PICTURE = "action:picture";
    static final String ACTION_SCREENSAVER = "action:screensaver";
    static final String ACTION_CAM_1 = "action:cam1";
    static final String ACTION_CAM_2 = "action:cam2";
    static final String ACTION_CAM_BOTH = "action:cam_both";
    static final String ACTION_CAM_CYCLE = "action:cam_cycle";
    static final String ACTION_NOOP = "action:noop";
    static final String[] ACTIONS = {
            ACTION_HOME, ACTION_SETTINGS, ACTION_PICTURE, ACTION_SCREENSAVER,
            ACTION_CAM_1, ACTION_CAM_2, ACTION_CAM_BOTH, ACTION_CAM_CYCLE, ACTION_NOOP
    };

    private static final Object CONNECT_LOCK = new Object();

    private final SharedPreferences p;

    RemoteKeys(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void register(SharedPreferences.OnSharedPreferenceChangeListener l) { p.registerOnSharedPreferenceChangeListener(l); }
    void unregister(SharedPreferences.OnSharedPreferenceChangeListener l) { p.unregisterOnSharedPreferenceChangeListener(l); }
    static boolean isMapKey(String key) { return key == null || key.startsWith(MAP_PREFIX); }

    static String appTarget(String pkg) { return APP_PREFIX + pkg; }

    /** Button identity from a KeyEvent: keyCode when known (works across Bluetooth and IR), else scanCode. */
    static String keyId(KeyEvent e) { return e == null ? null : keyId(e.getKeyCode(), e.getScanCode()); }

    static String keyId(int keyCode, int scanCode) {
        if (keyCode > 0) return "k:" + keyCode;
        if (scanCode > 0) return "s:" + scanCode;
        return null;
    }

    /** Integer slot for zero-allocation SparseArray lookups: positive = keyCode, negative = -scanCode, 0 = invalid. */
    static int slot(int keyCode, int scanCode) {
        if (keyCode > 0) return keyCode;
        return scanCode > 0 ? -scanCode : 0;
    }

    static int slotOf(String id) {
        int code = keyCodeOf(id);
        return code > 0 ? code : -scanCodeOf(id);
    }

    static boolean validId(String id) { return keyCodeOf(id) > 0 || scanCodeOf(id) > 0; }

    static int keyCodeOf(String id) { return parseId(id, "k:"); }

    static int scanCodeOf(String id) { return parseId(id, "s:"); }

    private static int parseId(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return 0;
        String num = id.substring(prefix.length());
        if (num.isEmpty() || num.charAt(0) < '1' || num.charAt(0) > '9') return 0;
        try { return Integer.parseInt(num); } catch (NumberFormatException e) { return 0; }
    }

    static boolean validTarget(String target) {
        if (target == null || target.isEmpty()) return false;
        if (HomeConfig.indexOf(ACTIONS, target) >= 0) return true;
        return target.startsWith(APP_PREFIX) && target.length() > APP_PREFIX.length();
    }

    /** Essential navigation and TV hardware buttons that must never be remapped. */
    static boolean blocked(int keyCode) {
        if (passThroughInCapture(keyCode)) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    /** Buttons that keep working normally even while the "Press a button" prompt is listening. */
    static boolean passThroughInCapture(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
    }

    // ---------- storage ----------

    private static boolean isMapEntry(Map.Entry<String, ?> e) {
        return e.getKey().startsWith(MAP_PREFIX) && e.getValue() instanceof String && validTarget((String) e.getValue());
    }

    /** All mapped buttons (keyId -> target), ordered nicely by label then id. */
    Map<String, String> all() {
        Map<String, ?> raw = p.getAll();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, ?> e : raw.entrySet()) {
            if (isMapEntry(e)) ids.add(e.getKey().substring(MAP_PREFIX.length()));
        }
        ids.sort((a, b) -> {
            int cmp = label(a).compareToIgnoreCase(label(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : ids) out.put(id, (String) raw.get(MAP_PREFIX + id));
        return out;
    }

    /** Unsorted slot -> target table for zero-allocation key dispatch in RemoteKeyService. */
    SparseArray<String> slots() {
        SparseArray<String> out = new SparseArray<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (!isMapEntry(e)) continue;
            int s = slotOf(e.getKey().substring(MAP_PREFIX.length()));
            if (s != 0 && !blocked(s > 0 ? s : 0)) out.put(s, (String) e.getValue());
        }
        return out;
    }

    boolean isEmpty() {
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) if (isMapEntry(e)) return false;
        return true;
    }

    String target(String id) {
        String t = p.getString(MAP_PREFIX + id, null);
        return validTarget(t) ? t : null;
    }

    void set(String id, String target) {
        if (!validId(id) || blocked(keyCodeOf(id))) return;
        if (target == null || target.isEmpty()) { remove(id); return; }
        if (!validTarget(target)) return;
        p.edit().putString(MAP_PREFIX + id, target).apply();
    }

    void remove(String id) {
        p.edit().remove(MAP_PREFIX + id).remove(LABEL_PREFIX + id).apply();
    }

    void clear() { p.edit().clear().apply(); }

    String customLabel(String id) { return p.getString(LABEL_PREFIX + id, "").trim(); }

    String label(String id) {
        String custom = customLabel(id);
        return custom.isEmpty() ? defaultKeyLabel(id) : custom;
    }

    void setLabel(String id, String label) {
        if (!validId(id)) return;
        String clean = label == null ? "" : label.trim();
        if (clean.isEmpty()) p.edit().remove(LABEL_PREFIX + id).apply();
        else p.edit().putString(LABEL_PREFIX + id, clean).apply();
    }

    // ---------- labels ----------

    /** Human-readable fallback name for a remote button when the user hasn't renamed it. */
    static String defaultKeyLabel(String id) {
        int code = keyCodeOf(id);
        if (code > 0) {
            String known = knownKeyName(code);
            if (known != null) return known;
            try {
                String s = KeyEvent.keyCodeToString(code);
                if (s != null && s.startsWith("KEYCODE_")) return humaniseKeyCode(s.substring(8)) + " button";
            } catch (RuntimeException | LinkageError ignored) { }   // JVM unit tests without Robolectric
            return "#" + code;
        }
        int scan = scanCodeOf(id);
        return scan > 0 ? "#s" + scan : "Button";
    }

    private static String knownKeyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SETTINGS: return "Settings button";
            case KeyEvent.KEYCODE_TV_INPUT: return "Input button";
            case KeyEvent.KEYCODE_GUIDE: return "Guide button";
            case KeyEvent.KEYCODE_INFO: return "Info button";
            case KeyEvent.KEYCODE_MENU: return "Menu button";
            case KeyEvent.KEYCODE_BOOKMARK: return "Bookmark button";
            case KeyEvent.KEYCODE_MEDIA_TOP_MENU: return "Top menu button";
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU: return "Contents button";
            case KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU: return "Context menu button";
            case KeyEvent.KEYCODE_PROG_RED: return "Red button";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green button";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow button";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue button";
            case KeyEvent.KEYCODE_TV: return "TV button";
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST: return "Assistant button";
            case KeyEvent.KEYCODE_NOTIFICATION: return "Notifications button";
            case KeyEvent.KEYCODE_APP_SWITCH: return "Recent apps button";
            case KeyEvent.KEYCODE_EXPLORER: return "Browser button";
            case KeyEvent.KEYCODE_MUSIC: return "Music button";
            case KeyEvent.KEYCODE_TV_TELETEXT: return "Teletext button";
            case KeyEvent.KEYCODE_CAPTIONS: return "Captions button";
            case KeyEvent.KEYCODE_DVR: return "DVR button";
            case KeyEvent.KEYCODE_AVR_INPUT:
            case KeyEvent.KEYCODE_STB_INPUT: return "Input button";
            case KeyEvent.KEYCODE_HOME: return "Home";
            case KeyEvent.KEYCODE_BACK: return "Back";
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Arrows";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "OK";
            default:
                if (code >= KeyEvent.KEYCODE_BUTTON_1 && code <= KeyEvent.KEYCODE_BUTTON_16)
                    return "Button " + (code - KeyEvent.KEYCODE_BUTTON_1 + 1);
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                    return "Number " + (code - KeyEvent.KEYCODE_0) + " button";
                return null;
        }
    }

    private static String humaniseKeyCode(String raw) {
        String s = raw.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return s.isEmpty() ? "Remote" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Whether a built-in action should be offered in Config > Remote buttons given the current camera setup. */
    static boolean actionAvailable(String target, boolean hasCam1, boolean hasCam2) {
        if (target == null) return false;
        switch (target) {
            case ACTION_CAM_1: return hasCam1;
            case ACTION_CAM_2: return hasCam2;
            case ACTION_CAM_BOTH:
            case ACTION_CAM_CYCLE: return hasCam1 && hasCam2;
            default: return true;
        }
    }

    static boolean actionAvailable(String target, HomeConfig cfg) {
        if (cfg == null) return actionAvailable(target, false, false);
        return actionAvailable(target, !cfg.camUrl(0).isEmpty(), !cfg.camUrl(1).isEmpty());
    }

    /** Human-readable name for a target (app name with Kids' time / Grown-ups hint, or TV action). */
    static String targetLabel(String target, List<AppStore.App> installed, TimedApps timed) {
        return targetLabel(target, installed, timed, null);
    }

    static String targetLabel(String target, List<AppStore.App> installed, TimedApps timed, HomeConfig cfg) {
        if (target == null) return "Normal";
        switch (target) {
            case ACTION_HOME: return "Home screen";
            case ACTION_SETTINGS: return "TV Settings";
            case ACTION_PICTURE: return "Picture settings";
            case ACTION_SCREENSAVER: return "Screensaver";
            case ACTION_CAM_1: return (cfg != null ? cfg.camName(0) : "Camera 1") + " overlay";
            case ACTION_CAM_2: return (cfg != null ? cfg.camName(1) : "Camera 2") + " overlay";
            case ACTION_CAM_BOTH: return "Both cameras overlay";
            case ACTION_CAM_CYCLE: return "Camera overlay (cycle)";
            case ACTION_NOOP: return "Do nothing (disabled)";
        }
        if (target.startsWith(APP_PREFIX)) {
            String pkg = target.substring(APP_PREFIX.length());
            String name = pkg;
            if (installed != null) {
                for (AppStore.App a : installed) {
                    if (a.pkg.equals(pkg)) { name = a.label; break; }
                }
            }
            return appTargetLabel(pkg, name, timed);
        }
        return target;
    }

    /** Same as targetLabel() when the caller already has the app's package and display name. */
    static String appTargetLabel(String pkg, String name, TimedApps timed) {
        if (timed != null && timed.grownUp(pkg)) return name + " (Grown-ups)";
        if (timed != null && timed.timed(pkg)) return name + " (Timed)";
        return name;
    }

    // ---------- execution ----------

    /** Run a mapped target (called from RemoteKeyService when the user presses a mapped button). */
    static void execute(Context c, String target) {
        if (target == null) return;
        switch (target) {
            case ACTION_NOOP: return;
            case ACTION_HOME:
                start(c, MainActivity.home(c));
                return;
            case ACTION_SETTINGS:
                start(c, new Intent(Settings.ACTION_SETTINGS));
                return;
            case ACTION_PICTURE:
                if (!start(c, new Intent(Settings.ACTION_DISPLAY_SETTINGS)))
                    start(c, new Intent(Settings.ACTION_SETTINGS));
                return;
            case ACTION_SCREENSAVER:
                start(c, new Intent("com.google.android.pano.action.SLEEP"));
                return;
            case ACTION_CAM_1:
                ScreenTimeService.camOverlay(c, "toggle", CameraOverlay.MODE_CAM1);
                return;
            case ACTION_CAM_2:
                ScreenTimeService.camOverlay(c, "toggle", CameraOverlay.MODE_CAM2);
                return;
            case ACTION_CAM_BOTH:
                ScreenTimeService.camOverlay(c, "toggle", CameraOverlay.MODE_BOTH);
                return;
            case ACTION_CAM_CYCLE:
                ScreenTimeService.camOverlay(c, "cycle", CameraOverlay.MODE_OFF);
                return;
        }
        if (!target.startsWith(APP_PREFIX)) return;
        String pkg = target.substring(APP_PREFIX.length());
        long now = System.currentTimeMillis();
        if (TimedApps.inArray(c, R.array.grownup_packages, pkg)) {
            if (!new ScreenTime(c).grownUp(now)) {
                start(c, MainActivity.home(c).putExtra(MainActivity.EXTRA_UNLOCK_PKG, pkg));
                return;
            }
            Intent launch = AppStore.launchIntent(c.getPackageManager(), pkg);
            if (launch != null) start(c, launch);
            return;
        }
        Intent[] launch = new Intent[1];
        boolean[] resolved = new boolean[1];
        if (TimedApps.isTimedSingle(c, pkg, () -> {
            resolved[0] = true;
            launch[0] = AppStore.launchIntent(c.getPackageManager(), pkg);
            return launch[0] != null;
        })) {
            start(c, new Intent(c, WatchActivity.class).putExtra("pkg", pkg));
            return;
        }
        Intent i = resolved[0] ? launch[0] : AppStore.launchIntent(c.getPackageManager(), pkg);
        if (i != null) start(c, i);
    }

    private static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "couldn't start " + i, e);
            return false;
        }
    }

    // ---------- self-healing AccessibilityService via LocalAdb ----------

    static void notifyConnected() {
        synchronized (CONNECT_LOCK) { CONNECT_LOCK.notifyAll(); }
    }

    private static boolean isOwnService(String s) { return s.equals(SERVICE) || s.equals(SERVICE_SHORT); }

    /** Pure helper: add or remove Home's service from Android's colon-separated enabled_accessibility_services list. */
    static String mergeService(String current, String service, boolean include) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (current != null) {
            for (String part : current.trim().split(":")) {
                String s = part.trim();
                if (s.isEmpty() || s.equals("null") || s.equals(service) || isOwnService(s)) continue;
                items.add(s);
            }
        }
        if (include) items.add(service);
        return String.join(":", items);
    }

    private static boolean containsOwnService(String current) {
        if (current == null) return false;
        for (String part : current.trim().split(":")) if (isOwnService(part.trim())) return true;
        return false;
    }

    private static String setServicesCmd(String list) {
        return list.isEmpty()
                ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + list + "'";
    }

    /**
     * Make sure RemoteKeyService is enabled and bound, using Home's own adb connection (LocalAdb).
     * Reads current state in-process (one adb call to write). If Android already lists it as enabled
     * but left it unbound, re-toggles it to force a fresh bind. Call off the main thread.
     */
    static synchronized boolean ensureService(Context c) {
        if (RemoteKeyService.isConnected()) return true;
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean enabled = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            String with = mergeService(cur, SERVICE, true);
            String enableCmd = setServicesCmd(with) + "; settings put secure accessibility_enabled 1";
            if (enabled && containsOwnService(cur)) {
                String without = mergeService(cur, SERVICE, false);
                LocalAdb.exec(c, setServicesCmd(without) + "; sleep 0.3; " + enableCmd, 5_000);
            } else {
                LocalAdb.exec(c, enableCmd, 5_000);
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 2_500;
            synchronized (CONNECT_LOCK) {
                while (!RemoteKeyService.isConnected()) {
                    long left = deadline - android.os.SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    CONNECT_LOCK.wait(left);
                }
            }
            return RemoteKeyService.isConnected();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RemoteKeyService.isConnected();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "ensureService failed: " + e.getMessage());
            return false;
        }
    }

    /** Remove Home's accessibility service when the user has cleared all button mappings. Call off the main thread. */
    static synchronized void disableService(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!containsOwnService(cur)) return;
            String without = mergeService(cur, SERVICE, false);
            String cmd = setServicesCmd(without) + (without.isEmpty() ? "; settings put secure accessibility_enabled 0" : "");
            LocalAdb.exec(c, cmd, 5_000);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "disableService failed: " + e.getMessage());
        }
    }
}
