// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    static final String EXTRA_LOCKED = "au.internal.home.LOCKED";
    static final String EXTRA_UNLOCK_PKG = "au.internal.home.UNLOCK_PKG";
    static final long GROWN_UP_MS = 12 * 3_600_000L;   // ends sooner: when you come back Home
    // Grid is 12 spans wide. Beside the cameras: favourites two per row, other apps three. Across the whole screen
    // (no cameras): four and six, so the tiles stay the same size.
    private static final int SPANS = 12;
    private static final int T_HEADER = 0, T_FAV = 1, T_APP = 2;
    private static final long TICK_MS = 30_000, WEATHER_MS = 15 * 60_000, LABEL_FADE_MS = 5_000;

    private static volatile MainActivity active;
    private static volatile boolean assetsSeeded;

    /** Step the visible World Cam card forward (dir > 0) or backward (dir < 0). */
    static void stepWorldCams(Context c, int dir) {
        MainActivity a = active;
        if (a != null && a.hasWindowFocus()) {
            a.h.post(() -> a.advanceWorldCams(dir));
            return;
        }
        WorldCams wc = new WorldCams(c);
        List<WorldCams.Stream> all = wc.load();
        if (all.isEmpty()) return;
        List<WorldCams.Stream> pool = wc.activePool(all);
        if (dir < 0) wc.prev(0, pool, null); else wc.next(0, pool, null);
    }

    /** This launcher's own Home screen, startable from anywhere. */
    static Intent home(Context c) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static final class Row {
        final int type; final String title; final AppStore.App app;
        Row(int type, String title, AppStore.App app) { this.type = type; this.title = title; this.app = app; }
    }

    /** Tiles per row for this row's section. */
    private int cols(Row r) { return r.type == T_FAV ? (wide ? 4 : 2) : (wide ? 6 : 3); }

    private AppStore store;
    private SharedPreferences prefs;
    private final List<AppStore.App> all = new ArrayList<>();   // full ordered list
    private final List<Row> rows = new ArrayList<>();           // what the grid displays
    private boolean showHidden;
    private String movingPkg;                                   // non-null while in move mode
    private int hiddenCount;

    private RecyclerView grid;
    private TextView greeting, weather;
    private View bins;
    private Sky sky;
    private final Adapter adapter = new Adapter();
    private final Handler h = new Handler(Looper.getMainLooper());
    private CameraFeed[] feeds;
    private WebCamHost webHost;
    private WorldCams worldCams;
    private List<WorldCams.Stream> worldCatalog = Collections.emptyList();
    private WorldCams.Stream[] worldSlots = new WorldCams.Stream[2];
    private boolean[] camIsWorld;
    private View[] camViews, camLabels;
    private int[] camLabelTopPad, camLabelStartPad;
    private final Runnable[] camStarters = new Runnable[2];
    private View fullscreenCam;
    private boolean leftHome;
    private static final long RANDOM_PREFETCH_LEAD_MS = 10_000L;
    private TextView worldTimer;
    private long worldTimerDeadlineMs;
    private WorldCams.Stream pendingRandomStream;
    private long lastWeather;
    private int lastSunDay = -1;
    private double lastSunLat = Double.NaN, lastSunLon = Double.NaN;
    private String lastTimeSig;
    private ScreenTime screenTime;
    private TimedApps timedApps;
    private View scrim;
    private SceneTile sceneTile;
    private boolean leftDuringPass;
    private HomeConfig cfg;
    private boolean[] camOn;
    private Look look;
    private boolean wide;           // no cameras: apps across the whole screen
    private Glow glow;
    private ImageView modeBtn;
    private View grownUps;
    private TextView cornerLabel;
    private Boolean modeBtnAdult;
    /** Open dialogs owned by Home (Config, ParentGate, app menu, etc.): dismissed when Home is pressed. */
    private static final java.util.List<java.lang.ref.WeakReference<AlertDialog>> OPEN_DIALOGS = new ArrayList<>();

    static void trackDialog(AlertDialog d) {
        OPEN_DIALOGS.removeIf(ref -> { AlertDialog x = ref.get(); return x == null || !x.isShowing(); });
        OPEN_DIALOGS.add(new java.lang.ref.WeakReference<>(d));
    }

    static AlertDialog showDialog(AlertDialog.Builder b) { return showDialog(b.create()); }

    static AlertDialog showDialog(AlertDialog d) {
        d.show();
        trackDialog(d);
        return d;
    }

    static void dismissDialogs() {
        List<AlertDialog> open = new ArrayList<>();
        for (java.lang.ref.WeakReference<AlertDialog> ref : OPEN_DIALOGS) {
            AlertDialog d = ref.get();
            if (d != null && d.isShowing()) open.add(d);
        }
        OPEN_DIALOGS.clear();
        for (AlertDialog d : open) {
            try { d.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        active = this;
        setContentView(R.layout.activity_main);
        store = new AppStore(this);
        cfg = new HomeConfig(this);
        if (!assetsSeeded) {
            assetsSeeded = true;
            Context appCtx = getApplicationContext();
            new Thread(() -> {
                Restore.seedIfBundled(appCtx);
                CustomScene.seedIfBundled(appCtx);
            }, "seed-private").start();
        }
        screenTime = new ScreenTime(this);
        timedApps = new TimedApps(this);
        // Hides Home while a timed app's window sits on top of it (Home stays visible behind it, and may be
        // recreated there when Framer changes density; onTopResumedActivityChanged(true) clears it when Home is on top).
        scrim = new View(this);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(1f);
        scrim.setVisibility(View.VISIBLE);
        ((ViewGroup) getWindow().getDecorView()).addView(scrim, new ViewGroup.LayoutParams(-1, -1));
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        greeting = findViewById(R.id.greeting);
        weather = findViewById(R.id.weather);
        bins = findViewById(R.id.bins);
        float density = getResources().getDisplayMetrics().density;
        sky = new Sky();
        findViewById(R.id.root).setBackground(sky);
        grid = findViewById(R.id.grid);
        grid.addItemDecoration(glow = new Glow(density));
        GridLayoutManager glm = new GridLayoutManager(this, SPANS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int pos) {
                Row r = rows.get(pos);
                return r.type == T_HEADER ? SPANS : SPANS / cols(r);
            }
        });
        grid.setLayoutManager(glm);
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);

        camViews = new View[]{findViewById(R.id.cam1), findViewById(R.id.cam2)};
        camLabels = new View[2];
        camLabelTopPad = new int[2];
        camLabelStartPad = new int[2];
        camOn = new boolean[2];
        camIsWorld = new boolean[2];
        feeds = new CameraFeed[2];
        worldCams = new WorldCams(this);
        if (cfg.camerasOn() && cfg.worldCamsOn()) {
            worldCatalog = worldCams.load();
            worldCams.refreshIfDue(cfg.worldCamsUrl(), () -> worldCatalog = worldCams.load());
        }
        Runnable[] webOnLive = new Runnable[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            camLabels[i] = camViews[i].findViewById(R.id.label);
            camLabelTopPad[i] = camLabels[i].getPaddingTop();
            camLabelStartPad[i] = camLabels[i].getPaddingStart();
            webOnLive[i] = () -> { if (!camViews[idx].hasFocus()) fadeLabel(camLabels[idx], LABEL_FADE_MS); };
        }
        webHost = new WebCamHost(this, findViewById(R.id.webBackdrop), camViews, webOnLive);
        boolean hasAnyLocal = !cfg.camUrl(0).isEmpty() || !cfg.camUrl(1).isEmpty();
        boolean worldMode = cfg.camerasOn() && cfg.worldCamsOn() && !worldCatalog.isEmpty()
                && (!hasAnyLocal || worldCams.slotUsesWorld(0));
        for (int i = 0; i < 2; i++) {
            View v = camViews[i];
            View label = camLabels[i];
            boolean hasLocal = !cfg.camUrl(i).isEmpty();
            boolean useWorld = worldMode && i == 0;
            camIsWorld[i] = useWorld;
            camOn[i] = cfg.camerasOn() && (worldMode ? useWorld : hasLocal);
            v.setVisibility(camOn[i] ? View.VISIBLE : View.GONE);
            final int idx = i;
            if (hasLocal && !worldMode) {
                feeds[i] = new CameraFeed(this, cfg.camUrl(i), cfg.camHdUrl(i),
                        (TextureView) v.findViewById(R.id.surface), v.findViewById(R.id.state),
                        () -> { if (!camViews[idx].hasFocus()) fadeLabel(label, LABEL_FADE_MS); });
            }
            if (useWorld) {
                if (worldCams.isPinned(i) || !worldCams.randomOnReturn()) {
                    worldSlots[i] = worldCams.slotStream(i, worldCatalog);
                } else {
                    worldSlots[i] = worldCams.shuffle(i, activeWorldPool(), otherWorldKey(i));
                }
                v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                v.findViewById(R.id.surface).setVisibility(View.GONE);
                updateWorldLabel(i);
                webHost.setSlot(i, true, worldSlots[i] != null ? worldSlots[i].embedUrl() : null);
            } else {
                ((TextView) v.findViewById(R.id.name)).setText(cfg.camName(i));
            }
            v.setOnClickListener(this::toggleFullscreen);
            v.setOnLongClickListener(x -> {
                if (camIsWorld[idx]) worldCamMenu(idx);
                else localCamMenu(idx);
                return true;
            });
            v.setOnFocusChangeListener((x, has) -> {
                boolean isLive = camIsWorld[idx]
                        ? webHost.isLive(idx)
                        : (feeds[idx] != null && feeds[idx].isLive());
                if (has) showLabel(label);
                else if (isLive) fadeLabel(label, 2_000);
            });
        }
        findViewById(R.id.camGap).setVisibility(camOn[0] && camOn[1] ? View.VISIBLE : View.GONE);
        findViewById(R.id.rightWrap).setVisibility(camOn[0] || camOn[1] ? View.VISIBLE : View.GONE);
        updateWorldPrefetch();
        worldTimer = findViewById(R.id.worldTimer);
        if (worldTimer != null) {
            setupWorldTimerView(density);
            updateWorldTimerUi();
        }
        wide = !camOn[0] && !camOn[1];
        setLook(cfg.look());

        // Top corner, up from the top row of apps: Cameras toggle, Adult/Kids toggle, Kids' time, Remote buttons, and Settings.
        View corner = findViewById(R.id.corner);
        cornerLabel = findViewById(R.id.cornerLabel);
        ImageView camBtn = findViewById(R.id.camBtn);
        modeBtn = findViewById(R.id.modeBtn);
        View timeBtn = findViewById(R.id.timeBtn);
        View remoteBtn = findViewById(R.id.remoteBtn);
        grownUps = findViewById(R.id.grownups);
        updateCamButton(camBtn);
        bindCorner(camBtn, this::cycleCameraPanel, null, modeBtn);
        bindCorner(modeBtn, this::toggleMode, camBtn, timeBtn);
        bindCorner(timeBtn, () -> ParentGate.ask(this, this::kidsTime), modeBtn, remoteBtn);
        bindCorner(remoteBtn, () -> ParentGate.ask(this, this::remoteButtons), timeBtn, grownUps);
        bindCorner(grownUps, () -> ParentGate.ask(this, this::settings), remoteBtn, null);
        updateModeButton(screenTime.adultMode(System.currentTimeMillis()));
        // The corner buttons exist before the app tiles do, so Android would give them first focus: start on the apps.
        grid.post(() -> { if (getCurrentFocus() == null || corner.hasFocus()) focus(firstAppPos()); });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        reload();

        // Gentle fade-in when the launcher is first created (i.e. at boot).
        View root = findViewById(R.id.root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(350).setStartDelay(50).start();

        String unlockPkg = getIntent().getStringExtra(EXTRA_UNLOCK_PKG);
        if (unlockPkg != null) {
            getIntent().removeExtra(EXTRA_UNLOCK_PKG);
            h.post(() -> unlockAndOpen(unlockPkg));
        }
    }

    private void bindCorner(View btn, Runnable onClick, View left, View right) {
        btn.setOnClickListener(v -> onClick.run());
        btn.setOnFocusChangeListener((v, has) -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            TileFx.animate(v, has ? 1.08f : 1f, 0);
            if (has) {
                grid.smoothScrollToPosition(0);
                cornerLabel.setText(v.getContentDescription());
                cornerLabel.animate().cancel();
                cornerLabel.animate().alpha(1f).setDuration(140).start();
            } else {
                // Moving to another corner button fires its has=true callback synchronously right after, cancelling this fade.
                cornerLabel.animate().cancel();
                cornerLabel.animate().alpha(0f).setDuration(140).start();
            }
        });
        btn.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focus(firstAppPos()); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (left != null) left.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (right != null) right.requestFocus(); return true; }
            return false;
        });
    }

    @Override protected void onDestroy() {
        if (active == this) active = null;
        dismissDialogs();
        h.removeCallbacksAndMessages(null);
        unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        // Cameras and the app list are (re)started in onTopResumedActivityChanged, which always follows.
        Bedtime.catchUp(this, null);
        Bedtime.schedule(this);
        ScreenTimeService.start(this);
        tick.run();
    }

    @Override protected void onStop() {
        ScreenTimeService.setHomeOnTop(false);
        h.removeCallbacks(tick);
        stopWorldTimer();
        WorldCams.stopChecker();
        stopCams();   // free the decoders for whatever app is opened
        if (movingPkg != null) endMove();
        super.onStop();
    }

    /** A timed app's window on top leaves Home visible but not on top: dim it and free the camera decoders. */
    @Override public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        ScreenTimeService.setHomeOnTop(top);
        scrim.animate().cancel();
        if (top) {
            if (scrim.getVisibility() == View.VISIBLE) {
                scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
            }
        } else {
            scrim.setVisibility(View.VISIBLE);
            scrim.animate().alpha(1f).setDuration(350).start();
        }
        long now = System.currentTimeMillis();
        if (top) {
            // A one-app grown-up pass ends when you come back Home; persistent Adult mode stays on until toggled/scheduled off.
            if (screenTime.demo()) {
                screenTime.setScrub(-1f);
                screenTime.setDemo(false);
                startService(ScreenTimeService.dev(this, "LIVE"));
            }
            if (cfg.camerasOn() && cfg.worldCamsOn()) {
                worldCams.refreshIfDue(cfg.worldCamsUrl(), () -> {
                    worldCatalog = worldCams.load();
                    if (hasWindowFocus()) WorldCams.startChecker(worldCatalog);
                });
                if (!worldCatalog.isEmpty()) WorldCams.startChecker(worldCatalog);
            }
            if (leftHome) {
                leftHome = false;
                if (!worldCatalog.isEmpty() && worldCams.randomOnReturn()) {
                    List<WorldCams.Stream> pool = activeWorldPool();
                    for (int i = 0; i < 2; i++) {
                        if (camIsWorld[i] && !worldCams.isPinned(i)) {
                            WorldCams.Stream pick = worldCams.shuffle(i, pool, otherWorldKey(i));
                            if (pick != null) {
                                worldSlots[i] = pick;
                                updateWorldLabel(i);
                                webHost.setSlot(i, true, pick.embedUrl());
                            }
                        }
                    }
                }
            }
            resetWorldTimer();
            updateWorldPrefetch();
            boolean adult = screenTime.adultMode(now);
            boolean passEnded = leftDuringPass && screenTime.hasPass(now);
            if (passEnded) screenTime.setGrownUp(0);
            leftDuringPass = false;
            updateModeButton(adult);
            startCams();
            if (movingPkg == null) {
                timedApps = new TimedApps(this);
                String sig = timeSig(now, adult);
                if (!sig.equals(lastTimeSig)) {
                    lastTimeSig = sig;
                    refreshRuledTiles();
                }
            }
        } else {
            leftHome = true;
            if (screenTime.hasPass(now)) leftDuringPass = true;
            stopWorldTimer();
            WorldCams.stopChecker();
            stopCams();
        }
    }

    /** Defer and stagger camera startup so returning Home paints and takes D-pad input immediately. */
    private void startCams() {
        boolean anyWorld = false;
        for (int i = 0; i < camViews.length; i++) {
            if (camStarters[i] != null) h.removeCallbacks(camStarters[i]);
            if (camOn[i] && camViews[i].getVisibility() == View.VISIBLE) {
                if (camIsWorld[i]) {
                    showLabel(camLabels[i]);
                    anyWorld = true;
                } else {
                    final int idx = i;
                    camStarters[i] = () -> { showLabel(camLabels[idx]); if (feeds[idx] != null) feeds[idx].start(); };
                    h.postDelayed(camStarters[i], 120L + i * 140L);
                }
            }
        }
        if (anyWorld && webHost != null) h.postDelayed(webHost::start, 140L);
    }

    private void stopCams() {
        for (int i = 0; i < camViews.length; i++) {
            if (camStarters[i] != null) { h.removeCallbacks(camStarters[i]); camStarters[i] = null; }
            if (feeds[i] != null) feeds[i].stop();
        }
        if (webHost != null) webHost.stop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String unlockPkg = intent.getStringExtra(EXTRA_UNLOCK_PKG);
        // Home pressed: close any open grown-up/Config/app menu, drop out of any mode, and go to the first tile.
        dismissDialogs();
        if (fullscreenCam != null) toggleFullscreen(fullscreenCam);
        if (movingPkg != null) endMove();
        if (showHidden) { showHidden = false; rebuild(); }
        focus(firstAppPos());
        if (unlockPkg != null) unlockAndOpen(unlockPkg);
    }

    /** A remote button mapped to a grown-ups-only app: reuse launch() so it asks for the code when needed. */
    private void unlockAndOpen(String pkg) {
        String label = "that app";
        for (AppStore.App a : all) if (a.pkg.equals(pkg)) { label = a.label; break; }
        launch(pkg, label);
    }

    @Override public void onBackPressed() {
        if (fullscreenCam != null) toggleFullscreen(fullscreenCam);
        else if (movingPkg != null) endMove();
        else if (showHidden) { showHidden = false; rebuild(); }
        // otherwise: we're home, nowhere to go back to
    }

    /** Put a theme on Home: straight away, also while it's only being tried in Config's theme list. */
    void setLook(Look l) {
        look = l;
        l.apply(findViewById(R.id.root), sky, wide);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(l.bg));
        glow.setLook(l);
        adapter.notifyDataSetChanged();
    }

    // ---------- clock-driven bits: greeting, bedtime, weather ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int dayKey = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR);
            double lat = cfg.lat(), lon = cfg.lon();
            if (dayKey != lastSunDay || lat != lastSunLat || lon != lastSunLon) {
                lastSunDay = dayKey; lastSunLat = lat; lastSunLon = lon;
                sky.setSun(lat, lon, cal);
            }
            sky.setTime(hour * 60 + cal.get(Calendar.MINUTE));
            Bins.show(bins, Bins.tonight(cal, cfg.binsDay(), cfg.binsAnchor(), cfg.binsEvery()));
            greeting.setText(greeting(hour, cfg.familyName()));
            long now = System.currentTimeMillis();
            weather.setVisibility(cfg.place().isEmpty() ? View.GONE : View.VISIBLE);
            if (!cfg.place().isEmpty() && now - lastWeather > WEATHER_MS) {
                lastWeather = now;
                Weather.fetch(Double.toString(lat), Double.toString(lon), cfg.tz(), w -> {
                            if (w == null) { lastWeather = 0; return; }   // retry next tick
                            weather.setText(w.text);
                            weather.setCompoundDrawablesRelativeWithIntrinsicBounds(Weather.icon(w.code, w.day), 0, 0, 0);
                            sky.setMood(Sky.moodFor(w.code));
                        });
            }
            if (cfg.camerasOn() && cfg.worldCamsOn()) {
                worldCams.refreshIfDue(cfg.worldCamsUrl(), () -> {
                    worldCatalog = worldCams.load();
                    if (hasWindowFocus()) WorldCams.startChecker(worldCatalog);
                });
            }
            boolean adult = screenTime.adultMode(now);
            updateModeButton(adult);
            String sig = timeSig(now, adult);
            if (!sig.equals(lastTimeSig)) {
                lastTimeSig = sig;
                refreshRuledTiles();
            }
            h.removeCallbacks(this);
            h.postDelayed(this, TICK_MS);
        }
    };

    private String timeSig(long now, boolean adult) {
        float p = screenTime.progress(now);
        boolean grown = adult || screenTime.hasPass(now);
        return adult + ":" + grown + ":" + (!grown && p >= 1f) + ":" + screenTime.scrubbing()
                + ":" + screenTime.minutesLeft(now, p) + ":" + Math.round(p * 100);
    }

    private void refreshRuledTiles() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.app != null && ruled(r.app.pkg)) adapter.notifyItemChanged(i);
        }
    }

    // ---------- cameras ----------

    private static void showLabel(View label) {
        label.animate().cancel();
        label.animate().alpha(1f).setStartDelay(0).setDuration(200).start();
    }

    private static void fadeLabel(View label, long delay) {
        label.animate().cancel();
        label.animate().alpha(0f).setStartDelay(delay).setDuration(600).start();
    }

    private void toggleFullscreen(View cam) {
        boolean goFull = fullscreenCam == null;
        fullscreenCam = goFull ? cam : null;
        int vis = goFull ? View.GONE : View.VISIBLE;
        findViewById(R.id.left).setVisibility(vis);
        findViewById(R.id.camGap).setVisibility(goFull || !(camOn[0] && camOn[1]) ? View.GONE : View.VISIBLE);
        View right = findViewById(R.id.right);
        float d = getResources().getDisplayMetrics().density;
        right.setPadding(goFull ? 0 : (int) (10 * d), 0, goFull ? 0 : (int) (12 * d), 0);
        cam.setClipToOutline(!goFull);   // square corners when it fills the screen
        int fullSlot = -1;
        for (int i = 0; i < camViews.length; i++) {
            if (camViews[i] == cam) {
                fullSlot = i;
                if (!camIsWorld[i] && feeds[i] != null) feeds[i].setHd(goFull); // 1080p only when RTSP fills the screen
            } else if (camOn[i]) {
                camViews[i].setVisibility(vis);
                if (camIsWorld[i]) {
                    if (!goFull) showLabel(camLabels[i]);
                    webHost.setSlot(i, !goFull, worldSlots[i] != null ? worldSlots[i].embedUrl() : null);
                } else {
                    if (goFull) { if (feeds[i] != null) feeds[i].stop(); }
                    else { showLabel(camLabels[i]); if (feeds[i] != null) feeds[i].start(); }
                }
            }
        }
        if (webHost != null) webHost.setFullscreen(goFull);
        if (goFull && fullSlot >= 0 && camIsWorld[fullSlot]) {
            if (webHost != null) webHost.setMuted(fullSlot, false);
            showLabel(camLabels[fullSlot]);
            fadeLabel(camLabels[fullSlot], 2_500);
        }
        for (int i = 0; i < 2; i++) if (camIsWorld[i]) updateWorldLabel(i);
        updateWorldTimerUi();
        cam.requestFocus();
    }

    private void updateCamButton(ImageView camBtn) {
        boolean anyOn = camOn[0] || camOn[1];
        boolean allWorld = anyOn && (!camOn[0] || camIsWorld[0]) && (!camOn[1] || camIsWorld[1]);
        boolean hasLocal = !cfg.camUrl(0).isEmpty() || !cfg.camUrl(1).isEmpty();
        int icon;
        String desc;
        if (!anyOn) {
            icon = R.drawable.ic_cam_off;
            desc = hasLocal ? "Show cameras" : "Show World cam";
        } else if (allWorld) {
            icon = R.drawable.ic_world;
            desc = "Hide cameras (full-width apps)";
        } else {
            icon = R.drawable.ic_cam;
            desc = "Switch to World cam";
        }
        camBtn.setImageResource(icon);
        camBtn.setContentDescription(desc);
    }

    /** Cycle right-hand panel: Local RTSP (if any) -> 1x World Cam -> Off (full-width apps) -> repeat. */
    private void cycleCameraPanel() {
        boolean anyOn = camOn[0] || camOn[1];
        boolean allWorld = anyOn && (!camOn[0] || camIsWorld[0]) && (!camOn[1] || camIsWorld[1]);
        boolean hasLocal = !cfg.camUrl(0).isEmpty() || !cfg.camUrl(1).isEmpty();
        if (!anyOn) {
            if (hasLocal) {
                cfg.set(HomeConfig.K_CAMERAS, "on");
                worldCams.setSlotUsesWorld(0, false);
                worldCams.setSlotUsesWorld(1, false);
            } else {
                worldCams.setWorldMode(cfg, true);
            }
        } else if (allWorld) {
            cfg.set(HomeConfig.K_CAMERAS, "off");
        } else {
            worldCams.setWorldMode(cfg, true);
        }
        recreate();
    }

    private void localCamMenu(int slot) {
        boolean wasPinned = ScreenTimeService.pinnedCam() == slot;
        List<String> items = new ArrayList<>();
        items.add(wasPinned ? "Turn off floating overlay" : "Pin as floating overlay");
        boolean canWorld = cfg.worldCamsOn() && !worldCatalog.isEmpty();
        if (canWorld) items.add("Switch to World Cam…");
        showDialog(new AlertDialog.Builder(this).setTitle(cfg.camName(slot))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        ScreenTimeService.camOverlay(this, wasPinned ? "off" : "set", slot);
                    } else if (which == 1 && canWorld) {
                        worldCams.setWorldMode(cfg, true);
                        recreate();
                    }
                }));
    }

    private void updateWorldLabel(int slot) {
        WorldCams.Stream s = worldSlots[slot];
        if (s == null) return;
        String pin = worldCams.isPinned(slot) ? "📌 " : "";
        String prefix = worldCams.isHearted(s) ? "♥ " : "";
        TextView nameTv = camViews[slot].findViewById(R.id.name);
        nameTv.setText(pin + prefix + s.displayName());
        View label = camLabels[slot];
        float textPx = nameTv.getTextSize();
        int extraTop = s.labelOffsetLines > 0f ? Math.round(s.labelOffsetLines * textPx) : 0;
        int extraStart = s.labelOffsetX > 0f ? Math.round(s.labelOffsetX * textPx) : 0;
        label.setPaddingRelative(camLabelStartPad[slot] + extraStart, camLabelTopPad[slot] + extraTop,
                label.getPaddingEnd(), label.getPaddingBottom());
    }

    private void switchWorldSlot(int slot, WorldCams.Stream s) {
        if (s == null) return;
        worldSlots[slot] = s;
        worldCams.setSlot(slot, s);
        if (slot == 0) pendingRandomStream = null;
        updateWorldLabel(slot);
        showLabel(camLabels[slot]);
        webHost.setSlot(slot, true, s.embedUrl());
        resetWorldTimer();
        updateWorldPrefetch();
    }

    private List<WorldCams.Stream> activeWorldPool() {
        return worldCams.activePool(worldCatalog);
    }

    private void updateWorldPrefetch() {
        if (webHost == null || worldCams == null) return;
        int count = worldCams.prefetchCount();
        if (count <= 0 || camIsWorld == null || camOn == null || !camIsWorld[0] || !camOn[0]
                || worldCatalog.isEmpty() || worldSlots[0] == null) {
            webHost.setPrefetchUrls(Collections.emptyList());
            return;
        }
        List<String> urls = new ArrayList<>(count);
        String avoidKey = otherWorldKey(0);
        if (pendingRandomStream != null && !pendingRandomStream.key.equals(worldSlots[0].key)) {
            urls.add(pendingRandomStream.embedUrl());
        }
        if (urls.size() < count) {
            List<WorldCams.Stream> nexts = WorldCams.peekNext(worldSlots[0], activeWorldPool(), avoidKey, count);
            for (WorldCams.Stream s : nexts) {
                if (urls.size() >= count) break;
                String u = s.embedUrl();
                if (u != null && !urls.contains(u)) urls.add(u);
            }
        }
        webHost.setPrefetchUrls(urls);
    }

    private static String prefetchLabel(int n) {
        return n <= 0 ? "Off" : String.valueOf(n);
    }

    private void setupWorldTimerView(float d) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x28FFFFFF);
        bg.setCornerRadius(7f * d);
        bg.setStroke(Math.max(1, Math.round(1f * d)), 0x33FFFFFF);
        worldTimer.setBackground(bg);
        worldTimer.setOnFocusChangeListener((v, has) -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            TileFx.animate(v, has ? 1.07f : 1f, 0);
            bg.setColor(has ? 0x48FFFFFF : 0x28FFFFFF);
            bg.setStroke(Math.max(1, Math.round((has ? 1.5f : 1f) * d)), has ? 0xCCFFD166 : 0x33FFFFFF);
        });
        worldTimer.setOnClickListener(v -> {
            boolean on = !worldCams.autoAdvanceOn();
            worldCams.setAutoAdvanceOn(on);
            if (on) resetWorldTimer();
            else stopWorldTimer();
            updateWorldTimerUi();
        });
        worldTimer.setOnLongClickListener(v -> {
            worldTimerMenu();
            return true;
        });
    }

    private void worldTimerMenu() {
        int mins = worldCams.autoAdvanceMinutes();
        boolean randRet = worldCams.randomOnReturn();
        boolean on = worldCams.autoAdvanceOn();
        String[] items = {
                "Switch cam every: " + mins + " min…",
                "Auto-switch timer: " + (on ? "On" : "Off"),
                "New random cam when returning Home: " + (randRet ? "On" : "Off")
        };
        showDialog(new AlertDialog.Builder(this).setTitle("World Cam rotation")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        int[] opts = {1, 2, 5, 10, 15, 30, 60};
                        String[] labels = new String[opts.length];
                        int checked = 2;
                        for (int i = 0; i < opts.length; i++) {
                            labels[i] = opts[i] + (opts[i] == 1 ? " minute" : " minutes");
                            if (opts[i] == mins) checked = i;
                        }
                        showDialog(new AlertDialog.Builder(this).setTitle("Switch cam every…")
                                .setSingleChoiceItems(labels, checked, (d2, w) -> {
                                    d2.dismiss();
                                    worldCams.setAutoAdvanceMinutes(opts[w]);
                                    worldCams.setAutoAdvanceOn(true);
                                    resetWorldTimer();
                                    updateWorldPrefetch();
                                    updateWorldTimerUi();
                                }));
                    } else if (which == 1) {
                        worldCams.setAutoAdvanceOn(!on);
                        if (!on) resetWorldTimer();
                        else stopWorldTimer();
                        updateWorldTimerUi();
                    } else if (which == 2) {
                        worldCams.setRandomOnReturn(!randRet);
                        Toast.makeText(this, "New random cam on return Home: " + (!randRet ? "On" : "Off"),
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private boolean worldTimerActive() {
        return camIsWorld != null && camIsWorld[0] && camOn != null && camOn[0]
                && !worldCatalog.isEmpty() && worldCams.autoAdvanceOn() && !worldCams.isPinned(0);
    }

    private void resetWorldTimer() {
        h.removeCallbacks(worldTimerTick);
        pendingRandomStream = null;
        if (worldTimerActive()) {
            worldTimerDeadlineMs = System.currentTimeMillis() + worldCams.autoAdvanceMinutes() * 60_000L;
            h.postDelayed(worldTimerTick, 1_000L);
        } else {
            worldTimerDeadlineMs = 0L;
        }
        updateWorldTimerUi();
    }

    private void stopWorldTimer() {
        h.removeCallbacks(worldTimerTick);
        pendingRandomStream = null;
    }

    private void updateWorldTimerUi() {
        if (worldTimer == null) return;
        boolean show = camIsWorld != null && camIsWorld[0] && camOn != null && camOn[0] && fullscreenCam == null;
        worldTimer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (worldCams.isPinned(0)) {
            worldTimer.setText("📌 Pinned");
            worldTimer.setAlpha(0.85f);
        } else if (!worldCams.autoAdvanceOn()) {
            worldTimer.setText("⏱ Off");
            worldTimer.setAlpha(0.60f);
        } else {
            long remSec = Math.max(0L, (worldTimerDeadlineMs - System.currentTimeMillis() + 999L) / 1_000L);
            if (worldTimerDeadlineMs <= 0L) remSec = worldCams.autoAdvanceMinutes() * 60L;
            worldTimer.setText(String.format(java.util.Locale.ROOT, "⏱ %d:%02d", remSec / 60L, remSec % 60L));
            worldTimer.setAlpha(0.95f);
        }
    }

    private final Runnable worldTimerTick = new Runnable() {
        @Override public void run() {
            if (!worldTimerActive()) {
                updateWorldTimerUi();
                return;
            }
            long now = System.currentTimeMillis();
            if (now >= worldTimerDeadlineMs) {
                if (hasWindowFocus() && !worldCatalog.isEmpty()) {
                    List<WorldCams.Stream> pool = activeWorldPool();
                    WorldCams.Stream pick = pendingRandomStream;
                    pendingRandomStream = null;
                    if (pick == null || WorldCams.isKnownOffline(pick)) {
                        pick = worldCams.peekShuffle(worldSlots[0], pool, otherWorldKey(0));
                    }
                    if (pick != null) {
                        switchWorldSlot(0, pick); // also resets timer & updates prefetch
                        return;
                    }
                }
                pendingRandomStream = null;
                worldTimerDeadlineMs = now + worldCams.autoAdvanceMinutes() * 60_000L;
            } else if (hasWindowFocus() && !worldCatalog.isEmpty()
                    && worldCams.prefetchCount() > 0 && pendingRandomStream == null
                    && (worldTimerDeadlineMs - now) <= RANDOM_PREFETCH_LEAD_MS) {
                pendingRandomStream = worldCams.peekShuffle(worldSlots[0], activeWorldPool(), otherWorldKey(0));
                if (pendingRandomStream != null) updateWorldPrefetch();
            }
            updateWorldTimerUi();
            h.postDelayed(this, 1_000L);
        }
    };

    private void advanceWorldCams(int dir) {
        if (worldCatalog.isEmpty()) return;
        List<WorldCams.Stream> pool = activeWorldPool();
        if (camIsWorld[0] && camIsWorld[1]) {
            WorldCams.Stream[] pair = dir < 0 ? worldCams.prevPair(pool) : worldCams.nextPair(pool);
            if (pair[0] != null) switchWorldSlot(0, pair[0]);
            if (pair[1] != null) switchWorldSlot(1, pair[1]);
        } else if (camIsWorld[0]) {
            switchWorldSlot(0, dir < 0 ? worldCams.prev(0, pool, null) : worldCams.next(0, pool, null));
        } else if (camIsWorld[1]) {
            switchWorldSlot(1, dir < 0 ? worldCams.prev(1, pool, null) : worldCams.next(1, pool, null));
        }
    }

    private String otherWorldKey(int slot) {
        int other = 1 - slot;
        return camIsWorld[other] && worldSlots[other] != null ? worldSlots[other].key : null;
    }

    private void worldCamMenu(int slot) {
        if (worldCatalog.isEmpty()) return;
        WorldCams.Stream cur = worldSlots[slot];
        boolean isH = worldCams.isHearted(cur);
        List<WorldCams.Stream> favs = worldCams.hearted(worldCatalog);
        boolean hasLocal0 = !cfg.camUrl(0).isEmpty();
        boolean hasLocal1 = !cfg.camUrl(1).isEmpty();
        boolean isPinned = worldCams.isPinned(slot);

        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(isPinned ? "📌 Unpin from Home (random on return)" : "📌 Pin to Home (always show this cam)");
        actions.add(() -> {
            worldCams.setPinned(slot, !isPinned);
            updateWorldLabel(slot);
            resetWorldTimer();
            updateWorldPrefetch();
            showLabel(camLabels[slot]);
            fadeLabel(camLabels[slot], 2_500);
        });
        items.add(isH ? "♥ Remove heart" : "♡ Heart this stream");
        actions.add(() -> {
            worldCams.toggleHeart(cur);
            updateWorldLabel(slot);
            updateWorldPrefetch();
            showLabel(camLabels[slot]);
            fadeLabel(camLabels[slot], 2_500);
        });
        items.add(favs.size() > 1 ? "Next hearted stream" : "Next stream");
        actions.add(() -> switchWorldSlot(slot, worldCams.next(slot, activeWorldPool(), otherWorldKey(slot))));
        items.add("Shuffle");
        actions.add(() -> switchWorldSlot(slot, worldCams.shuffle(slot, activeWorldPool(), otherWorldKey(slot))));

        int onlineAll = WorldCams.onlineCount(worldCatalog);
        String onlineTag = onlineAll > 0 ? " · " + onlineAll + " online" : "";
        items.add("Browse all streams (" + worldCatalog.size() + onlineTag + ")…");
        actions.add(() -> pickWorldStream(slot, "All World Cams", worldCatalog));

        if (!favs.isEmpty()) {
            items.add("♥ Hearted streams (" + favs.size() + ")…");
            actions.add(() -> pickWorldStream(slot, "♥ Hearted streams", favs));
        }
        items.add("Pre-fetch next stream: " + prefetchLabel(worldCams.prefetchCount()));
        actions.add(() -> {
            String[] opts = {"Off", "1 stream (recommended)", "2 streams (rapid surfing)"};
            showDialog(new AlertDialog.Builder(this).setTitle("Pre-fetch next stream")
                    .setSingleChoiceItems(opts, worldCams.prefetchCount(), (d2, w) -> {
                        d2.dismiss();
                        worldCams.setPrefetchCount(w);
                        if (w == 0) pendingRandomStream = null;
                        updateWorldPrefetch();
                        Toast.makeText(this, "Pre-fetch next stream: " + prefetchLabel(w), Toast.LENGTH_SHORT).show();
                    }));
        });
        if (hasLocal0 || hasLocal1) {
            String localLabel = (hasLocal0 && hasLocal1) ? "cameras" : (hasLocal0 ? cfg.camName(0) : cfg.camName(1));
            items.add("Switch back to " + localLabel);
            actions.add(() -> {
                worldCams.setSlotUsesWorld(0, false);
                worldCams.setSlotUsesWorld(1, false);
                recreate();
            });
        }

        String title = cur != null ? cur.displayName() : "World Cams";
        showDialog(new AlertDialog.Builder(this).setTitle(title)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run()));
    }

    private void pickWorldStream(int slot, String title, List<WorldCams.Stream> pool) {
        if (pool.isEmpty()) return;
        WorldCamPicker.show(this, title, pool, worldCams, worldSlots[slot], s -> switchWorldSlot(slot, s));
    }

    // ---------- apps ----------

    private void reload() {
        if (movingPkg != null) return; // don't reshuffle under the user's feet
        all.clear();
        all.addAll(store.load());
        timedApps = new TimedApps(this);   // the setting, or the apps on Home, may have changed
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        hiddenCount = 0;
        List<Row> favs = new ArrayList<>(), rest = new ArrayList<>();
        for (AppStore.App a : all) {
            if (a.hidden) hiddenCount++;
            if (a.hidden && !showHidden) continue;
            if (a.fav) favs.add(new Row(T_FAV, null, a)); else rest.add(new Row(T_APP, null, a));
        }
        if (!favs.isEmpty()) {
            rows.add(new Row(T_HEADER, "Favourites", null));
            rows.addAll(favs);
            rows.add(new Row(T_HEADER, "All apps", null));
        }
        rows.addAll(rest);
        adapter.notifyDataSetChanged();
    }

    /** The scene tile's picture: a miniature of the chosen scene. */
    private SceneTile sceneTile() {
        if (sceneTile == null) {
            String pkg = getString(R.string.scene_tile_package), label = "YouTube";
            int badge = 0xFFFF0033;   // YouTube red
            for (AppStore.App x : all) if (x.pkg.equals(pkg)) { label = x.label; if (!timedApps.youtube(pkg)) badge = x.accent; }
            sceneTile = new SceneTile(label, getResources().getFont(R.font.inter_semibold), Scene.create(this, Scene.Mode.TILE), badge);
        }
        return sceneTile;
    }

    /** Timed (YouTube, or whatever Config says) or grown-ups-only (SmartTube): an app the allowance rules apply to. */
    private boolean ruled(String pkg) { return timedApps.timed(pkg) || timedApps.grownUp(pkg); }

    private int firstAppPos() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).type != T_HEADER) return i;
        return 0;
    }

    private int posOf(AppStore.App a) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).app == a) return i;
        return -1;
    }

    private void launch(AppStore.App a) { launch(a.pkg, a.label); }

    private void launch(String pkg, String label) {
        if (timedApps.grownUp(pkg) && !screenTime.grownUp(System.currentTimeMillis())) {
            ParentGate.ask(this, () -> {
                screenTime.setGrownUp(System.currentTimeMillis() + GROWN_UP_MS);
                launch(pkg, label);
            });
            return;
        }
        if (timedApps.timed(pkg)) {
            WatchActivity.open(this, pkg);   // framed in the scene, Goodnight if it's used up, or full screen in grown-up time
            return;
        }
        if (!open(pkg)) Toast.makeText(this, "Can't open " + label, Toast.LENGTH_SHORT).show();
    }

    /** Start an app the normal way; false if it can't be. */
    private boolean open(String pkg) {
        Intent i = AppStore.launchIntent(getPackageManager(), pkg);
        if (i == null) return false;
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (ActivityNotFoundException e) { return false; }
    }

    private void showMenu(AppStore.App a) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (timedApps.timed(a.pkg) && !screenTime.adultMode(System.currentTimeMillis())) {
            items.add("Grown-up time: " + a.label);
            acts.add(() -> ParentGate.ask(this, () -> {
                screenTime.setGrownUp(System.currentTimeMillis() + GROWN_UP_MS);
                WatchActivity.openFullScreen(this, a.pkg);
            }));
        }
        items.add("Move"); acts.add(() -> startMove(a));
        items.add(a.fav ? "Remove from favourites" : "Add to favourites");
        acts.add(() -> { a.fav = !a.fav; store.setFav(a.pkg, a.fav); rebuild(); focus(posOf(a)); });
        items.add(a.hidden ? "Show on home" : "Hide");
        acts.add(() -> { a.hidden = !a.hidden; store.setHidden(a.pkg, a.hidden); rebuild(); });
        items.add(showHidden ? "Stop showing hidden apps" : "Show hidden apps (" + hiddenCount + ")");
        acts.add(() -> { showHidden = !showHidden; rebuild(); });
        items.add("App info");
        acts.add(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null))));
        showDialog(new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run()));
    }

    /** "Good morning" etc. for the hour (0–23), with optional family name. */
    static String greeting(int hour, String familyName) {
        String part = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon"
                : hour < 22 ? "Good evening" : "Good night";
        return familyName == null || familyName.isEmpty() ? part : part + ", " + familyName;
    }

    /** Top corner's Adult/Kids mode toggle: Kids -> Adult requires the arrow code; Adult -> Kids is one click. */
    private void toggleMode() {
        long now = System.currentTimeMillis();
        if (screenTime.adultMode(now)) {
            applyAdultMode(false);
        } else {
            ParentGate.ask(this, () -> applyAdultMode(true));
        }
    }

    private void applyAdultMode(boolean adult) {
        screenTime.setAdultMode(System.currentTimeMillis(), adult);
        timeChanged(null);
    }

    private void updateModeButton(boolean adult) {
        if (modeBtn == null || (modeBtnAdult != null && modeBtnAdult == adult)) return;
        modeBtnAdult = adult;
        modeBtn.setImageResource(adult ? R.drawable.ic_adult : R.drawable.ic_child);
        modeBtn.setActivated(adult);
        modeBtn.setContentDescription(adult ? "Adult mode on (OK for kids)" : "Switch to adult mode");
        if (modeBtn.hasFocus()) cornerLabel.setText(modeBtn.getContentDescription());
    }

    /** Top corner's Settings button, past the arrow code: Home & household settings. */
    private void settings() {
        SettingsMenu.showSettings(this, this::recreate, this::setLook, this::confirmRestore);
    }

    /** Top corner's Remote buttons button, past the arrow code: remote button remapping. */
    private void remoteButtons() {
        SettingsMenu.showRemoteButtons(this, all);
    }

    /** Top corner's Kids' time button, past the arrow code: daily limits, top-ups, timed apps & scene. */
    private void kidsTime() {
        SettingsMenu.showKidsTime(this, all, this::recreate, this::timeChanged, this::runDemo);
    }

    private void runDemo() {
        screenTime.setScrub(0f);   // so the app opens even if today's real time is used up
        screenTime.setDemo(true);  // and stays windowed even while Adult mode is on
        startService(ScreenTimeService.dev(this, "DEMO").putExtra("secs", 10));
        WatchActivity.open(this, getString(R.string.scene_tile_package));
    }

    private void timeChanged(String msg) {
        long now = System.currentTimeMillis();
        boolean adult = screenTime.adultMode(now);
        updateModeButton(adult);
        lastTimeSig = timeSig(now, adult);
        refreshRuledTiles();
    }

    // ---------- put the TV back ----------

    /** Past the arrow code: say plainly what's about to happen, with Cancel first. */
    private void confirmRestore() {
        if (!LocalAdb.isAdbEnabled(this)) {
            LocalAdb.showAdbRequiredDialog(this, "Put the TV back needs USB debugging turned on temporarily.", null, null);
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Put the TV back how it came?")
                .setMessage("This undoes everything that was set up on this TV: the TV's own home screen comes back, "
                        + "the apps that were switched off come back, settings go back to how they were, and this Home "
                        + "app (with its scenes, kids' time and cameras) removes itself. The TV restarts when it's done.\n\n"
                        + "Setting it up again needs a computer.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Put it back", (x, w) -> runRestore())
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
        showDialog(d);
    }

    private void runRestore() {
        AlertDialog busy = new AlertDialog.Builder(this).setTitle("Putting the TV back…")
                .setMessage("This takes a minute or so. Please don't switch the TV off.").setCancelable(false).show();
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            boolean started = true;
            try { Restore.run(app); }
            catch (Restore.NotStarted e) { err = e.getMessage(); started = false; }
            catch (java.io.IOException | RuntimeException e) { err = e.getMessage(); }
            final String msg = err;
            final boolean partly = started;
            h.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy.dismiss();
                if (msg == null) {
                    showDialog(new AlertDialog.Builder(this).setTitle("Done")
                            .setMessage("The TV will restart in a few seconds, with its own home screen."));
                    return;
                }
                showDialog(new AlertDialog.Builder(this).setTitle("Couldn't put the TV back")
                        .setMessage(partly
                                ? "Something went wrong part way (" + msg + "), so the TV may be only partly put back.\n\n"
                                        + "From a computer, run ./restore.sh in the repo to finish."
                                : "Home needs the TV to allow its debugging connection, and it didn't (" + msg + ").\n\n"
                                        + "Nothing was changed. From a computer, run ./restore.sh in the repo instead.")
                        .setPositiveButton("OK", null));
            });
        }, "restore").start();
    }

    private void startMove(AppStore.App a) {
        movingPkg = a.pkg;
        rebuild();
        focus(posOf(a));
    }

    private void endMove() {
        movingPkg = null;
        store.saveOrder(all);
        rebuild();
    }

    /** Move the dragged tile by `delta` positions within its own section, keeping `all` in sync. */
    private void moveBy(int pos, int delta) {
        int to = pos + delta;
        if (to < 0 || to >= rows.size() || rows.get(to).type != rows.get(pos).type) return;
        AppStore.App a = rows.get(pos).app, b = rows.get(to).app;
        Collections.swap(rows, pos, to);
        Collections.swap(all, all.indexOf(a), all.indexOf(b));
        adapter.notifyItemChanged(pos);
        adapter.notifyItemChanged(to);
        focus(to);
    }

    /** Focus scrolling only brings a tile just into view, which leaves the first/last row a few px short of
     *  the end: the fading edge then eats that row and its glow. On those rows, go all the way. */
    private void snapToEnds(int pos) {
        if (pos == RecyclerView.NO_POSITION || rows.isEmpty()) return;
        GridLayoutManager.SpanSizeLookup g = ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup();
        int row = g.getSpanGroupIndex(pos, SPANS);
        int first = firstAppPos();
        if (first >= 0 && row <= g.getSpanGroupIndex(first, SPANS)) {
            grid.smoothScrollToPosition(0);
        } else if (row == g.getSpanGroupIndex(rows.size() - 1, SPANS)) {
            int rest = grid.computeVerticalScrollRange() - grid.computeVerticalScrollExtent() - grid.computeVerticalScrollOffset();
            if (rest > 0) grid.smoothScrollBy(0, rest);
        }
    }

    private boolean inTopRow(int pos) {
        if (pos == RecyclerView.NO_POSITION) return false;
        GridLayoutManager.SpanSizeLookup g = ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup();
        return g.getSpanGroupIndex(pos, SPANS) <= g.getSpanGroupIndex(firstAppPos(), SPANS);
    }

    private void focus(int pos) {
        if (pos < 0 || pos >= rows.size()) return;
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int pos) { return rows.get(pos).type == T_HEADER ? T_HEADER : T_APP; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LayoutInflater li = LayoutInflater.from(p.getContext());
            if (t == T_HEADER) return new RecyclerView.ViewHolder(li.inflate(R.layout.section, p, false)) { };
            return new Holder(li.inflate(R.layout.tile, p, false));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Row r = rows.get(pos);
            if (r.type == T_HEADER) { ((TextView) h.itemView).setText(r.title); ((TextView) h.itemView).setTextColor(look.faint); }
            else ((Holder) h).bind(r.app);
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView banner, icon; final View iconBox, timeBar; final TextView name, badge;
        final TileFrame frame;
        AppStore.App app;

        Holder(View v) {
            super(v);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            iconBox = v.findViewById(R.id.iconBox);
            name = v.findViewById(R.id.name);
            badge = v.findViewById(R.id.badge);
            timeBar = v.findViewById(R.id.timeBar);
            float d = v.getResources().getDisplayMetrics().density;
            frame = new TileFrame(d);
            v.setForeground(frame);
            v.setOnClickListener(x -> { if (movingPkg != null) endMove(); else launch(app); });
            v.setOnLongClickListener(x -> { if (movingPkg == null) showMenu(app); return true; });
            v.setOnFocusChangeListener((x, has) -> {
                animateFocus(has, true);
                if (has) grid.post(() -> snapToEnds(getBindingAdapterPosition()));
            });
            v.setOnKeyListener((x, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_MENU && movingPkg == null) { showMenu(app); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP && movingPkg == null && inTopRow(getBindingAdapterPosition())) {
                    grownUps.requestFocus();   // not left to focus search, which can pick a camera instead
                    return true;
                }
                if (movingPkg == null || !movingPkg.equals(app.pkg)) return false;
                int pos = getBindingAdapterPosition();
                int cols = cols(rows.get(pos));
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:  moveBy(pos, -1); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(pos, +1); return true;
                    case KeyEvent.KEYCODE_DPAD_UP:    moveBy(pos, -cols); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:  moveBy(pos, +cols); return true;
                }
                return false;
            });
        }

        void animateFocus(boolean has, boolean spring) {
            boolean moving = app != null && app.pkg.equals(movingPkg);
            float s = moving ? TileFx.MOVING : has ? TileFx.FOCUS : 1f;
            frame.setFocused(has || moving);
            if (spring) TileFx.animate(itemView, s, 0);
            else TileFx.set(itemView, s, 0);
        }

        void bind(AppStore.App a) {
            app = a;
            boolean hasBanner = a.banner != null;
            banner.setVisibility(hasBanner ? View.VISIBLE : View.GONE);
            iconBox.setVisibility(hasBanner ? View.GONE : View.VISIBLE);
            boolean isTimed = timedApps.timed(a.pkg), isLocked = timedApps.grownUp(a.pkg);
            boolean isRuled = isTimed || isLocked;
            long now = isRuled ? System.currentTimeMillis() : 0;
            boolean adult = isRuled && screenTime.adultMode(now);
            boolean grown = isRuled && (adult || screenTime.hasPass(now));
            boolean counted = isTimed && !grown;
            float p = counted ? screenTime.progress(now) : 0f;
            // The scene tile: a little version of the scene, its sky following today's time.
            boolean scene = counted && a.pkg.equals(getString(R.string.scene_tile_package));
            if (scene) {
                sceneTile().setProgress(p);
                banner.setVisibility(View.VISIBLE);
                iconBox.setVisibility(View.GONE);
                banner.setImageDrawable(sceneTile);
            } else if (hasBanner) banner.setImageDrawable(a.banner);
            else { icon.setImageDrawable(a.icon); name.setText(a.label); }
            int accent = scene ? 0xFFFFB347 : a.accent;   // warm sunshine glow for the scene tile
            itemView.setContentDescription(a.label);
            itemView.setTag(R.id.accent, accent);
            frame.setLook(look);
            frame.setAccent(accent);
            itemView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(look.tile));
            name.setTextColor(look.text);
            boolean asleep = counted && p >= 1f;
            boolean lockedApp = isLocked && !grown;
            itemView.setAlpha(a.hidden ? 0.35f : asleep ? 0.55f : 1f);
            boolean moving = a.pkg.equals(movingPkg);
            String note = null; int icon = 0;
            if (moving) note = "MOVING";
            else if (a.hidden) note = "HIDDEN";
            else if (asleep) { note = "THAT'S ALL FOR TODAY"; icon = R.drawable.wx_moon; }
            // With every app timed, only the scene tile shows the minutes (the rest would all say the same).
            else if (counted && (scene || !timedApps.all())) {
                int left = screenTime.minutesLeft(now, p);
                note = (screenTime.scrubbing() ? Math.round((1 - p) * 100) + "% LEFT" : left + " MIN LEFT");
                icon = p >= ScreenTime.NIGHT ? R.drawable.wx_moon : R.drawable.wx_sun;
            }
            else if (lockedApp) note = "GROWN-UPS";
            else if (isRuled && !adult) note = "GROWN-UP TIME";
            badge.setVisibility(note != null ? View.VISIBLE : View.GONE);
            badge.setText(note);
            float density = itemView.getResources().getDisplayMetrics().density;
            android.graphics.drawable.Drawable badgeIcon = icon != 0 ? itemView.getContext().getDrawable(icon) : null;
            if (badgeIcon != null) badgeIcon.setBounds(0, 0, (int) (12 * density), (int) (12 * density));
            badge.setCompoundDrawablesRelative(badgeIcon, null, null, null);
            if (counted && (scene || !timedApps.all()) && !asleep && !moving && !a.hidden) {
                float cl = Math.min(1f, p);
                timeBar.setVisibility(View.VISIBLE);
                timeBar.setBackgroundColor(sceneTile().scene.barColour(cl));
                timeBar.setPivotX(0);
                timeBar.setScaleX(1f - cl);
            } else timeBar.setVisibility(View.GONE);
            badge.setCompoundDrawablePadding((int) (4 * density));
            animateFocus(itemView.hasFocus(), itemView.hasFocus() || a.pkg.equals(movingPkg));
        }
    }
}
