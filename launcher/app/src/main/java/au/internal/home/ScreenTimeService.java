// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches for the timed apps (TimedApps; YouTube unless Config says otherwise) and the grown-ups-only ones
 * (SmartTube) on screen, and runs the child's day around them:
 *  - counts watch time against today's allowance (not while it's sat paused and silent, not in grown-up time)
 *  - puts the Scene overlay over the screen, with the video showing through its window
 *  - from golden hour, turns on the TV's Blue Light Filter (put back afterwards)
 *  - through nightfall, eases the volume down (put back afterwards, unless someone changed it)
 *  - when the allowance runs out: the night closes over the video, playback pauses, Goodnight takes over
 *
 * Foreground detection uses UsageStatsManager (usage access granted over adb). Overlay and starting
 * Goodnight from the background need "display over other apps" (also granted over adb).
 */
public final class ScreenTimeService extends Service {
    static final String TAG = "HomeScreenTime";
    static final String ACTION_DEV = "au.internal.home.DEV";
    static final String ACTION_CAM_OVERLAY = "au.internal.home.CAM_OVERLAY";
    private static final long TICK_MS = 1_000, SILENT_GRACE_MS = 90_000, CLOSE_MS = 4_000;
    /** How long the window's density stays after the last timed app goes, so a quick switch doesn't flip it. */
    private static final long DENSITY_HOLD_MS = 3_000;
    private static final float VOLUME_FLOOR = 0.4f;   // fraction of her volume left at the very end

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScreenTime st;
    private UsageStatsManager usm;
    private AudioManager audio;
    private PowerManager power;
    private WindowManager wm;
    private SharedPreferences vol, settings;
    private RemoteKeys remoteKeys;
    private TimedApps timed;
    private volatile boolean timedDirty, hasRemoteKeys;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (p, k) -> {
        if (HomeConfig.K_TIMED.equals(k) || k == null) timedDirty = true;
        if (HomeConfig.K_OVERLAY_CORNER.equals(k) || HomeConfig.K_OVERLAY_SIZE.equals(k) || k == null) {
            h.post(() -> { if (this.camOverlay != null) this.camOverlay.reconfigure(); });
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener remoteKeyListener = (p, k) -> {
        if (RemoteKeys.isMapKey(k)) hasRemoteKeys = !remoteKeys.isEmpty();
    };
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { timedDirty = true; }
    };
    private volatile boolean framed;          // the app on screen was in its window when Framer last looked
    private long frameFastUntil;              // check every tick until then: a timed app just came up
    private boolean densitySmall;             // the TV is at the window's density (Framer.windowDensity)
    private long smallUntil;                  // keep that density until then
    private Framer framer;
    private final java.util.concurrent.atomic.AtomicBoolean framing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean checkingKeys = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean checkingOps = new java.util.concurrent.atomic.AtomicBoolean();
    private long lastFrame, lastKeyCheck = -60_000, lastOpsCheck = -60_000;
    private volatile long keyCheckWait = 10_000, opsCheckWait = 10_000;
    private boolean frameViaAdb;

    private final Map<String, String> resumed = new HashMap<>();   // "pkg/class" -> pkg, counted apps
    private final Map<String, String> lockedUp = new HashMap<>();  // same, grown-ups-only apps
    private long lastQuery, lastTick, lastAudio;
    private boolean watching, blueOn, goingToSleep, preview;
    private SceneView overlay;
    private CameraOverlay camOverlay;
    private long lastGoodnight = -10_000;

    // Scrub run (dev): progress animates from -> to over the given time.
    private float runFrom, runTo;
    private long runStart = -1, runMs;

    private static volatile boolean homeOnTop;
    private static volatile int pinnedCam = -1;
    private static volatile String activeOverlayCorner = HomeConfig.CORNER_TR;
    private static volatile ScreenTimeService running;

    static void setHomeOnTop(boolean top) {
        homeOnTop = top;
        ScreenTimeService s = running;
        if (s != null) s.h.post(s::syncCamOverlay);
    }

    static int pinnedCam() { return pinnedCam; }
    static String overlayCorner() { return activeOverlayCorner; }

    /** Toggle, cycle, or set the floating camera overlay (op: "toggle", "cycle", "set", "off"). */
    static void camOverlay(Context c, String op, int cam) {
        try {
            c.startForegroundService(new Intent(c, ScreenTimeService.class)
                    .setAction(ACTION_CAM_OVERLAY).putExtra("op", op).putExtra("cam", cam));
        } catch (RuntimeException e) { Log.w(TAG, "camOverlay failed", e); }
    }

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, ScreenTimeService.class)); }
        catch (RuntimeException e) { Log.w(TAG, "start failed", e); }
    }

    /** A development command for the running service (see dev() and DevReceiver). */
    static Intent dev(Context c, String cmd) {
        return new Intent(c, ScreenTimeService.class).setAction(ACTION_DEV).putExtra("cmd", cmd);
    }

    @Override public void onCreate() {
        super.onCreate();
        st = new ScreenTime(this);
        usm = getSystemService(UsageStatsManager.class);
        audio = getSystemService(AudioManager.class);
        power = getSystemService(PowerManager.class);
        wm = getSystemService(WindowManager.class);
        camOverlay = new CameraOverlay(this, wm, new HomeConfig(this));
        running = this;
        vol = getSharedPreferences("screentime_volume", MODE_PRIVATE);
        settings = getApplicationContext().getSharedPreferences("settings", MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(settingsListener);
        remoteKeys = new RemoteKeys(this);
        remoteKeys.register(remoteKeyListener);
        hasRemoteKeys = !remoteKeys.isEmpty();
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(pkgReceiver, pkgFilter);
        timed = new TimedApps(this);
        framer = new Framer(this);
        frameViaAdb = getResources().getBoolean(R.bool.frame_via_adb);
        // Left lowered (Home stopped while a timed app was up)? Then the first tick puts it back.
        densitySmall = getResources().getConfiguration().densityDpi != android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
        if (frameViaAdb) worker.execute(() -> framer.authorise(120_000));   // first time only: the TV asks to allow Home's adb key

        goForeground();

        restoreVolume();   // in case we died mid-fade
        lastQuery = System.currentTimeMillis() - 30 * 60_000L;
        lastTick = SystemClock.elapsedRealtime();
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        goForeground();   // every start: each startForegroundService() call must be answered, not just the first
        if (i != null) {
            if (ACTION_DEV.equals(i.getAction())) dev(i);
            else if (ACTION_CAM_OVERLAY.equals(i.getAction())) handleCamOverlay(i);
        }
        return START_STICKY;
    }

    private void handleCamOverlay(Intent i) {
        if (camOverlay == null) return;
        String op = i.getStringExtra("op");
        int cam = i.getIntExtra("cam", -1);
        if ("toggle".equals(op)) pinnedCam = camOverlay.toggle(cam);
        else if ("cycle".equals(op)) pinnedCam = camOverlay.cycle();
        else if ("off".equals(op)) pinnedCam = camOverlay.pin(CameraOverlay.MODE_OFF);
        else pinnedCam = camOverlay.pin(cam);
        activeOverlayCorner = camOverlay.activeCorner();
        syncCamOverlay();
    }

    private void syncCamOverlay() {
        if (camOverlay == null) return;
        boolean canShow = power != null && power.isInteractive() && !homeOnTop && !goingToSleep;
        camOverlay.sync(canShow);
        pinnedCam = camOverlay.pinned();
        activeOverlayCorner = camOverlay.activeCorner();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("st", "Screen time", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "st").setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Parental controls").setOngoing(true).build());
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener);
        remoteKeys.unregister(remoteKeyListener);
        unregisterReceiver(pkgReceiver);
        worker.shutdownNow();
        running = null;
        if (camOverlay != null) { camOverlay.destroy(); camOverlay = null; }
        pinnedCam = -1;
        hideOverlay(false);
        restoreVolume();
        super.onDestroy();
    }

    // ---------- the loop ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (RuntimeException e) { Log.w(TAG, "tick", e); }
            h.postDelayed(this, TICK_MS);
        }
    };

    private void step() {
        long now = System.currentTimeMillis(), el = SystemClock.elapsedRealtime();
        long dt = Math.min(5_000, el - lastTick);
        lastTick = el;
        // Pick up Config changes and newly installed apps only when they actually change.
        if (timedDirty) { timedDirty = false; timed = new TimedApps(this); }
        updateResumed(now);

        boolean on = power.isInteractive();
        boolean appUp = on && !homeOnTop && !resumed.isEmpty();
        boolean grown = !st.demo() && st.grownUp(now);
        if (appUp && !watching) lastAudio = el;   // count the first stretch while it starts up
        watching = appUp;

        boolean kid = watching && !grown;
        // Grown-ups-only apps (SmartTube): without grown-up time, straight back to Home.
        if (on && !homeOnTop && !grown && !lockedUp.isEmpty()) {
            lockedUp.clear();
            startActivity(MainActivity.home(this).putExtra(MainActivity.EXTRA_LOCKED, true));
        }
        boolean windowed = frameViaAdb && kid && !goingToSleep;
        // The window's density first (queued before any framing, so the app lays out once at it): on as soon as a
        // timed app is up, back off DENSITY_HOLD_MS after none is.
        if (windowed) smallUntil = el + DENSITY_HOLD_MS;
        boolean wantSmall = el < smallUntil;
        if (frameViaAdb && wantSmall != densitySmall) {
            densitySmall = wantSmall;
            worker.execute(() -> framer.windowDensity(wantSmall));
        }
        // Self-heal SYSTEM_ALERT_WINDOW and GET_USAGE_STATS over LocalAdb (Android 14 / TCL resets appops on reboot/reinstall).
        if (!hasAppOps() && el - lastOpsCheck > opsCheckWait && checkingOps.compareAndSet(false, true)) {
            lastOpsCheck = el;
            Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    String pkg = app.getPackageName();
                    LocalAdb.exec(app, "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                            + "appops set " + pkg + " GET_USAGE_STATS allow", 5_000);
                    opsCheckWait = 10_000;
                } catch (java.io.IOException | RuntimeException e) {
                    Log.w(TAG, "appops heal failed: " + e.getMessage());
                    opsCheckWait = Math.min(5 * 60_000L, opsCheckWait * 2);
                } finally {
                    checkingOps.set(false);
                }
            }, "appops-heal").start();
        }
        // Keep RemoteKeyService bound when buttons are mapped (off the framing worker so binds never stall Framer).
        if (hasRemoteKeys && !RemoteKeyService.isConnected() && el - lastKeyCheck > keyCheckWait
                && checkingKeys.compareAndSet(false, true)) {
            lastKeyCheck = el;
            Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    boolean ok = RemoteKeys.ensureService(app);
                    keyCheckWait = ok ? 10_000 : Math.min(5 * 60_000L, keyCheckWait * 2);
                } finally {
                    checkingKeys.set(false);
                }
            }, "remote-keys-heal").start();
        }
        // Keep the timed app in its window (just opened, or resized/restarted by something else).
        // Every 4 s; every tick for a while after a timed app comes up (e.g. YouTube just after it starts, or after it
        // hands itself off to a new task) until it's framed. Framer works out which resumed timed app is on screen.
        boolean due = el - lastFrame > 4_000 || el < frameFastUntil && !framed;
        if (windowed && due && !framing.get() && !resumed.isEmpty()) {
            lastFrame = el;
            final java.util.Set<String> pkgs = new java.util.HashSet<>(resumed.values());
            framing.set(true);
            worker.execute(() -> {
                try { framed = framer.frame(pkgs); }
                finally { framing.set(false); }
            });
        }
        if (kid && audio.isMusicActive()) lastAudio = el;
        if (kid && !goingToSleep && el - lastAudio < SILENT_GRACE_MS && !st.scrubbing()) st.addUsed(now, dt);

        float p = progress(now);
        // Night closes slowly if she was watching as it ran out; quickly if a timed app was just opened while asleep.
        if (kid && p >= 1f && !goingToSleep && el - lastGoodnight > 5_000) goodnight(overlay != null ? CLOSE_MS : 1_200);

        if ((kid || preview) && !goingToSleep) showOverlay(); else if (!goingToSleep) hideOverlay(true);
        syncCamOverlay();
        setSceneProgress(now, p);
        if (kid && !goingToSleep) { fadeVolume(p); warm(p >= ScreenTime.GOLDEN); }
        else if (!goingToSleep) { restoreVolume(); warm(false); }
    }

    private boolean hasAppOps() {
        if (!Settings.canDrawOverlays(this)) return false;
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private float progress(long now) {
        if (runStart >= 0) {
            float f = Math.min(1f, (SystemClock.elapsedRealtime() - runStart) / (float) runMs);
            if (f >= 1f) { runStart = -1; st.setScrub(runTo); }
            return runFrom + (runTo - runFrom) * f;
        }
        return st.progress(now);
    }

    private void setSceneProgress(long now, float p) {
        if (overlay == null) return;
        overlay.scene.p = p;
        overlay.scene.minutesLeft = st.minutesLeft(now, p);
    }

    /** Keep `resumed` = the tracked apps' activities currently resumed, from the usage event stream. */
    private void updateResumed(long now) {
        UsageEvents ev;
        try { ev = usm.queryEvents(lastQuery, now + 1); }
        catch (RuntimeException e) { return; }
        lastQuery = now;
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.getNextEvent(e)) {
            String pkg = e.getPackageName();
            Map<String, String> into = timed.timed(pkg) ? resumed : timed.grownUp(pkg) ? lockedUp : null;
            if (into == null) continue;
            String key = pkg + "/" + e.getClassName();
            switch (e.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    into.put(key, pkg);
                    if (into == resumed) { frameFastUntil = SystemClock.elapsedRealtime() + 10_000; framed = false; }
                    break;
                // Not on PAUSED: a paused app can still be on screen (in its window, under a FrameHolder for a moment).
                case UsageEvents.Event.ACTIVITY_STOPPED: into.remove(key); break;
            }
        }
    }

    // ---------- overlay ----------

    private void showOverlay() {
        if (overlay != null) return;
        if (!Settings.canDrawOverlays(this)) { Log.w(TAG, "no overlay permission"); return; }
        overlay = new SceneView(this, Scene.Mode.OVERLAY);
        long now = System.currentTimeMillis();
        setSceneProgress(now, progress(now));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("HomeScene");
        overlay.setAlpha(0f);
        try { wm.addView(overlay, lp); }
        catch (RuntimeException e) { Log.w(TAG, "overlay", e); overlay = null; return; }
        overlay.animate().alpha(1f).setDuration(900).start();
    }

    private void hideOverlay(boolean fade) {
        SceneView v = overlay;
        if (v == null) return;
        overlay = null;
        Runnable remove = () -> { try { wm.removeView(v); } catch (RuntimeException ignored) { } };
        if (fade) v.animate().alpha(0f).setDuration(600).withEndAction(remove).start(); else remove.run();
    }

    // ---------- goodnight ----------

    /** Close the night over the video, pause it, show Goodnight, then tidy up behind it. */
    private void goodnight(long closeMs) {
        goingToSleep = true;
        showOverlay();
        long closeStart = SystemClock.elapsedRealtime();
        Runnable closing = new Runnable() {
            @Override public void run() {
                float f = Math.min(1f, (SystemClock.elapsedRealtime() - closeStart) / (float) closeMs);
                if (overlay != null) overlay.scene.close = Scene.smooth(f);
                if (f < 1f) { h.postDelayed(this, 50); return; }
                pause();
                lastGoodnight = SystemClock.elapsedRealtime();
                startActivity(new Intent(ScreenTimeService.this, GoodnightActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                h.postDelayed(() -> {
                    hideOverlay(false);
                    restoreVolume();
                    warm(false);
                    goingToSleep = false;
                }, 1_500);
            }
        };
        h.post(closing);
    }

    private void pause() {
        long t = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
    }

    // ---------- volume ----------

    /** From nightfall, ease the volume down towards VOLUME_FLOOR of where she had it. */
    private void fadeVolume(float p) {
        if (p < ScreenTime.NIGHT) { restoreVolume(); return; }
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (!vol.contains("orig")) vol.edit().putInt("orig", cur).putInt("set", -1).putBoolean("user", false).apply();
        if (vol.getBoolean("user", false)) return;
        int set = vol.getInt("set", -1);
        if (set >= 0 && cur != set) { vol.edit().putBoolean("user", true).apply(); return; }   // someone used the remote: leave it
        int target = Math.round(vol.getInt("orig", cur) * (1f - (1f - VOLUME_FLOOR) * Scene.ramp(p, ScreenTime.NIGHT, 1)));
        if (target != cur) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            vol.edit().putInt("set", target).apply();
        } else if (set < 0) vol.edit().putInt("set", cur).apply();
    }

    private void restoreVolume() {
        if (!vol.contains("orig")) return;
        if (!vol.getBoolean("user", false)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol.getInt("orig", 0), 0);
        vol.edit().clear().apply();
    }

    // ---------- warmth: the TV's own Blue Light Filter ----------

    private void warm(boolean on) {
        if (on == blueOn) return;
        blueOn = on;
        Context app = getApplicationContext();
        worker.execute(() -> TvBlueLight.set(app, on || Bedtime.wantsBlue(app)));
    }

    // ---------- development controls (see DevReceiver) ----------

    private void dev(Intent i) {
        String cmd = i.getStringExtra("cmd");
        long now = System.currentTimeMillis();
        if (cmd == null) return;
        switch (cmd) {
            case "SCRUB": {
                float p = i.getFloatExtra("p", -1f);
                if (i.hasExtra("to")) startRun(p < 0 ? st.progress(now) : p, i.getFloatExtra("to", 1f), Math.max(1, i.getIntExtra("secs", 60)));
                else { runStart = -1; st.setScrub(p); startSmooth(); }
                break;
            }
            case "LIVE": runStart = -1; st.setScrub(-1); st.setDemo(false); preview = false; break;
            case "DEMO":   // the whole day, morning to Goodnight, over `secs` (default 10); no real minutes used
                st.setDemo(true);
                startRun(0f, 1f, Math.max(10, i.getIntExtra("secs", 10)));
                break;
            case "PREVIEW": preview = i.getBooleanExtra("on", true); break;
        }
        step();
    }

    /** Animate progress from -> to over `secs`. */
    private void startRun(float from, float to, int secs) {
        runFrom = from;
        runTo = to;
        runMs = secs * 1000L;
        runStart = SystemClock.elapsedRealtime();
        st.setScrub(from);
        startSmooth();
    }

    private final Runnable smooth = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            setSceneProgress(now, progress(now));
            if (runStart >= 0) h.postDelayed(this, 50);
        }
    };

    /** While a scrub run is going, nudge the scene every frame rather than once a second. */
    private void startSmooth() {
        h.removeCallbacks(smooth);
        h.post(smooth);
    }
}
