// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The TV settings that follow the sun at the weather location (computed on the TV, no network):
 *  - Panel backlight: full by day, dusk level from sunset, night level once it's properly dark
 *    (nautical dusk), and back the same way in the morning (nautical dawn, sunrise).
 *  - Blue Light Filter: on at sunset, off at sunrise.
 * Each change is applied once per step, so a manual change in between is respected until the next
 * step. Missed steps (TV off) are caught up at the next alarm, boot or return to Home.
 */
public final class Bedtime extends BroadcastReceiver {
    private static final String ACTION = "au.internal.home.BEDTIME";

    // ---------- backlight steps ----------

    /** A backlight change at a sun event. */
    static final class Step {
        final long at; final int level; final String name;
        final Boolean blue;   // Blue Light Filter on/off at this step, or null to leave it alone
        Step(long at, int level, String name, Boolean blue) { this.at = at; this.level = level; this.name = name; this.blue = blue; }
        @Override public String toString() { return String.format(Locale.ROOT, "%s %tF %<tR -> %d", name, at, level); }
    }

    /** Sun-driven steps from yesterday to tomorrow, in time order. */
    static List<Step> steps(double lat, double lon, Calendar now, int day, int dusk, int night) {
        List<Step> out = new ArrayList<>();
        for (int off = -1; off <= 1; off++) {
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_YEAR, off);
            long[] sun = Sun.crossings(lat, lon, d, Sun.SUNSET), dark = Sun.crossings(lat, lon, d, Sun.NAUTICAL);
            if (dark != null) out.add(new Step(dark[0], dusk, "dawn", null));
            if (sun != null) { out.add(new Step(sun[0], day, "sunrise", false)); out.add(new Step(sun[1], dusk, "sunset", true)); }
            if (dark != null) out.add(new Step(dark[1], night, "dark", null));
        }
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    /** The step in force at `now` (index 0) and the next one (index 1). */
    static Step[] around(List<Step> steps, long now) {
        Step cur = null, next = null;
        for (Step s : steps) {
            if (s.at <= now) cur = s;
            else { next = s; break; }
        }
        return new Step[]{cur, next};
    }

    /** The latest step at or before `now` that sets the Blue Light Filter. */
    static Step lastBlue(List<Step> steps, long now) {
        Step cur = null;
        for (Step s : steps) if (s.at <= now && s.blue != null) cur = s;
        return cur;
    }

    private static List<Step> steps(Context c, Calendar now) {
        Resources r = c.getResources();
        HomeConfig cfg = new HomeConfig(c);
        return steps(cfg.lat(), cfg.lon(), now, r.getInteger(R.integer.backlight_day), r.getInteger(R.integer.backlight_dusk), r.getInteger(R.integer.backlight_night));
    }

    /** Whether the sun says the Blue Light Filter should be on right now (sunset to sunrise). */
    static boolean wantsBlue(Context c) {
        Calendar now = Calendar.getInstance();
        Step s = lastBlue(steps(c, now), now.getTimeInMillis());
        return s != null && Boolean.TRUE.equals(s.blue);
    }

    // ---------- applying ----------

    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Apply whatever we haven't yet applied for the current period/step. Returns immediately; `done` runs after. */
    static void catchUp(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Calendar now = Calendar.getInstance();
                SharedPreferences p = app.getSharedPreferences("bedtime", Context.MODE_PRIVATE);
                List<Step> steps = steps(app, now);
                Step blue = lastBlue(steps, now.getTimeInMillis());
                if (blue != null && !blue.toString().equals(p.getString("blue_applied", "")) && TvBlueLight.set(app, blue.blue))
                    p.edit().putString("blue_applied", blue.toString()).apply();

                Step cur = around(steps, now.getTimeInMillis())[0];
                if (cur == null) return;
                String step = cur.toString();
                if (step.equals(p.getString("backlight_applied", ""))) return;
                Integer last = p.contains("backlight_level") ? p.getInt("backlight_level", 0) : null;
                if (TvBacklight.fadeTo(app, last, cur.level))
                    p.edit().putString("backlight_applied", step).putInt("backlight_level", cur.level).apply();
            } finally {
                if (done != null) done.run();
            }
        });
    }

    /** Alarm at the next step (or in 6 h if somehow there isn't one).
     *  RTC (not _WAKEUP): if the TV is in standby it fires on wake. */
    static void schedule(Context c) {
        Calendar t = Calendar.getInstance();
        Step next = around(steps(c, t), t.getTimeInMillis())[1];
        long at = next != null ? next.at + 5_000 : t.getTimeInMillis() + 6 * 3_600_000L;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Bedtime.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setWindow(AlarmManager.RTC, at, 60_000, pi);
    }

    @Override public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) ScreenTimeService.start(c);   // boot / update: start watching for YouTube
        PendingResult r = goAsync();
        schedule(c);
        catchUp(c, r::finish);
    }
}
