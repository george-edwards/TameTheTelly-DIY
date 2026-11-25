// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating corner RTSP camera window drawn over any fullscreen app via SYSTEM_ALERT_WINDOW.
 *
 * Supports showing either camera alone (MODE_CAM1 = 0, MODE_CAM2 = 1) or both stacked
 * vertically (MODE_BOTH = 2). Pressing a toggle button steps clockwise through the four corners
 * (top right -> bottom right -> bottom left -> top left -> off) with an instant jump and no toast.
 * Always uses the low-res (SD) stream, muted and with audio track disabled (via CameraFeed),
 * so it never steals audio focus or stalls playback underneath.
 */
final class CameraOverlay {
    static final int MODE_OFF = -1;
    static final int MODE_CAM1 = 0;
    static final int MODE_CAM2 = 1;
    static final int MODE_BOTH = 2;

    /** Clockwise corner order for toggle presses: TR -> BR -> BL -> TL -> off. */
    private static final String[] CORNERS_CW = {
            HomeConfig.CORNER_TR, HomeConfig.CORNER_BR, HomeConfig.CORNER_BL, HomeConfig.CORNER_TL
    };

    private static final String TAG = "HomeCamOverlay";
    private static final long LABEL_FADE_MS = 4_000;
    private static final int MARGIN_DP = 18;
    private static final int STACK_GAP_DP = 8;
    private static final float RADIUS_DP = 10f;

    private final Context ctx;
    private final WindowManager wm;
    private final HomeConfig cfg;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int pinnedMode = MODE_OFF;
    private int shownMode = MODE_OFF;
    private int cornerIdx = 0;          // index into CORNERS_CW
    private View view;
    private int shownW, shownH;
    private final List<CameraFeed> feeds = new ArrayList<>(2);
    private final List<Runnable> pendingStarts = new ArrayList<>(2);

    CameraOverlay(Context ctx, WindowManager wm, HomeConfig cfg) {
        this.ctx = ctx;
        this.wm = wm;
        this.cfg = cfg;
        this.cornerIdx = cornerIndex(cfg.overlayCorner());
    }

    int pinned() { return pinnedMode; }
    String activeCorner() { return CORNERS_CW[cornerIdx]; }

    /** Human-readable name for a mode (used in STATUS). */
    static String modeName(HomeConfig cfg, int mode) {
        if (mode == MODE_BOTH) return "Both cameras";
        if (mode >= 0 && mode < HomeConfig.CAMERAS) return cfg.camName(mode);
        return "off";
    }

    private static int cornerIndex(String corner) {
        for (int i = 0; i < CORNERS_CW.length; i++) if (CORNERS_CW[i].equals(corner)) return i;
        return 0;
    }

    private boolean modeAvailable(int mode) {
        if (mode == MODE_BOTH) {
            return !cfg.camUrl(MODE_CAM1).isEmpty() && !cfg.camUrl(MODE_CAM2).isEmpty();
        }
        return mode >= 0 && mode < HomeConfig.CAMERAS && !cfg.camUrl(mode).isEmpty();
    }

    /** Pin a mode (MODE_CAM1, MODE_CAM2, MODE_BOTH, or MODE_OFF) at top right. Returns the resulting mode. */
    int pin(int mode) {
        if (!modeAvailable(mode)) {
            pinnedMode = MODE_OFF;
            hide();
            return MODE_OFF;
        }
        cornerIdx = 0;
        pinnedMode = mode;
        if (view != null && shownMode != mode) hide();
        else if (view != null) applyPosition();
        return pinnedMode;
    }

    /**
     * Step a toggle button:
     *   press 1 (from off or another mode) -> on at top right
     *   press 2 -> bottom right (instant jump, keeps stream alive)
     *   press 3 -> bottom left
     *   press 4 -> top left
     *   press 5 -> off
     */
    int toggle(int mode) {
        if (!modeAvailable(mode)) return pin(MODE_OFF);
        if (pinnedMode != mode) return pin(mode);
        if (cornerIdx + 1 < CORNERS_CW.length) {
            cornerIdx++;
            applyPosition();
            return pinnedMode;
        }
        return pin(MODE_OFF);
    }

    /** Cycle between the two cameras (Cam 2 -> Cam 1 -> Off), skipping unavailable cameras. */
    int cycle() {
        int[] order = {MODE_CAM2, MODE_CAM1};
        int start = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == pinnedMode) { start = i; break; }
        }
        for (int i = start + 1; i < order.length; i++) {
            if (modeAvailable(order[i])) return pin(order[i]);
        }
        return pin(MODE_OFF);
    }

    /** Show or hide the overlay window depending on whether an app is on screen and a mode is pinned. */
    void sync(boolean canShow) {
        if (canShow && pinnedMode != MODE_OFF) {
            if (!modeAvailable(pinnedMode)) {
                pinnedMode = MODE_OFF;
                hide();
            } else {
                show(pinnedMode);
            }
        } else {
            hide();
        }
    }

    /** Apply updated corner/size settings from Config while visible. */
    void reconfigure() {
        cornerIdx = cornerIndex(cfg.overlayCorner());
        if (view == null || shownMode == MODE_OFF) return;
        int mode = shownMode;
        hide();
        show(mode);
    }

    void destroy() {
        pinnedMode = MODE_OFF;
        hide();
    }

    private void applyPosition() {
        if (view == null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        try {
            wm.updateViewLayout(view, buildLayoutParams(shownW, shownH, d));
        } catch (RuntimeException e) {
            Log.w(TAG, "updateViewLayout failed", e);
        }
    }

    private void show(int mode) {
        if (view != null && shownMode == mode) return;
        if (view != null) hide();
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "no overlay permission");
            return;
        }
        int[] cams = mode == MODE_BOTH ? new int[]{MODE_CAM1, MODE_CAM2} : new int[]{mode};
        for (int cam : cams) if (cfg.camUrl(cam).isEmpty()) return;

        Context themed = new ContextThemeWrapper(ctx, R.style.HomeTheme);
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = cardWidthPx(d);
        int h = cardHeightPx(d);
        int gap = Math.round(STACK_GAP_DP * d);

        List<CameraFeed> newFeeds = new ArrayList<>(cams.length);
        View root;
        if (cams.length == 1) {
            root = buildCard(themed, cams[0], d, newFeeds);
        } else {
            LinearLayout stack = new LinearLayout(themed);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setFocusable(false);
            stack.setClickable(false);
            for (int i = 0; i < cams.length; i++) {
                View card = buildCard(themed, cams[i], d, newFeeds);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, h);
                if (i > 0) clp.topMargin = gap;
                stack.addView(card, clp);
            }
            root = stack;
        }

        int totalH = cams.length == 1 ? h : h * cams.length + gap * (cams.length - 1);
        try {
            wm.addView(root, buildLayoutParams(w, totalH, d));
        } catch (RuntimeException e) {
            Log.w(TAG, "addView failed", e);
            return;
        }
        view = root;
        shownW = w;
        shownH = totalH;
        shownMode = mode;
        feeds.addAll(newFeeds);

        // Stagger startup slightly when showing both feeds so hardware decoders initialise cleanly.
        for (int i = 0; i < newFeeds.size(); i++) {
            final CameraFeed f = newFeeds.get(i);
            Runnable r = f::start;
            pendingStarts.add(r);
            main.postDelayed(r, i * 160L);
        }
    }

    private View buildCard(Context themed, int cam, float d, List<CameraFeed> outFeeds) {
        View v = LayoutInflater.from(themed).inflate(R.layout.camera, null);
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setClickable(false);

        float radius = RADIUS_DP * d;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        v.setElevation(12f * d);

        View label = v.findViewById(R.id.label);
        ((TextView) v.findViewById(R.id.name)).setText(cfg.camName(cam));
        TextureView surface = v.findViewById(R.id.surface);
        TextView state = v.findViewById(R.id.state);

        String url = cfg.camUrl(cam);
        // Always use the SD (640x360) stream for the corner overlay to keep decoder load minimal.
        outFeeds.add(new CameraFeed(themed, url, url, surface, state, () -> {
            label.animate().cancel();
            label.animate().alpha(0f).setStartDelay(LABEL_FADE_MS).setDuration(600).start();
        }));
        return v;
    }

    private void hide() {
        for (Runnable r : pendingStarts) main.removeCallbacks(r);
        pendingStarts.clear();
        for (CameraFeed f : feeds) f.stop();
        feeds.clear();
        View v = view;
        view = null;
        shownMode = MODE_OFF;
        if (v == null) return;
        try { wm.removeView(v); } catch (RuntimeException ignored) { }
    }

    private int cardWidthPx(float d) {
        int widthDp;
        switch (cfg.overlaySize()) {
            case HomeConfig.OVERLAY_SMALL: widthDp = 216; break;
            case HomeConfig.OVERLAY_LARGE: widthDp = 336; break;
            default: widthDp = 272; break;
        }
        return Math.round(widthDp * d);
    }

    private int cardHeightPx(float d) {
        return Math.round(cardWidthPx(d) * 9f / 16f);
    }

    private WindowManager.LayoutParams buildLayoutParams(int w, int h, float d) {
        int margin = Math.round(MARGIN_DP * d);
        int gravity;
        switch (CORNERS_CW[cornerIdx]) {
            case HomeConfig.CORNER_TL: gravity = Gravity.TOP | Gravity.START; break;
            case HomeConfig.CORNER_BR: gravity = Gravity.BOTTOM | Gravity.END; break;
            case HomeConfig.CORNER_BL: gravity = Gravity.BOTTOM | Gravity.START; break;
            default: gravity = Gravity.TOP | Gravity.END; break;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = margin;
        lp.y = margin;
        lp.windowAnimations = 0;   // hard jump with no transition animation
        lp.setTitle("HomeCameraOverlay");
        return lp;
    }
}
