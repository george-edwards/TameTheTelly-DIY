// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * An empty, see-through activity that only exists to make a multi-window task (see Framer.adopt): apps like
 * YouTube hand themselves off to a new full-screen task as they start, and full-screen tasks can't be resized or
 * switched to another mode, but they can be moved into an existing multi-window task. Framer starts this one in
 * the hole, moves the app's task into it, then finishes it. It finishes itself after a while in case nothing comes.
 */
public final class FrameHolder extends Activity {
    private static volatile FrameHolder current;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        h.postDelayed(this::done, 15_000);
    }

    /** Wait (off the main thread) for a holder to come up; its task id (a root task of its own), or -1. */
    static int awaitTask(long timeoutMs) throws InterruptedException {
        for (long end = System.currentTimeMillis() + timeoutMs; System.currentTimeMillis() < end; Thread.sleep(50)) {
            FrameHolder f = current;
            if (f != null) return f.getTaskId();
        }
        return -1;
    }

    /** Finish the holder that's up, if there is one. */
    static void release() {
        FrameHolder f = current;
        current = null;   // straight away, so awaitTask() only sees a newer one
        if (f != null) f.runOnUiThread(f::done);
    }

    private void done() {
        if (!isFinishing()) { finish(); overridePendingTransition(0, 0); }
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
