// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Screen-time controls over adb, for tuning the progression live on the TV and for grown-ups at a computer.
 * Protected by the DUMP permission, which adb's shell has and ordinary apps can't get.
 *
 *   adb shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.SCRUB --ef p 0.8
 *       pin the scene at 80% (0 morning .. 0.7 golden hour .. 0.9 nightfall .. 1 goodnight)
 *   ... -a au.internal.home.SCRUB --ef p 0 --ef to 1 --ei secs 120     run 0 -> 1 over two minutes
 *   ... -a au.internal.home.LIVE                back to the real allowance (and preview off)
 *   ... -a au.internal.home.PREVIEW --ez on true   show the scene over whatever is on screen
 *   ... -a au.internal.home.ADD --ei min 15     extra minutes today
 *   ... -a au.internal.home.RESET               start today's allowance again
 *   ... -a au.internal.home.GROWNUP --ei min 120   grown-up time (0 ends it)
 *   ... -a au.internal.home.DEMO --ei secs 10   the whole day over YouTube in 10 s (then open YouTube)
 *   ... -a au.internal.home.STATUS              print where things are (and what Framer did lately)
 *   ... -a au.internal.home.RESTORE_CHECK       copy restore.sh onto the TV and check it parses (sh -n); runs nothing
 *   ... -a au.internal.home.SET --es key scene --es value brook    a setting (keys in HomeConfig; key reset
 *                                                                  with any value forgets them all)
 *   ... -a au.internal.home.KEY --es id k:176 --es target action:settings [--es label Settings]
 *       remap a remote button (empty target removes it; id "clear" clears all)
 */
public final class DevReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null || !a.startsWith("au.internal.home.")) return;
        String cmd = a.substring("au.internal.home.".length());
        ScreenTime st = new ScreenTime(c);
        long now = System.currentTimeMillis();
        switch (cmd) {
            case "ADD": st.addMinutes(now, i.getIntExtra("min", 15)); break;
            case "RESET": st.resetToday(now); break;
            case "GROWNUP": st.setGrownUp(now + i.getIntExtra("min", 120) * 60_000L); break;
            case "SCRUB": case "LIVE": case "PREVIEW": case "DEMO": {
                if (cmd.equals("DEMO")) { st.setScrub(0f); st.setDemo(true); }   // before WatchActivity starts, so it opens windowed even if asleep or in adult mode
                Bundle x = i.getExtras();
                Intent s = ScreenTimeService.dev(c, cmd);
                if (x != null) s.putExtras(x);
                c.startForegroundService(s);
                if (cmd.equals("DEMO")) c.startActivity(new Intent(c, WatchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            }
            case "SET": {
                // A setting, as in the TV's Config menu (key names: HomeConfig). Home picks it up when it's next
                // started; `am force-stop au.internal.home` and press Home to see it straight away.
                String key = i.getStringExtra("key");
                String err = new HomeConfig(c).setFromText(key, i.getStringExtra("value"));
                if (err == null && (HomeConfig.K_ADULT_START.equals(key) || HomeConfig.K_ADULT_END.equals(key) || "reset".equals(key)))
                    st.clearModeOverride();
                setResultData(err != null ? err : "set " + key);
                return;
            }
            case "KEY": {
                RemoteKeys rk = new RemoteKeys(c);
                String id = i.getStringExtra("id");
                String target = i.getStringExtra("target");
                String label = i.getStringExtra("label");
                if ("clear".equals(id)) {
                    rk.clear();
                    runAsync(c, app -> { RemoteKeys.disableService(app); return "cleared"; });
                    return;
                }
                if (!RemoteKeys.validId(id)) { setResultData("bad id (use k:<keyCode> or s:<scanCode>)"); return; }
                if (RemoteKeys.blocked(RemoteKeys.keyCodeOf(id))) { setResultData("blocked button " + id); return; }
                if (target == null || target.isEmpty()) {
                    rk.remove(id);
                    if (!rk.isEmpty()) { setResultData("removed " + id); return; }
                    runAsync(c, app -> { RemoteKeys.disableService(app); return "removed " + id + " (service off)"; });
                    return;
                }
                if (!RemoteKeys.validTarget(target)) {
                    setResultData("bad target (app:<pkg>, " + String.join(", ", RemoteKeys.ACTIONS) + ")");
                    return;
                }
                rk.set(id, target);
                if (label != null) rk.setLabel(id, label);
                runAsync(c, app -> {
                    boolean ok = RemoteKeys.ensureService(app);
                    return "mapped " + id + " -> " + target + " (service " + (ok ? "connected" : "not connected") + ")";
                });
                return;
            }
            case "RESTORE_CHECK": {
                // Copies restore.sh onto the TV and has the shell read it without running it (sh -n). Never runs it.
                runAsync(c, Restore::check);
                return;
            }
            case "STATUS": break;
            default: setResultData("unknown: " + cmd); return;
        }
        RemoteKeys rk = new RemoteKeys(c);
        java.util.Map<String, String> mapped = rk.all();
        StringBuilder keys = new StringBuilder();
        keys.append("Remote keys (service ").append(RemoteKeyService.isConnected() ? "connected" : "off")
                .append(", ").append(mapped.size()).append(" mapped):");
        for (java.util.Map.Entry<String, String> e : mapped.entrySet()) {
            keys.append("\n  ").append(e.getKey()).append(" (").append(rk.label(e.getKey())).append(") -> ").append(e.getValue());
        }
        String recent = RemoteKeyService.recentKeys();
        if (!recent.isEmpty()) keys.append("\nRecent keys:\n").append(recent);
        String schedText = new HomeConfig(c).adultScheduleText();
        String sched = schedText != null ? ", schedule " + schedText : "";
        setResultData(st.describe(now) + String.format(java.util.Locale.ROOT, ", progress %.2f%s%s%s", st.progress(now),
                st.scrubbing() ? " (scrubbed)" : "",
                st.adultMode(now) ? ", adult mode" : st.hasPass(now) ? ", grown-up pass" : "",
                sched)
                + "\nFramer:\n" + Framer.notes() + "\n" + keys);
    }

    private void runAsync(Context c, java.util.function.Function<Context, String> work) {
        Context app = c.getApplicationContext();
        PendingResult r = goAsync();
        new Thread(() -> { r.setResultData(work.apply(app)); r.finish(); }).start();
    }
}
