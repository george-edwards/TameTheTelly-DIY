// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which apps the kids' daily time applies to (Config > Timed apps, HomeConfig.timedApps): a chosen list, or
 * "all": every app on Home except Home itself, the grown-ups-only apps and config.xml's untimed_packages
 * (settings, the Play Store and TV tools; not in a chosen list either). Never the apps that break in a window
 * (config.xml's unwindowable_apps).
 * A snapshot: make a new one to pick up changes to the setting or the installed apps.
 */
final class TimedApps {
    private final String self;
    private final boolean all;
    private final Set<String> list, never, grownUp, youtube;
    private final Map<String, String> unwindowable = new HashMap<>();   // package -> why it can't be timed
    private final Set<String> onHome;   // "all" only: launchable apps

    TimedApps(Context c) {
        HomeConfig cfg = new HomeConfig(c);
        self = c.getPackageName();
        all = cfg.timedAll();
        list = cfg.timedList();
        never = ScreenTime.pkgs(c, R.array.untimed_packages);
        grownUp = ScreenTime.pkgs(c, R.array.grownup_packages);
        youtube = ScreenTime.pkgs(c, R.array.youtube_packages);
        for (String item : c.getResources().getStringArray(R.array.unwindowable_apps)) {   // "package|reason"
            String pkg = unwindowablePkg(item);
            if (pkg != null) unwindowable.put(pkg, item.substring(item.indexOf('|') + 1).trim());
        }
        onHome = all ? launchable(c) : null;
    }

    boolean all() { return all; }

    /** Offered in Config > Timed apps: not Home itself, Settings and the like, or a grown-ups app. */
    boolean offered(String pkg) {
        return pkg != null && !pkg.equals(self) && !never.contains(pkg) && !grownUp.contains(pkg);
    }

    /** Counts against the allowance and gets the scene. */
    boolean timed(String pkg) {
        if (!offered(pkg) || unwindowable.containsKey(pkg)) return false;
        return all ? onHome.contains(pkg) : list.contains(pkg);
    }

    /** Grown-ups-only (e.g. SmartTube): requires the arrow code. */
    boolean grownUp(String pkg) { return grownUp.contains(pkg); }

    /** Why `pkg` can never be timed (it breaks in a window), or null. */
    String cantBeTimed(String pkg) { return unwindowable.get(pkg); }

    /** YouTube: the scene tile's play badge is YouTube red rather than the app's own colour. */
    boolean youtube(String pkg) { return youtube.contains(pkg); }

    /** Single-package check without scanning all launchable apps (for remote-button dispatch). */
    static boolean isTimedSingle(Context c, String pkg, java.util.function.BooleanSupplier launchable) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        if (inArray(c, R.array.untimed_packages, pkg) || inArray(c, R.array.grownup_packages, pkg)) return false;
        for (String item : c.getResources().getStringArray(R.array.unwindowable_apps)) {
            if (pkg.equals(unwindowablePkg(item))) return false;
        }
        HomeConfig cfg = new HomeConfig(c);
        return cfg.timedAll() ? launchable.getAsBoolean() : cfg.timedList().contains(pkg);
    }

    static boolean inArray(Context c, int arrayId, String pkg) {
        if (pkg == null) return false;
        for (String s : c.getResources().getStringArray(arrayId)) if (pkg.equals(s)) return true;
        return false;
    }

    private static String unwindowablePkg(String item) {
        int bar = item.indexOf('|');
        return bar > 0 ? item.substring(0, bar).trim() : null;
    }

    /** Packages with a launcher activity (TV or ordinary), as Home lists them. */
    static Set<String> launchable(Context c) {
        Set<String> out = new HashSet<>();
        for (ResolveInfo ri : AppStore.queryLaunchable(c.getPackageManager())) out.add(ri.activityInfo.packageName);
        return out;
    }
}
