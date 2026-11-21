// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed launchable apps plus the user's ordering and hidden set (persisted in SharedPreferences). */
final class AppStore {
    static final class App {
        final String pkg;
        final String label;
        final Drawable banner; // may be null
        final Drawable icon;
        final int accent;      // the app's own colour, for its focus glow
        boolean hidden;
        boolean fav;
        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg; this.label = label; this.banner = banner; this.icon = icon;
            Integer c = ACCENTS.get(pkg);
            if (c == null) ACCENTS.put(pkg, c = TileFx.accentOf(banner != null ? banner : icon));
            accent = c;
        }
    }

    /** Accent colours survive reloads; recomputed only when the process restarts (e.g. after an app update). */
    private static final Map<String, Integer> ACCENTS = new java.util.HashMap<>();

    private final Context ctx;
    private final SharedPreferences prefs;

    AppStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    /** All launchable activities (TV leanback first, then ordinary launcher). */
    static List<ResolveInfo> queryLaunchable(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            out.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0));
        }
        return out;
    }

    /** All apps in the user's order (hidden ones included, flagged). */
    List<App> load() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        for (ResolveInfo ri : queryLaunchable(pm)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(ctx.getPackageName()) || found.containsKey(ai.packageName)) continue;
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            found.put(ai.packageName, new App(ai.packageName, String.valueOf(ri.loadLabel(pm)), banner, icon));
        }
        Set<String> hidden = hidden();
        List<App> out = new ArrayList<>();
        for (String pkg : order()) {
            App a = found.remove(pkg);
            if (a != null) out.add(a);
        }
        List<App> rest = new ArrayList<>(found.values());
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        out.addAll(rest);
        Set<String> favs = set("favs");
        for (App a : out) { a.hidden = hidden.contains(a.pkg); a.fav = favs.contains(a.pkg); }
        return out;
    }

    void saveOrder(List<App> apps) {
        StringBuilder sb = new StringBuilder();
        for (App a : apps) { if (sb.length() > 0) sb.append(','); sb.append(a.pkg); }
        prefs.edit().putString("order", sb.toString()).apply();
    }

    void setHidden(String pkg, boolean hide) { setMember("hidden", pkg, hide); }

    void setFav(String pkg, boolean fav) { setMember("favs", pkg, fav); }

    private void setMember(String key, String pkg, boolean in) {
        Set<String> h = new HashSet<>(set(key));
        if (in) h.add(pkg); else h.remove(pkg);
        prefs.edit().putStringSet(key, h).apply();
    }

    private Set<String> set(String key) {
        return prefs.getStringSet(key, new HashSet<>());
    }

    private List<String> order() {
        String s = prefs.getString("order", "");
        return s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split(","));
    }

    private Set<String> hidden() { return set("hidden"); }

    /** The TV (leanback) launch intent, else the ordinary one; null if the app can't be launched. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }
}
