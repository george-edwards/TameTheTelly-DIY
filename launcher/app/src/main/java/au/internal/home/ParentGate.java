// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * A grown-ups-only door: type a short arrow sequence on the remote (HomeConfig.parentCode, U/D/L/R).
 * Dots fill in as you press; a wrong sequence quietly starts over. Back closes it.
 */
final class ParentGate {
    static void ask(Activity a, Runnable onOpen) { ask(a, new HomeConfig(a).parentCode(), onOpen); }

    static TextView dotView(Activity a) {
        TextView v = new TextView(a);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(28);
        v.setPadding(0, 24, 0, 24);
        v.setTextColor(a.getColor(R.color.text));
        return v;
    }

    /** Map a D-pad key to its U/D/L/R letter, or 0 for anything else. */
    static char dpadChar(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return 'U';
            case KeyEvent.KEYCODE_DPAD_DOWN: return 'D';
            case KeyEvent.KEYCODE_DPAD_LEFT: return 'L';
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 'R';
            default: return 0;
        }
    }

    static void ask(Activity a, String code, Runnable onOpen) {
        TextView dots = dotView(a);
        StringBuilder typed = new StringBuilder();
        Runnable show = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < code.length(); i++) sb.append(i < typed.length() ? "● " : "○ ");
            dots.setText(sb.toString().trim());
        };
        show.run();
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Grown-ups").setView(dots).create();
        d.setOnKeyListener((x, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return k != KeyEvent.KEYCODE_BACK;
            if (k == KeyEvent.KEYCODE_BACK) return false;
            char c = dpadChar(k);
            if (c == 0) return true;
            typed.append(c);
            if (typed.toString().equals(code)) { d.dismiss(); onOpen.run(); return true; }
            if (typed.length() >= code.length()) typed.setLength(0);   // wrong (no hint which press): start over
            show.run();
            return true;
        });
        MainActivity.showDialog(d);
    }
}
