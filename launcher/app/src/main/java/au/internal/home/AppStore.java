// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

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
        App(String pkg, String label, Drawable banner, Drawable icon, int accent) {
            this.pkg = pkg; this.label = label; this.banner = banner; this.icon = icon; this.accent = accent;
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
            if (banner == null) {
                Drawable logo = ai.loadLogo(pm);
                if (logo == null) logo = ai.applicationInfo.loadLogo(pm);
                if (logo != null && logo.getIntrinsicWidth() >= logo.getIntrinsicHeight() * 1.4f) banner = logo;
            }
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            String label = String.valueOf(ri.loadLabel(pm));
            App app = new App(ai.packageName, label, banner, icon);
            if (banner == null && icon != null) {
                app = new App(ai.packageName, label, makeBanner(ctx, label, icon, app.accent), null, app.accent);
            }
            found.put(ai.packageName, app);
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

    void setFavs(Set<String> pkgs) {
        prefs.edit().putStringSet("favs", new HashSet<>(pkgs)).apply();
    }

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

    /** Synthesise a 16:9 TV banner for apps that only ship a square icon, tinted with the icon's own accent colour. */
    private static Drawable makeBanner(Context ctx, String label, Drawable icon, int accent) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        float sat = Math.min(hsv[1], 0.52f);
        int top = Color.HSVToColor(new float[]{hsv[0], sat, 0.28f});
        int bot = Color.HSVToColor(new float[]{hsv[0], sat * 0.85f, 0.12f});
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, top, bot, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.5f, h * 0.38f, w * 0.45f,
                (accent & 0xFFFFFF) | 0x38000000, accent & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        int size = 78, ix = (w - size) / 2, iy = 28;
        Drawable ic = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        ic.setBounds(ix, iy, ix + size, iy + size);
        ic.draw(c);

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        try { tp.setTypeface(ctx.getResources().getFont(R.font.inter_semibold)); }
        catch (RuntimeException ignored) { tp.setTypeface(Typeface.DEFAULT_BOLD); }
        tp.setTextSize(22f);
        tp.setColor(0xF2FFFFFF);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setShadowLayer(6f, 0f, 2f, 0x80000000);
        CharSequence ellip = TextUtils.ellipsize(label, tp, w - 36f, TextUtils.TruncateAt.END);
        c.drawText(ellip, 0, ellip.length(), w * 0.5f, 146f, tp);
        return new BitmapDrawable(ctx.getResources(), b);
    }

    /** The TV (leanback) launch intent, else the ordinary one; null if the app can't be launched. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }
}
