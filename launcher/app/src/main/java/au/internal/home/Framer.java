// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a timed app in its window: a "multi-window" task (freeform without the title bar) the size of the Scene's
 * hole (WatchActivity.window). Apps can't size other apps' windows, so this goes through LocalAdb (shell rights on
 * the TV itself):
 *   - in a multi-window task but the wrong size -> am task resize (its root task)
 *   - anything else (full screen, e.g. opened by the remote's button, or YouTube, which hands itself off to a new
 *     full-screen task as it starts; or freeform)
 *                                         -> adopt(): start a FrameHolder in the hole and move the app's task
 *                                            into it with `am stack move-task`. The app isn't restarted.
 * Needs the TV's freeform/multi-window support on (settings global enable_freeform_support 1).
 * Runs on a background thread; without adb rights it does nothing and the scene crops instead.
 */
final class Framer {
    private static final String TAG = "HomeFramer";
    private static final String MULTI_WINDOW = "multi-window";
    private static final Pattern ROOT = Pattern.compile("^Stack id=(\\d+) bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    private static final Pattern STACK = Pattern.compile("mWindowingMode=([\\w-]+)");
    private static final Pattern TASK = Pattern.compile("taskId=(\\d+): ([\\w.]+)/\\S+ bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\].*visible=(\\w+)");

    private final Context c;
    private long lastAuthTry;
    /** Tasks that wouldn't go into a window: not tried again (a new task of the same app is). */
    private final Set<Integer> failed = new HashSet<>();

    Framer(Context c) { this.c = c.getApplicationContext(); }

    /** Ask the TV once to trust Home's adb key (shows the "Allow debugging?" prompt the first time). */
    boolean authorise(long waitMs) {
        long now = System.currentTimeMillis();
        if (now - lastAuthTry < 30 * 60_000L) return false;   // declined or ignored: don't nag
        lastAuthTry = now;
        return LocalAdb.ready(c, waitMs);
    }

    /**
     * Make sure the app on screen is framed: the topmost visible task of any of `pkgs` (the timed apps that look
     * resumed; that list can hold stale entries, e.g. an app that was killed, so it's the screen that decides).
     * True if it's in its window; false if it isn't on screen, won't go in a window, or there's no adb.
     */
    boolean frame(Collection<String> pkgs) {
        try {
            Rect want = WatchActivity.window(c.getResources());
            Task t = find(pkgs, true);
            if (t == null) return false;
            if (t.mode.equals(MULTI_WINDOW)) { fit(t, want); return true; }
            if (failed.contains(t.id)) return false;
            note(t.pkg + " task " + t.id + " is " + t.mode + ": moving it into a window");
            if (adopt(t, want) && framedNow(t.pkg, want)) { note("framed " + t.pkg); return true; }
            failed.add(t.id);
            note(t.pkg + " task " + t.id + " won't go in a window: leaving it as it is");
            return false;
        } catch (IOException | InterruptedException | RuntimeException e) {
            note("frame " + pkgs + ": " + e);
            return false;
        }
    }

    // What Framer did lately, for DevReceiver STATUS (this TV's logcat doesn't keep Home's lines for long).
    private static final ArrayDeque<String> NOTES = new ArrayDeque<>();
    private static final ArrayDeque<Long> NOTE_TIMES = new ArrayDeque<>();

    private static void note(String s) {
        Log.i(TAG, s);
        synchronized (NOTES) {
            NOTES.addLast(s);
            NOTE_TIMES.addLast(System.currentTimeMillis());
            if (NOTES.size() > 12) { NOTES.removeFirst(); NOTE_TIMES.removeFirst(); }
        }
    }

    static String notes() {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        StringBuilder out = new StringBuilder();
        synchronized (NOTES) {
            Iterator<Long> at = NOTE_TIMES.iterator();
            for (String s : NOTES) out.append(out.length() > 0 ? "\n" : "").append(f.format(new Date(at.next()))).append(' ').append(s);
        }
        return out.toString();
    }

    /** Start a FrameHolder in the hole (a fresh multi-window root task) and move task `t` into it. */
    private boolean adopt(Task t, Rect want) throws IOException, InterruptedException {
        FrameHolder.release();
        c.startActivity(new Intent(c, FrameHolder.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                WatchActivity.windowOptions(c.getResources()));
        try {
            int holder = FrameHolder.awaitTask(5_000);   // its own root task, started in the hole
            if (holder < 0) { note("no holder came up"); return false; }
            resize(holder, want);
            note("moving task " + t.id + " into holder " + holder);
            shell("am stack move-task " + t.id + " " + holder + " true");
            return true;
        } finally {
            FrameHolder.release();
        }
    }

    /** After adopt(): is `pkg` now in a multi-window task? (Gives it a moment to settle.) */
    private boolean framedNow(String pkg, Rect want) throws IOException, InterruptedException {
        for (int k = 0; k < 3; k++) {
            Thread.sleep(300);
            Task n = find(Collections.singleton(pkg), true);
            if (n != null && n.mode.equals(MULTI_WINDOW)) { fit(n, want); return true; }
        }
        return false;
    }

    /**
     * The window's density: while a timed app is in its window (small = true) the whole TV is set to the density
     * that makes the window the 960x540 dp screen every TV app is designed for (the hole is 3/4 of the screen
     * across, so 3/4 of the TV's own density: 240 on a 320 dpi 1080p TV). Apps then lay out exactly as they do
     * full screen, just smaller, instead of squeezing their full-size layout into a 720x405 dp window. It's
     * TV-wide (Android has no per-app density), so it goes back (`wm density reset`) as soon as she leaves.
     */
    void windowDensity(boolean small) {
        try {
            if (small) {
                float share = WatchActivity.window(c.getResources()).width() / (float) c.getResources().getDisplayMetrics().widthPixels;
                int dpi = Math.round(DisplayMetrics.DENSITY_DEVICE_STABLE * share);
                shell("wm density " + dpi);
                note("density " + dpi + " for the window");
            } else {
                shell("wm density reset");
                note("density back to " + DisplayMetrics.DENSITY_DEVICE_STABLE);
            }
        } catch (IOException | RuntimeException e) {
            note("density: " + e);
        }
    }

    /**
     * Grown-up time: open `pkg` full screen. A windowed task of it left over from her time would otherwise come
     * back as the small window, so that one is restarted full screen. False if adb isn't available (the caller
     * then just starts it).
     */
    boolean fullScreen(String pkg) {
        try {
            Intent i = AppStore.launchIntent(c.getPackageManager(), pkg);
            ComponentName cn = i != null ? i.getComponent() : null;
            if (cn == null) return false;
            Task t = find(Collections.singleton(pkg), false);
            String start = "am start --windowingMode 1 -n " + cn.flattenToShortString();
            boolean windowed = t != null && !t.mode.equals("fullscreen");
            shell(windowed ? "am force-stop " + pkg + "; " + start : start);
            return true;
        } catch (IOException | RuntimeException e) {
            note("full screen " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    /** A task as `am stack list` shows it, with the root task ("stack") it sits in. */
    private static final class Task { int id, root; String pkg, mode; Rect rootBounds; }

    /** The topmost (visible, if asked) task whose base activity belongs to one of `pkgs`. */
    private Task find(Collection<String> pkgs, boolean visibleOnly) throws IOException {
        String mode = "";
        int root = -1;
        Rect rootBounds = null;
        for (String line : shell("am stack list").split("\n")) {
            Matcher r = ROOT.matcher(line);
            if (r.find()) {
                root = Integer.parseInt(r.group(1));
                rootBounds = rect(r, 2);
                mode = "";
                continue;
            }
            Matcher s = STACK.matcher(line);
            if (s.find()) { mode = s.group(1); continue; }
            Matcher m = TASK.matcher(line);
            if (m.find() && pkgs.contains(m.group(2)) && (!visibleOnly || m.group(7).equals("true"))) {
                Task t = new Task();
                t.id = Integer.parseInt(m.group(1));
                t.pkg = m.group(2);
                t.root = root >= 0 ? root : t.id;
                t.mode = mode;
                t.rootBounds = rootBounds != null ? rootBounds : rect(m, 3);
                return t;
            }
        }
        return null;
    }

    private static Rect rect(Matcher m, int g) {
        return new Rect(Integer.parseInt(m.group(g)), Integer.parseInt(m.group(g + 1)),
                Integer.parseInt(m.group(g + 2)), Integer.parseInt(m.group(g + 3)));
    }

    /** Resize task `t`'s root to `want` if it isn't already. */
    private void fit(Task t, Rect want) throws IOException { if (!t.rootBounds.equals(want)) resize(t.root, want); }

    private void resize(int task, Rect r) throws IOException {
        shell("am task resize " + task + " " + r.left + " " + r.top + " " + r.right + " " + r.bottom);
    }
    private String shell(String cmd) throws IOException { return LocalAdb.exec(c, cmd, 5_000); }
}
