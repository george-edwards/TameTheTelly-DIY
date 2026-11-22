// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The child's daily allowance for the timed apps: minutes used today, extra minutes a grown-up granted, the grown-up pass,
 * and a development "scrub" that pins the progression to any point for tuning on the TV.
 * A "day" runs from RESET_HOUR to RESET_HOUR, so a late-evening top-up doesn't spill into tomorrow
 * and the allowance comes back while they're asleep.
 *
 * Progress p (0..1+) drives everything visible:
 *   0 – 0.7 day, 0.7 – 0.9 golden hour then twilight, 0.9 – 1 nightfall, >= 1 asleep until tomorrow.
 */
final class ScreenTime {
    static final float GOLDEN = 0.7f, NIGHT = 0.9f;

    private final SharedPreferences p;
    private final HomeConfig config;
    private final int resetHour;
    private String cachedDay;
    private long dayStartMs, dayEndMs;
    private long minStartMs = -1;
    private int cachedMinuteOfDay;

    ScreenTime(Context c) {
        p = c.getApplicationContext().getSharedPreferences("screentime", Context.MODE_PRIVATE);
        config = new HomeConfig(c);   // the daily minutes can change on the TV, so read them each time
        resetHour = c.getResources().getInteger(R.integer.yt_reset_hour);
    }

    /** A package list from resources (R.array.yt_packages and friends), as a set. */
    static Set<String> pkgs(Context c, int arrayId) {
        return new HashSet<>(Arrays.asList(c.getResources().getStringArray(arrayId)));
    }

    /** The allowance day `now` falls in, e.g. "2026-11-02" until 4am on the 3rd. */
    static String dayOf(long now, int resetHour) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.HOUR_OF_DAY, -resetHour);
        return String.format(Locale.ROOT, "%tF", c);
    }

    /** Wipe yesterday's numbers if the day has turned over. */
    private void roll(long now) {
        String today;
        if (cachedDay != null && now >= dayStartMs && now < dayEndMs) {
            today = cachedDay;
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.HOUR_OF_DAY, -resetHour);
            today = String.format(Locale.ROOT, "%tF", c);
            c.set(Calendar.HOUR_OF_DAY, resetHour);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            dayStartMs = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
            dayEndMs = c.getTimeInMillis();
            cachedDay = today;
        }
        if (!today.equals(p.getString("day", ""))) {
            p.edit().putString("day", today).putLong("used", 0).putLong("bonus", 0).apply();
        }
    }

    long usedMs(long now) { roll(now); return p.getLong("used", 0); }
    long allowanceMs(long now) { roll(now); return config.ytMinutes() * 60_000L + p.getLong("bonus", 0); }

    void addUsed(long now, long ms) {
        roll(now);
        p.edit().putLong("used", p.getLong("used", 0) + ms).apply();
    }

    /** Grown-up top-up for today only. */
    void addMinutes(long now, int min) {
        roll(now);
        p.edit().putLong("bonus", p.getLong("bonus", 0) + min * 60_000L).apply();
    }

    void resetToday(long now) {
        p.edit().putString("day", dayOf(now, resetHour)).putLong("used", 0).putLong("bonus", 0).apply();
    }

    /** Real progress through today's allowance. */
    float realProgress(long now) {
        return progress(usedMs(now), allowanceMs(now));
    }

    static float progress(long used, long allowance) {
        return allowance <= 0 ? 1f : used / (float) allowance;
    }

    /** What the scene and the rules follow: the scrub if one is set, otherwise the real progress. */
    float progress(long now) {
        float s = p.getFloat("scrub", -1f);
        return s >= 0 ? s : realProgress(now);
    }

    /** Whole minutes left at progress p, rounded up (so it reads 1 until the time has really gone). During a
     *  scrub or demo this follows the scrubbed progress, counting down through today's allowance. */
    int minutesLeft(long now, float p) {
        return (int) Math.ceil(Math.max(0f, 1f - p) * allowanceMs(now) / 60_000.0 - 1e-6);
    }

    boolean asleep(long now) { return !grownUp(now) && progress(now) >= 1f; }

    // ---------- adult mode (persistent toggle + daily schedule) & one-app grown-up pass ----------

    /** True if `minuteOfDay` (0..1439) falls in [startMin, endMin), handling overnight windows (e.g. 20:00–05:00). */
    static boolean inWindow(int minuteOfDay, int startMin, int endMin) {
        if (!HomeConfig.validWindow(startMin, endMin)) return false;
        return startMin < endMin
                ? (minuteOfDay >= startMin && minuteOfDay < endMin)
                : (minuteOfDay >= startMin || minuteOfDay < endMin);
    }

    /** Next wall-clock instant strictly after `nowMs` when local time reaches `targetMin` (0..1439). */
    static long nextOccurrence(long nowMs, int targetMin) {
        int norm = Math.floorMod(targetMin, 1440);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, norm / 60);
        c.set(Calendar.MINUTE, norm % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /**
     * Expiry timestamp for a manual mode override when a schedule is active:
     *  - turning Adult mode on holds until the scheduled end time, capped at the next daily reset hour
     *    (so extending past e.g. 11 pm to finish a movie still falls back to kids' mode at 4 am instead of staying
     *    on all the next day).
     *  - turning Kids' mode on inside the adult window holds until the next scheduled adult start.
     */
    static long scheduledOverrideUntil(long nowMs, boolean wantAdult, int startMin, int endMin, int resetHour) {
        if (!wantAdult) return nextOccurrence(nowMs, startMin);
        long endAt = nextOccurrence(nowMs, endMin);
        if (inWindow(resetHour * 60, startMin, endMin)) return endAt;
        return Math.min(endAt, nextOccurrence(nowMs, resetHour * 60));
    }

    private int minuteOfDay(long nowMs) {
        if (minStartMs >= 0 && nowMs >= minStartMs && nowMs < minStartMs + 60_000L) return cachedMinuteOfDay;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        cachedMinuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        minStartMs = c.getTimeInMillis();
        return cachedMinuteOfDay;
    }

    /** Persistent adult mode (from the corner toggle or the automatic schedule). */
    boolean adultMode(long now) {
        if (now < p.getLong("modeUntil", 0)) return p.getBoolean("modeOverrideAdult", false);
        int s = config.adultStartMin();
        if (s < 0) return false;
        int e = config.adultEndMin();
        if (!HomeConfig.validWindow(s, e)) return false;
        return inWindow(minuteOfDay(now), s, e);
    }

    /**
     * Switch adult mode on or off. Returns the wall-clock timestamp when it will automatically fall back to kids'
     * mode (0 if indefinite or switching to kids' mode), so the UI can show the real switch-back time.
     */
    long setAdultMode(long now, boolean adult) {
        int s = config.adultStartMin(), e = config.adultEndMin();
        boolean scheduled = HomeConfig.validWindow(s, e);
        SharedPreferences.Editor ed = p.edit();
        if (!adult) ed.putLong("grownUpUntil", 0);   // switching to kids' mode ends any one-app pass too
        long fallBackAt = 0;
        if (!scheduled) {
            if (adult) ed.putBoolean("modeOverrideAdult", true).putLong("modeUntil", Long.MAX_VALUE);
            else ed.remove("modeOverrideAdult").remove("modeUntil");
        } else {
            boolean schedNow = inWindow(minuteOfDay(now), s, e);
            if (adult == schedNow) {
                ed.remove("modeOverrideAdult").remove("modeUntil");
                if (adult) fallBackAt = nextOccurrence(now, e);
            } else {
                long until = scheduledOverrideUntil(now, adult, s, e, resetHour);
                ed.putBoolean("modeOverrideAdult", adult).putLong("modeUntil", until);
                if (adult) fallBackAt = until;
            }
        }
        ed.apply();
        return fallBackAt;
    }

    /** Forget any manual toggle override (called when the schedule itself is changed or reset). */
    void clearModeOverride() { p.edit().remove("modeOverrideAdult").remove("modeUntil").apply(); }

    /** Grown-up right now: either persistent adult mode (toggle/schedule) or a one-app grown-up pass. */
    boolean grownUp(long now) { return adultMode(now) || hasPass(now); }

    /** One-app grown-up pass (Hold OK > Grown-up time, or unlocking a grown-ups-only app; ends on return Home). */
    void setGrownUp(long until) { p.edit().putLong("grownUpUntil", until).apply(); }
    boolean hasPass(long now) { return now < p.getLong("grownUpUntil", 0); }

    // ---------- development scrub ----------

    /** Pin progress to `s` (0..1+), or clear it with a negative value. Real counting carries on underneath. */
    void setScrub(float s) { p.edit().putFloat("scrub", s).apply(); }
    boolean scrubbing() { return p.getFloat("scrub", -1f) >= 0; }

    /** A demo run is in progress (cleared, with the scrub, once Home is back on top). */
    void setDemo(boolean on) { p.edit().putBoolean("demo", on).apply(); }
    boolean demo() { return p.getBoolean("demo", false); }

    static String describe(long usedMs, long allowanceMs) {
        long allMin = Math.max(0, Math.round(allowanceMs / 60_000.0));
        long usedMin = Math.min(allMin, Math.max(0, Math.round(usedMs / 60_000.0)));
        return String.format(Locale.ROOT, "%d of %d min used today", usedMin, allMin);
    }

    String describe(long now) {
        return describe(usedMs(now), allowanceMs(now));
    }

    /** Compact form for menu titles: "20 / 20 min". */
    String describeShort(long now) {
        long allMin = Math.max(0, Math.round(allowanceMs(now) / 60_000.0));
        long usedMin = Math.min(allMin, Math.max(0, Math.round(usedMs(now) / 60_000.0)));
        return String.format(Locale.ROOT, "%d / %d min", usedMin, allMin);
    }
}
