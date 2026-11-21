// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Global remote-button interceptor for Home (Config > Remote buttons).
 *
 * Runs inside Home's own process with flagRequestFilterKeyEvents so mapped buttons work from any app
 * without a separate third-party mapper. Hot-path lookups use SparseArray/SparseBooleanArray so unmapped
 * keys do no string or date allocations. While Config's "Press a button" prompt is open, capture mode
 * intercepts the next non-essential button cleanly (swallowing both DOWN and UP so Netflix/YouTube/etc.
 * don't launch behind the dialog).
 */
public final class RemoteKeyService extends AccessibilityService {
    interface CaptureListener {
        void onCaptured(String id);
        void onBlocked(int keyCode);
    }

    private static final class Entry {
        final long at; final int code, scan; final String msg;
        Entry(long at, int code, int scan, String msg) { this.at = at; this.code = code; this.scan = scan; this.msg = msg; }
    }

    private static volatile boolean connected;
    private static volatile CaptureListener capture;
    private static final ArrayDeque<Entry> RECENT = new ArrayDeque<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray swallowedDown = new SparseBooleanArray();
    private RemoteKeys store;
    private volatile SparseArray<String> map = new SparseArray<>();

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, k) -> {
        if (RemoteKeys.isMapKey(k)) reload();
    };

    static boolean isConnected() { return connected; }

    static void setCapture(CaptureListener l) { capture = l; }

    static String recentKeys() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        StringBuilder out = new StringBuilder();
        synchronized (RECENT) {
            for (Entry e : RECENT) {
                if (out.length() > 0) out.append('\n');
                out.append(fmt.format(new Date(e.at))).append(' ');
                if (e.code != 0 || e.scan != 0) {
                    out.append(RemoteKeys.keyId(e.code, e.scan)).append(" (scan ").append(e.scan).append(')');
                    if (e.msg != null) out.append(' ').append(e.msg);
                } else {
                    out.append(e.msg);
                }
            }
        }
        return out.toString();
    }

    private static void note(int code, int scan, String msg) {
        synchronized (RECENT) {
            RECENT.addLast(new Entry(System.currentTimeMillis(), code, scan, msg));
            if (RECENT.size() > 10) RECENT.removeFirst();
        }
    }

    private void reload() {
        map = store != null ? store.slots() : new SparseArray<>();
    }

    private void disconnect(String why) {
        connected = false;
        swallowedDown.clear();
        if (store != null) store.unregister(listener);
        if (why != null) note(0, 0, why);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        store = new RemoteKeys(this);
        store.register(listener);
        reload();
        connected = true;
        note(0, 0, "service connected (" + map.size() + " mapped)");
        RemoteKeys.notifyConnected();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        disconnect("service unbound");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect(null);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int scan = e.getScanCode();
        int slot = RemoteKeys.slot(code, scan);
        if (slot == 0) return false;
        int action = e.getAction();

        // Always swallow the matching UP of any DOWN we consumed (including right after capture ends).
        if (action == KeyEvent.ACTION_UP) {
            if (!swallowedDown.get(slot)) return false;
            swallowedDown.delete(slot);
            return true;
        }

        CaptureListener cap = capture;
        if (cap != null) {
            if (RemoteKeys.passThroughInCapture(code)) return false;
            if (action != KeyEvent.ACTION_DOWN) return false;
            swallowedDown.put(slot, true);
            if (e.getRepeatCount() == 0) {
                if (RemoteKeys.blocked(code)) {
                    note(code, scan, "(capture blocked)");
                    main.post(() -> { CaptureListener c = capture; if (c != null) c.onBlocked(code); });
                } else {
                    String id = RemoteKeys.keyId(code, scan);
                    note(code, scan, "(captured)");
                    main.post(() -> { CaptureListener c = capture; if (c != null && id != null) c.onCaptured(id); });
                }
            }
            return true;
        }

        String target = map.get(slot);
        if (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            note(code, scan, target != null ? "-> " + target : null);
        }
        if (target == null || action != KeyEvent.ACTION_DOWN) return false;
        swallowedDown.put(slot, true);
        if (e.getRepeatCount() == 0) RemoteKeys.execute(this, target);
        return true;
    }
}
