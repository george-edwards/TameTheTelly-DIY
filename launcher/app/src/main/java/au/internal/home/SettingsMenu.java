// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Home's three top-corner grown-up menus (each past the arrow code):
 *  - Kids' time: today's controls (grown-up time, reset today, +10/+n min, demo) plus timed apps, daily allowance & scene
 *  - Remote buttons: press a remote button, pick an app or TV action
 *  - Settings: Home theme, cameras, grown-up code, family, weather, bin night, reset, and Put the TV back
 *
 * Settings are kept on the TV (HomeConfig / RemoteKeys) and applied straight away. Back on the remote goes up one
 * level; pressing Home dismisses any open dialog cleanly.
 */
final class SettingsMenu {
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final int[] MINUTES = {15, 20, 30, 45, 60, 90, 120};
    private static final int[] TOP_UPS = {5, 10, 15, 20, 30, 60, 90, 120};

    private final Activity a;
    private final HomeConfig cfg;
    private RemoteKeys remoteKeys;
    private TimedApps timed;
    private ScreenTime screenTime;
    private final List<AppStore.App> installed;
    private final Runnable onChanged, onRestore, onDemo;
    private final java.util.function.Consumer<String> onTimeChanged;
    private final java.util.function.Consumer<Look> preview;

    private SettingsMenu(Activity a, List<AppStore.App> installed, Runnable onChanged,
                         java.util.function.Consumer<String> onTimeChanged, Runnable onDemo,
                         java.util.function.Consumer<Look> preview, Runnable onRestore) {
        this.a = a;
        this.cfg = new HomeConfig(a);
        this.installed = installed;
        this.onChanged = onChanged;
        this.onTimeChanged = onTimeChanged;
        this.onDemo = onDemo;
        this.preview = preview;
        this.onRestore = onRestore;
    }

    private RemoteKeys remoteKeys() { return remoteKeys != null ? remoteKeys : (remoteKeys = new RemoteKeys(a)); }
    private TimedApps timed() { return timed != null ? timed : (timed = new TimedApps(a)); }
    private ScreenTime screenTime() { return screenTime != null ? screenTime : (screenTime = new ScreenTime(a)); }

    /** Corner Settings button: Home & household settings (theme, cameras, code, family, weather, bins, restore). */
    static void showSettings(Activity a, Runnable onChanged,
                             java.util.function.Consumer<Look> preview, Runnable onRestore) {
        new SettingsMenu(a, null, onChanged, null, null, preview, onRestore).main();
    }

    /** Corner Remote buttons button: remap remote buttons directly. */
    static void showRemoteButtons(Activity a, List<AppStore.App> installed) {
        new SettingsMenu(a, installed, null, null, null, null, null).remoteButtons();
    }

    /** Corner Kids' time button: today's allowance controls plus timed apps, daily limit, and bedtime scene. */
    static void showKidsTime(Activity a, List<AppStore.App> installed, Runnable onChanged,
                             java.util.function.Consumer<String> onTimeChanged, Runnable onDemo) {
        new SettingsMenu(a, installed, onChanged, onTimeChanged, onDemo, null, null).kidsTime();
    }

    /** Settings' own reset, worded so it isn't mistaken for Put the TV back. */
    private static final String RESET = "Reset settings";

    /** One row in a clean two-column menu: bright label left, optional muted value right. */
    private static final class MenuRow {
        final String label, value;
        final Runnable action;
        MenuRow(String label, String value, Runnable action) {
            this.label = label; this.value = value; this.action = action;
        }
    }

    /** Show a two-column menu dialog: crisp labels on the left, quiet values on the right. */
    private AlertDialog showMenu(String title, Runnable up, List<MenuRow> rows) {
        float dp = a.getResources().getDisplayMetrics().density;
        int hPad = Math.round(24 * dp), vPad = Math.round(13 * dp), gap = Math.round(16 * dp), maxVal = Math.round(220 * dp);
        android.graphics.Typeface med = a.getResources().getFont(R.font.inter_medium);
        android.graphics.Typeface reg = a.getResources().getFont(R.font.inter_regular);
        int textCol = a.getColor(R.color.text), mutedCol = a.getColor(R.color.muted);
        android.widget.ArrayAdapter<MenuRow> adapter = new android.widget.ArrayAdapter<MenuRow>(a, 0, rows) {
            @Override public android.view.View getView(int pos, android.view.View convert, android.view.ViewGroup parent) {
                android.widget.LinearLayout row;
                TextView left, right;
                if (convert instanceof android.widget.LinearLayout) {
                    row = (android.widget.LinearLayout) convert;
                    left = (TextView) row.getChildAt(0);
                    right = (TextView) row.getChildAt(1);
                } else {
                    row = new android.widget.LinearLayout(a);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(hPad, vPad, hPad, vPad);
                    left = new TextView(a);
                    left.setTextSize(15.5f); left.setTypeface(med); left.setTextColor(textCol);
                    left.setSingleLine(true); left.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    right = new TextView(a);
                    right.setTextSize(14f); right.setTypeface(reg); right.setTextColor(mutedCol);
                    right.setSingleLine(true); right.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    right.setPadding(gap, 0, 0, 0);
                    right.setMaxWidth(maxVal);
                    row.addView(left, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(right, new android.widget.LinearLayout.LayoutParams(-2, -2));
                }
                MenuRow item = rows.get(pos);
                left.setText(item.label);
                boolean hasVal = item.value != null && !item.value.isEmpty();
                right.setVisibility(hasVal ? android.view.View.VISIBLE : android.view.View.GONE);
                if (hasVal) right.setText(item.value);
                return row;
            }
        };
        return show(dialog(title, up).setAdapter(adapter, (d, which) -> rows.get(which).action.run()));
    }

    /** A dialog that goes back to `up` when Back cancels it (picking an item dismisses it instead). */
    private AlertDialog.Builder dialog(String title, Runnable up) {
        return new AlertDialog.Builder(a).setTitle(title).setOnCancelListener(x -> up.run());
    }

    private AlertDialog show(AlertDialog.Builder b) { return MainActivity.showDialog(b); }

    private void main() {
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow("Home theme", cfg.look().name, this::theme));
        rows.add(new MenuRow("Cameras", cfg.camerasOn() ? "On" : "Off", () -> {
            boolean on = !cfg.camerasOn();
            cfg.set(HomeConfig.K_CAMERAS, on ? "on" : "off");
            changed(on ? "Cameras on" : "Cameras off: apps use the whole screen");
        }));
        WorldCams wc = new WorldCams(a);
        int hearts = wc.heartedCount(wc.load());
        String wcVal = cfg.worldCamsOn() ? (hearts > 0 ? "On (" + hearts + " ♥)" : "On") : "Off";
        rows.add(new MenuRow("World cam", wcVal, () -> {
            boolean on = !cfg.worldCamsOn();
            cfg.set(HomeConfig.K_WORLD_CAMS, on ? "on" : "off");
            wc.setSlotUsesWorld(0, on);
            wc.setSlotUsesWorld(1, false);
            if (on && !cfg.camerasOn()) cfg.set(HomeConfig.K_CAMERAS, "on");
            changed(on ? "World cam on (hold OK on the card to switch or ♥)" : "World cam off");
        }));
        rows.add(new MenuRow("Camera setup", null, this::cameras));
        rows.add(new MenuRow("Grown-up code", HomeConfig.dots(cfg.parentCode().length()), this::newCode));
        rows.add(new MenuRow("Family name", cfg.familyName().isEmpty() ? "None" : cfg.familyName(), () ->
                text("Family name in the greeting (blank for none)", cfg.familyName(), false,
                        v -> MainActivity.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), v.trim()), this::main, v -> {
                    cfg.set(HomeConfig.K_FAMILY, v.trim());
                    changed("Saved");
                })));
        rows.add(new MenuRow("Weather", cfg.place().isEmpty() ? "Off" : cfg.place(), () -> postcode(cfg.postcode())));
        rows.add(new MenuRow("Bin night", cfg.binsDay() >= 1 && cfg.binsDay() <= 7 ? DAYS[cfg.binsDay() - 1] : "Off", this::bins));
        if (!cfg.updateUrl().isEmpty()) rows.add(new MenuRow("Check for updates", "v" + Updater.currentVersionName(a), this::checkUpdate));
        rows.add(new MenuRow(RESET, null, this::reset));
        rows.add(new MenuRow("Put the TV back", null, onRestore));
        showMenu(settingsTitle(), () -> { }, rows);
    }

    private void checkUpdate() {
        String url = cfg.updateUrl();
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        android.content.Context app = a.getApplicationContext();
        int curCode = Updater.currentVersionCode(a);
        String curName = Updater.currentVersionName(a);
        new Thread(() -> {
            try {
                Updater.Release r = Updater.fetch(url);
                a.runOnUiThread(() -> {
                    if (r.versionCode <= curCode) {
                        show(dialog("Up to date", this::main)
                                .setMessage("Home v" + curName + " is the latest version.")
                                .setPositiveButton("OK", (d, w) -> main()));
                        return;
                    }
                    String msg = "Current: v" + curName + "\nLatest: v" + r.versionName
                            + (r.notes.isEmpty() ? "" : "\n\n" + r.notes);
                    show(dialog("Update available: v" + r.versionName, this::main)
                            .setMessage(msg)
                            .setPositiveButton("Install update", (d, w) -> installUpdate(app, r))
                            .setNegativeButton("Later", (d, w) -> main()));
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    Toast.makeText(a, "Couldn't check for updates: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    main();
                });
            }
        }, "home-update-check").start();
    }

    private void installUpdate(android.content.Context app, Updater.Release r) {
        if (!LocalAdb.isAdbEnabled(a)) {
            LocalAdb.showAdbRequiredDialog(a, "Installing Home updates needs USB debugging turned on temporarily.", null, this::main);
            return;
        }
        Toast.makeText(a, "Downloading v" + r.versionName + "…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(app, r);
                a.runOnUiThread(() -> Toast.makeText(a, "Installing v" + r.versionName + " — Home will restart", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    Toast.makeText(a, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    main();
                });
            }
        }, "home-update-install").start();
    }

    private String settingsTitle() {
        try {
            String v = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            if (v != null && !v.isEmpty()) return "Settings  ·  v" + v;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        return "Settings";
    }

    // ---------- parental controls ----------

    private void kidsTime() {
        long now = System.currentTimeMillis();
        ScreenTime st = screenTime();
        boolean adult = st.adultMode(now);
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow("Daily limit", minutesShort(cfg.ytMinutes()), this::minutes));
        rows.add(new MenuRow("Restart today", null, () -> {
            long t = System.currentTimeMillis(); st.resetToday(t); timeChanged("Parental controls: " + st.describe(t));
        }));
        rows.add(new MenuRow("Extra time today", null, this::topUp));
        rows.add(new MenuRow("Restricted apps", timedCountSummary(), this::timedApps));
        rows.add(new MenuRow("Restricted scene", cfg.sceneDisplayName(cfg.scene()), this::scene));
        if (onDemo != null) rows.add(new MenuRow("Preview", null, onDemo));
        rows.add(new MenuRow("Adult mode schedule", scheduleSummary(), this::adultSchedule));
        String title = (adult ? "Parental controls (adult) · " : "Parental controls · ") + st.describeShort(now);
        showMenu(title, () -> { }, rows);
    }

    private String scheduleSummary() {
        String s = cfg.adultScheduleText();
        return s != null ? s : "Off";
    }

    /** Automatic daily adult-mode schedule: start time (e.g. kids' bedtime) and fall-back to kids' mode (e.g. 11 pm). */
    private void adultSchedule() {
        int s = cfg.adultStartMin(), e = cfg.adultEndMin();
        if (!HomeConfig.validWindow(s, e)) {
            pickScheduleStart(19 * 60 + 30, 23 * 60, true, this::kidsTime);
            return;
        }
        String[] items = {
                "Adult mode starts: " + HomeConfig.formatTime12(s),
                "Back to kids' mode: " + HomeConfig.formatTime12(e),
                "Turn schedule off"
        };
        show(dialog("Adult mode schedule", this::kidsTime).setItems(items, (d, which) -> {
            switch (which) {
                case 0: pickScheduleStart(s, e, false, this::adultSchedule); break;
                case 1: pickScheduleEnd(s, e, this::adultSchedule); break;
                case 2: applySchedule("", "", "Adult schedule off"); break;
            }
        }));
    }

    private void pickScheduleStart(int startMin, int endMin, boolean thenPickEnd, Runnable back) {
        timePicker("When should adult mode start?", startMin, back, chosen -> {
            if (thenPickEnd) {
                pickScheduleEnd(chosen, endMin, () -> pickScheduleStart(chosen, endMin, true, back));
            } else {
                saveSchedule(chosen, endMin, () -> pickScheduleStart(chosen, endMin, false, back));
            }
        });
    }

    private void pickScheduleEnd(int startMin, int endMin, Runnable back) {
        timePicker("When should kids' mode come back?", endMin, back,
                chosen -> saveSchedule(startMin, chosen, () -> pickScheduleEnd(startMin, chosen, back)));
    }

    private void saveSchedule(int startMin, int endMin, Runnable retry) {
        if (startMin == endMin) {
            Toast.makeText(a, "Start and end times need to be different", Toast.LENGTH_LONG).show();
            retry.run();
            return;
        }
        applySchedule(HomeConfig.formatTime24(startMin), HomeConfig.formatTime24(endMin),
                "Adult schedule: " + HomeConfig.formatTime12(startMin) + " – " + HomeConfig.formatTime12(endMin));
    }

    private void applySchedule(String start, String end, String msg) {
        cfg.setAdultSchedule(start, end);
        screenTime().clearModeOverride();
        timeChanged(msg);
        kidsTime();
    }

    /** Shared layout for remote-controlled numeric/time pickers (timePicker and postcode). */
    private android.widget.LinearLayout pickerBox(TextView shown, String hintText) {
        shown.setGravity(Gravity.CENTER);
        shown.setTextSize(40);
        shown.setPadding(0, 24, 0, 8);
        shown.setTextColor(a.getColor(R.color.soft));
        TextView hint = new TextView(a);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(14);
        hint.setPadding(24, 0, 24, 24);
        hint.setTextColor(a.getColor(R.color.muted));
        hint.setText(hintText);
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(shown);
        box.addView(hint);
        return box;
    }

    /** Bold, underlined, full-contrast highlight for the active field in a remote picker. */
    private android.text.SpannableString highlight(String text, int start, int end) {
        android.text.SpannableString t = new android.text.SpannableString(text);
        t.setSpan(new android.text.style.ForegroundColorSpan(a.getColor(R.color.text)), start, end, 0);
        t.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0);
        t.setSpan(new android.text.style.UnderlineSpan(), start, end, 0);
        return t;
    }

    /** Remote-friendly 12-hour time picker (5-minute steps): arrows adjust hour / minute / am-pm, OK saves. */
    private void timePicker(String title, int initialMin, Runnable up, java.util.function.IntConsumer done) {
        int rounded = Math.floorMod(initialMin, 1440);
        int[] hour12 = {((rounded / 60) % 12) == 0 ? 12 : (rounded / 60) % 12};
        int[] minute = {(rounded % 60) / 5 * 5};
        boolean[] pm = {(rounded / 60) >= 12};
        int[] field = {0};   // 0 = hour, 1 = minute, 2 = am/pm

        TextView shown = new TextView(a);
        android.widget.LinearLayout box = pickerBox(shown, "▲ ▼ change   ◀ ▶ move   OK to save");

        Runnable draw = () -> {
            String h = Integer.toString(hour12[0]);
            String m = String.format(Locale.ROOT, "%02d", minute[0]);
            String full = h + ":" + m + " " + (pm[0] ? "pm" : "am");
            int s0 = 0, e0 = h.length();
            int s1 = e0 + 1, e1 = s1 + m.length();
            int s2 = e1 + 1, e2 = full.length();
            shown.setText(highlight(full, field[0] == 0 ? s0 : field[0] == 1 ? s1 : s2,
                    field[0] == 0 ? e0 : field[0] == 1 ? e1 : e2));
        };
        draw.run();

        AlertDialog d = dialog(title, up).setView(box).create();
        d.setOnKeyListener((x, k, e) -> {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    break;
                default:
                    return false;   // Back cancels; volume and hardware keys keep working
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (field[0] == 0) hour12[0] = hour12[0] == 12 ? 1 : hour12[0] + 1;
                    else if (field[0] == 1) minute[0] = (minute[0] + 5) % 60;
                    else pm[0] = !pm[0];
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (field[0] == 0) hour12[0] = hour12[0] == 1 ? 12 : hour12[0] - 1;
                    else if (field[0] == 1) minute[0] = (minute[0] + 55) % 60;
                    else pm[0] = !pm[0];
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (field[0] > 0) field[0]--; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (field[0] < 2) field[0]++; break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    d.dismiss();
                    int h24 = (hour12[0] % 12) + (pm[0] ? 12 : 0);
                    done.accept(h24 * 60 + minute[0]);
                    return true;
            }
            draw.run();
            return true;
        });
        MainActivity.showDialog(d);
    }

    private void topUp() {
        String[] labels = new String[TOP_UPS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = "+" + TOP_UPS[i] + " minutes";
        show(dialog("Extra time today", this::kidsTime)
                .setItems(labels, (d, which) -> {
                    long t = System.currentTimeMillis();
                    ScreenTime st = screenTime();
                    st.addMinutes(t, TOP_UPS[which]);
                    timeChanged("Parental controls: " + st.describe(t));
                }));
    }

    private void timeChanged(String msg) {
        if (onTimeChanged != null) onTimeChanged.accept(msg);
        else Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    private void changed(String what) {
        Toast.makeText(a, what, Toast.LENGTH_SHORT).show();
        onChanged.run();
    }

    private void save(String key, String value) {
        if (value.trim().isEmpty()) return;
        cfg.set(key, value.trim());
        changed("Saved");
    }

    /** Single-choice list docked to the right with live preview as each item is highlighted. */
    private AlertDialog sidePicker(String title, String[] names, int cur, Runnable onCancel,
                                   java.util.function.IntConsumer onHighlight, java.util.function.IntConsumer onPick) {
        AlertDialog d = dialog(title, onCancel)
                .setSingleChoiceItems(names, cur, (x, which) -> { x.dismiss(); onPick.accept(which); })
                .create();
        d.getListView().setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                onHighlight.accept(pos);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        return d;
    }

    /** Each theme shows on Home as it's highlighted; the list sits at the side so Home can be seen. */
    private void theme() {
        String[] names = Look.names();
        Look saved = cfg.look();
        boolean[] picked = {false};
        AlertDialog d = sidePicker("Home theme", names, HomeConfig.indexOf(Look.ids(), saved.id),
                this::main,
                pos -> preview.accept(Look.ALL[pos]),
                which -> { picked[0] = true; cfg.set(HomeConfig.K_THEME, Look.ALL[which].id); changed("Home theme: " + names[which]); });
        d.setOnDismissListener(x -> { if (!picked[0]) preview.accept(saved); });
        MainActivity.showDialog(d);
        toSide(d);
    }

    /** A list dialog at the right-hand side with no dimming, so what it previews can be seen. */
    private void toSide(AlertDialog d) {
        android.view.Window w = d.getWindow();
        if (w == null) return;
        w.setDimAmount(0f);
        w.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.x = (int) (40 * a.getResources().getDisplayMetrics().density);
        lp.width = Math.round(a.getResources().getDisplayMetrics().widthPixels * .30f);   // clear of ScenePreview's card
        w.setAttributes(lp);
    }

    /** Each scene plays on a big card beside the list as it's highlighted, through a whole day. */
    private void scene() {
        String[] ids = cfg.availableScenes();
        String[] names = cfg.availableSceneNames();
        int cur = HomeConfig.indexOf(ids, cfg.scene());
        ScenePreview preview = new ScenePreview(a);
        AlertDialog d = sidePicker("Restricted scene", names, cur, this::kidsTime,
                pos -> preview.show(ids[pos]),
                which -> { cfg.set(HomeConfig.K_SCENE, ids[which]); changed("Restricted scene: " + names[which]); });
        d.setOnDismissListener(x -> preview.close());
        preview.open();
        preview.show(cfg.scene());
        MainActivity.showDialog(d);
        toSide(d);
    }

    // ---------- restricted apps ----------

    private String timedCountSummary() {
        if (cfg.timedAll()) return "All";
        TimedApps ta = timed();
        int n = 0;
        for (AppStore.App app : installed) if (ta.timed(app.pkg)) n++;
        return n == 0 ? "None" : n == 1 ? "1 app" : n + " apps";
    }

    /** "All apps" first, then every app on Home (ticked if restricted). Save applies; Back goes up without saving.
     *  Apps that can't be windowed (config.xml's unwindowable_apps) are listed, but ticking one explains why instead. */
    private void timedApps() {
        TimedApps ta = timed();
        List<AppStore.App> apps = new ArrayList<>();
        for (AppStore.App app : installed) {
            if (!ta.offered(app.pkg)) continue;
            apps.add(app);
        }
        String[] labels = new String[apps.size() + 1];
        boolean[] on = new boolean[labels.length];
        labels[0] = "All apps";
        on[0] = cfg.timedAll();
        java.util.Set<String> timedSet = cfg.timedList();
        for (int i = 0; i < apps.size(); i++) {
            String pkg = apps.get(i).pkg;
            boolean cant = ta.cantBeTimed(pkg) != null;
            labels[i + 1] = apps.get(i).label + (cant ? " (unsupported)" : "");
            on[i + 1] = !on[0] && timedSet.contains(pkg) && !cant;
        }
        show(dialog("Restricted apps", this::kidsTime)
                .setMultiChoiceItems(labels, on, (d, which, checked) -> {
                    android.widget.ListView lv = ((AlertDialog) d).getListView();
                    String why = which > 0 ? ta.cantBeTimed(apps.get(which - 1).pkg) : null;
                    if (checked && why != null) {
                        // `on` is the dialog's own record of the ticks (it already says true), and the row has ticked itself.
                        on[which] = false;
                        lv.post(() -> lv.setItemChecked(which, false));
                        show(new AlertDialog.Builder(a).setTitle(apps.get(which - 1).label + " can't be restricted")
                                .setMessage(why).setPositiveButton("OK", null));
                        return;
                    }
                    on[which] = checked;
                    if (checked && which == 0) {
                        for (int i = 1; i < on.length; i++) if (on[i]) { on[i] = false; lv.setItemChecked(i, false); }
                    } else if (checked && on[0]) {
                        on[0] = false;
                        lv.setItemChecked(0, false);
                    }
                })
                .setPositiveButton("Save", (d, w) -> {
                    String value;
                    if (on[0]) value = HomeConfig.TIMED_ALL;
                    else {
                        List<String> pkgs = new ArrayList<>();
                        for (int i = 0; i < apps.size(); i++) if (on[i + 1]) pkgs.add(apps.get(i).pkg);
                        value = String.join(",", pkgs);
                    }
                    cfg.set(HomeConfig.K_TIMED, value);
                    timed = null;   // pick up the saved list in timedCountSummary()
                    changed("Restricted apps: " + timedCountSummary());
                }));
    }

    private void minutes() {
        String[] labels = new String[MINUTES.length];
        int cur = -1;
        for (int i = 0; i < MINUTES.length; i++) {
            labels[i] = minutesLabel(MINUTES[i]);
            if (MINUTES[i] == cfg.ytMinutes()) cur = i;
        }
        show(dialog("Daily limit", this::kidsTime).setSingleChoiceItems(labels, cur, (d, which) -> {
            d.dismiss();
            cfg.set(HomeConfig.K_MINUTES, MINUTES[which]);
            changed("Daily limit: " + labels[which]);
        }));
    }

    /** 20 min, 1 hr, 1½ hr, 2 hr. */
    private static String minutesShort(int m) {
        if (m < 60 || (m % 30) != 0) return m + " min";
        String h = (m / 60) + (m % 60 == 30 ? "½" : "");
        return h + (m == 60 ? " hr" : " hrs");
    }

    /** 30 minutes, 1 hour, 1½ hours, 2 hours. */
    static String minutesLabel(int m) {
        if (m < 60 || (m % 30) != 0) return m + " minutes";
        String h = (m / 60) + (m % 60 == 30 ? "½" : "");
        return h + (m == 60 ? " hour" : " hours");
    }

    // ---------- grown-ups code ----------

    private void newCode() {
        arrows("New grown-up code", "Press 2 to 8 arrows, then OK", first ->
                arrows("New grown-up code", "Press it again, then OK", second -> {
                    if (!second.equals(first)) { Toast.makeText(a, "Those didn't match; code not changed", Toast.LENGTH_LONG).show(); main(); return; }
                    cfg.set(HomeConfig.K_CODE, first);
                    changed("Grown-up code changed");
                }));
    }

    /** Collect an arrow sequence from the remote; OK finishes it, Back goes back to Config. */
    private void arrows(String title, String hint, java.util.function.Consumer<String> done) {
        TextView shown = ParentGate.dotView(a);
        shown.setText(hint);
        StringBuilder typed = new StringBuilder();
        AlertDialog d = dialog(title, this::main).setView(shown).create();
        d.setOnKeyListener((x, k, e) -> {
            if (k == KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (HomeConfig.validCode(typed.toString())) { d.dismiss(); done.accept(typed.toString()); }
                return true;
            }
            char c = ParentGate.dpadChar(k);
            if (c == 0) return true;
            if (typed.length() < 8) typed.append(c);
            shown.setText(HomeConfig.dots(typed.length()));
            return true;
        });
        MainActivity.showDialog(d);
    }

    // ---------- weather: postcode, then suburb ----------

    /**
     * Four digits on the remote: up/down change the highlighted one, left/right move along, number keys type
     * straight in, and OK looks the postcode up. Australian postcodes only (Postcodes).
     */
    private void postcode(String start) {
        char[] digits = (Postcodes.valid(start) ? start : "2000").toCharArray();
        int[] at = {0};
        TextView shown = new TextView(a);
        shown.setLetterSpacing(0.3f);
        android.widget.LinearLayout box = pickerBox(shown, "▲ ▼ change the number   ◀ ▶ move   OK to find the suburb");
        Runnable draw = () -> shown.setText(highlight(new String(digits), at[0], at[0] + 1));
        draw.run();
        AlertDialog d = dialog("Weather: your postcode", this::main).setView(box).create();
        d.setOnKeyListener((x, k, e) -> {
            if (k == KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int i = at[0];
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                digits[i] = (char) ('0' + k - KeyEvent.KEYCODE_0);
                if (i < 3) at[0]++;
            } else switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: digits[i] = (char) ('0' + (digits[i] - '0' + 1) % 10); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: digits[i] = (char) ('0' + (digits[i] - '0' + 9) % 10); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (i > 0) at[0]--; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (i < 3) at[0]++; break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    d.dismiss();
                    suburbs(new String(digits));
                    return true;
                default: return true;
            }
            draw.run();
            return true;
        });
        MainActivity.showDialog(d);
    }

    /** The postcode's suburbs to pick from (just the one: picked straight away). Back goes back to the postcode. */
    private void suburbs(String postcode) {
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            List<Weather.Place> found;
            try { found = Postcodes.find(app, postcode); } catch (java.io.IOException e) { found = new ArrayList<>(); }
            List<Weather.Place> places = found;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (places.isEmpty()) {
                    Toast.makeText(a, "No Australian suburbs have the postcode " + postcode, Toast.LENGTH_LONG).show();
                    postcode(postcode);
                    return;
                }
                if (places.size() == 1) { setPlace(postcode, places.get(0)); return; }
                String[] labels = new String[places.size()];
                int cur = -1;
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = places.get(i).label;
                    if (postcode.equals(cfg.postcode()) && places.get(i).name.equals(cfg.place())) cur = i;
                }
                show(dialog("Which suburb? (" + postcode + ")", () -> postcode(postcode))
                        .setSingleChoiceItems(labels, cur, (d, which) -> { d.dismiss(); setPlace(postcode, places.get(which)); }));
            });
        }, "postcode").start();
    }

    private void setPlace(String postcode, Weather.Place pl) {
        cfg.setPlace(postcode, pl.name, pl.lat, pl.lon, pl.tz);
        changed("Weather: " + pl.name);
    }

    // ---------- bin night ----------

    private void bins() {
        String[] labels = new String[8];
        labels[0] = "No bin night";
        System.arraycopy(DAYS, 0, labels, 1, 7);
        int cur = cfg.binsDay() >= 1 && cfg.binsDay() <= 7 ? cfg.binsDay() : 0;
        show(dialog("Bin night", this::main).setSingleChoiceItems(labels, cur, (d, which) -> {
            d.dismiss();
            if (which == 0) { cfg.set(HomeConfig.K_BINS_DAY, 0); changed("No bin night"); return; }
            binsEvery(which);
        }));
    }

    /** Step 2: the bin that goes out every week (the council decides: green at Lake Mac, red in Newcastle). */
    private void binsEvery(int day) {
        String[] labels = {Bins.label(Bins.GREEN) + ", e.g. Lake Macquarie", Bins.label(Bins.RED) + ", e.g. Newcastle",
                Bins.label(Bins.YELLOW)};
        show(dialog("Which bin goes out every week?", this::bins)
                .setSingleChoiceItems(labels, HomeConfig.indexOf(Bins.ALL, cfg.binsEvery()), (d, which) -> {
                    d.dismiss();
                    binsTurn(day, Bins.ALL[which]);
                }));
    }

    /** Step 3: which of the other two goes out this week, so Home knows which week is which. */
    private void binsTurn(int day, String every) {
        Calendar next = nextDay(day);
        String when = day == Calendar.getInstance().get(Calendar.DAY_OF_WEEK) ? "tonight" : "this " + DAYS[day - 1];
        String[] pair = Bins.pair(every);
        String[] labels = new String[2];
        for (int i = 0; i < 2; i++) labels[i] = cap(every) + " and " + pair[i] + " (" + Bins.name(pair[i]) + ")";
        show(dialog("Which bins go out " + when + "?", () -> binsEvery(day)).setItems(labels, (d, which) -> {
            // Anchor: a bin night when pair[0] goes out (this week's, or next week's if pair[1] goes out now).
            if (which == 1) next.add(Calendar.DAY_OF_MONTH, 7);
            cfg.set(HomeConfig.K_BINS_DAY, day);
            cfg.set(HomeConfig.K_BINS_EVERY, every);
            cfg.set(HomeConfig.K_BINS_ANCHOR, String.format(Locale.ROOT, "%tF", next));
            changed("Bin night: " + DAYS[day - 1] + ", " + every + " every week");
        }));
    }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** Today if it's that day of the week, otherwise the next one. */
    static Calendar nextDay(int dayOfWeek) {
        Calendar c = Calendar.getInstance();
        while (c.get(Calendar.DAY_OF_WEEK) != dayOfWeek) c.add(Calendar.DAY_OF_MONTH, 1);
        return c;
    }

    // ---------- cameras ----------

    private void cameras() {
        boolean anyCam = !cfg.camUrl(0).isEmpty() || !cfg.camUrl(1).isEmpty();
        String[] labels = new String[HomeConfig.CAMERAS + (anyCam ? 2 : 0)];
        for (int i = 0; i < HomeConfig.CAMERAS; i++) {
            String suffix = cfg.camHidden(i) ? " (hidden)" : (cfg.camRawUrl(i).isEmpty() ? " (not set)" : "");
            labels[i] = "Camera " + (i + 1) + ": " + cfg.camName(i) + suffix;
        }
        if (anyCam) {
            labels[HomeConfig.CAMERAS] = "Overlay corner: " + HomeConfig.overlayCornerName(cfg.overlayCorner());
            labels[HomeConfig.CAMERAS + 1] = "Overlay size: " + HomeConfig.overlaySizeName(cfg.overlaySize());
        }
        show(dialog(anyCam ? "Camera config: names, addresses & overlay" : "Camera config: names and addresses", this::main).setItems(labels, (d, which) -> {
            if (which < HomeConfig.CAMERAS) camera(which);
            else if (which == HomeConfig.CAMERAS) overlayCorner();
            else overlaySize();
        }));
    }

    private void overlayCorner() {
        int checked = HomeConfig.indexOf(HomeConfig.OVERLAY_CORNERS, cfg.overlayCorner());
        show(dialog("Camera overlay corner", this::cameras)
                .setSingleChoiceItems(HomeConfig.OVERLAY_CORNER_NAMES, checked, (d, which) -> {
                    d.dismiss();
                    cfg.set(HomeConfig.K_OVERLAY_CORNER, HomeConfig.OVERLAY_CORNERS[which]);
                    Toast.makeText(a, "Overlay corner: " + HomeConfig.OVERLAY_CORNER_NAMES[which], Toast.LENGTH_SHORT).show();
                    cameras();
                }));
    }

    private void overlaySize() {
        int checked = HomeConfig.indexOf(HomeConfig.OVERLAY_SIZES, cfg.overlaySize());
        show(dialog("Camera overlay size", this::cameras)
                .setSingleChoiceItems(HomeConfig.OVERLAY_SIZE_NAMES, checked, (d, which) -> {
                    d.dismiss();
                    cfg.set(HomeConfig.K_OVERLAY_SIZE, HomeConfig.OVERLAY_SIZES[which]);
                    Toast.makeText(a, "Overlay size: " + HomeConfig.OVERLAY_SIZE_NAMES[which], Toast.LENGTH_SHORT).show();
                    cameras();
                }));
    }

    private void camera(int i) {
        String n = Integer.toString(i + 1);
        boolean hidden = cfg.camHidden(i);
        boolean hasAddr = !cfg.camRawUrl(i).isEmpty();
        List<String> items = new ArrayList<>();
        items.add("Name: " + cfg.camName(i));
        items.add("Address (rtsp://…)");
        items.add("Full-screen address (optional)");
        if (hidden) items.add("Show this camera");
        else if (hasAddr) items.add("Hide this camera");
        show(dialog("Camera " + n, this::cameras).setItems(items.toArray(new String[0]), (d, which) -> {
            switch (which) {
                case 0: text("Camera " + n + " name", cfg.camName(i), false, () -> camera(i), v -> save(HomeConfig.K_CAM_NAME + n, v)); break;
                case 1: text("Camera " + n + " address", cfg.camRawUrl(i), true, () -> camera(i), v -> {
                    if (v.trim().isEmpty()) return;
                    cfg.set(HomeConfig.K_CAM_URL + n, v.trim());
                    cfg.setCamHidden(i, false);
                    changed("Saved");
                }); break;
                case 2: text("Camera " + n + " full-screen address (blank: same as above)", cfg.camRawHdUrl(i).equals(cfg.camRawUrl(i)) ? "" : cfg.camRawHdUrl(i), true, () -> camera(i), v -> {
                    cfg.set(HomeConfig.K_CAM_HD + n, v.trim());
                    changed("Saved");
                }); break;
                case 3:
                    cfg.setCamHidden(i, !hidden);
                    changed("Camera " + n + (hidden ? " shown" : " hidden"));
                    break;
            }
        }));
    }

    // ---------- remote buttons ----------

    private void ensureRemoteService(java.util.function.Consumer<Boolean> onUiDone) {
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            boolean ok = RemoteKeys.ensureService(app);
            if (onUiDone != null) a.runOnUiThread(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) onUiDone.accept(ok);
            });
        }, "remote-keys").start();
    }

    private void disableRemoteService() {
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> RemoteKeys.disableService(app), "remote-keys").start();
    }

    private void remoteButtons() {
        RemoteKeys rk = remoteKeys();
        TimedApps ta = timed();
        java.util.Map<String, String> mapped = rk.all();
        if (!mapped.isEmpty() && !RemoteKeyService.isConnected()) ensureRemoteService(null);
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow("+ Remap a button", null, this::captureButton));
        for (java.util.Map.Entry<String, String> e : mapped.entrySet()) {
            String id = e.getKey();
            rows.add(new MenuRow(rk.label(id), RemoteKeys.targetLabel(e.getValue(), installed, ta, cfg), () -> editButton(id)));
        }
        if (!mapped.isEmpty() && !RemoteKeyService.isConnected()) {
            rows.add(new MenuRow("Turn on remote buttons…", null, this::enableRemoteService));
        }
        if (mapped.size() >= 2) {
            rows.add(new MenuRow("Reset all buttons", null, this::clearAllButtons));
        }
        showMenu("Remote buttons", () -> { }, rows);
    }

    /** Listen for one button press on the remote, then ask what it should do. */
    private void captureButton() {
        float dp = a.getResources().getDisplayMetrics().density;
        TextView prompt = new TextView(a);
        prompt.setGravity(Gravity.CENTER);
        prompt.setTextSize(24);
        prompt.setTextColor(a.getColor(R.color.text));
        prompt.setPadding(Math.round(24 * dp), Math.round(28 * dp), Math.round(24 * dp), Math.round(10 * dp));
        prompt.setText("Press the button on your remote");

        TextView hint = new TextView(a);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(15);
        hint.setTextColor(a.getColor(R.color.muted));
        hint.setPadding(Math.round(24 * dp), 0, Math.round(24 * dp), Math.round(28 * dp));
        String readyHint = "Back cancels. Arrows, OK, Home, Volume and Power stay as they are.";
        hint.setText(RemoteKeyService.isConnected() ? readyHint : "Starting remote buttons…");

        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(prompt);
        box.addView(hint);

        AlertDialog d = dialog("Remap a button", this::remoteButtons).setView(box).create();
        boolean[] captured = {false};
        java.util.function.Consumer<String> accept = id -> {
            if (captured[0] || !d.isShowing()) return;
            captured[0] = true;
            RemoteKeyService.setCapture(null);
            d.dismiss();
            pickTarget(id, this::remoteButtons);
        };
        java.util.function.IntConsumer reject = code -> {
            if (!d.isShowing()) return;
            hint.setTextColor(a.getColor(R.color.soft));
            hint.setText(RemoteKeys.defaultKeyLabel(RemoteKeys.keyId(code, 0)) + " is needed to use the TV — try another button");
        };

        RemoteKeyService.setCapture(new RemoteKeyService.CaptureListener() {
            @Override public void onCaptured(String id) { accept.accept(id); }
            @Override public void onBlocked(int keyCode) { reject.accept(keyCode); }
        });
        // Fallback if AccessibilityService isn't bound yet: keys that reach the dialog still work.
        d.setOnKeyListener((x, code, ev) -> {
            if (RemoteKeys.passThroughInCapture(code)) return false;
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (ev.getRepeatCount() > 0) return true;
            if (RemoteKeys.blocked(code)) { reject.accept(code); return true; }
            String id = RemoteKeys.keyId(ev);
            if (id != null) accept.accept(id);
            return true;
        });
        d.setOnDismissListener(x -> RemoteKeyService.setCapture(null));
        box.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { }
            @Override public void onViewDetachedFromWindow(android.view.View v) { RemoteKeyService.setCapture(null); }
        });
        MainActivity.showDialog(d);

        if (!RemoteKeyService.isConnected()) {
            ensureRemoteService(ok -> {
                if (!d.isShowing() || captured[0]) return;
                hint.setTextColor(a.getColor(R.color.muted));
                hint.setText(ok ? readyHint
                        : "Press a button now, or turn on Home remote buttons in TV Settings > Accessibility.");
            });
        }
    }

    private void editButton(String id) {
        RemoteKeys rk = remoteKeys();
        String target = rk.target(id);
        String title = rk.label(id) + " → " + RemoteKeys.targetLabel(target, installed, timed(), cfg);
        String[] items = {"Change what it does", "Rename this button…", "Reset to normal"};
        show(dialog(title, this::remoteButtons).setItems(items, (d, which) -> {
            switch (which) {
                case 0: pickTarget(id, () -> editButton(id)); break;
                case 1:
                    text("Name for " + RemoteKeys.defaultKeyLabel(id), rk.customLabel(id), false,
                            () -> editButton(id), v -> {
                                rk.setLabel(id, v);
                                Toast.makeText(a, "Saved", Toast.LENGTH_SHORT).show();
                                remoteButtons();
                            });
                    break;
                case 2:
                    rk.remove(id);
                    if (rk.isEmpty()) disableRemoteService();
                    Toast.makeText(a, "Back to normal", Toast.LENGTH_SHORT).show();
                    remoteButtons();
                    break;
            }
        }));
    }

    /** Pick an app or TV action for button `id`. */
    private void pickTarget(String id, Runnable back) {
        RemoteKeys rk = remoteKeys();
        TimedApps ta = timed();
        List<String> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (String t : RemoteKeys.ACTIONS) {
            if (!RemoteKeys.actionAvailable(t, cfg)) continue;
            targets.add(t);
            String lbl = RemoteKeys.targetLabel(t, installed, ta, cfg);
            if ((RemoteKeys.ACTION_WORLD_NEXT.equals(t) || RemoteKeys.ACTION_WORLD_PREV.equals(t)) && !cfg.worldCamsOn()) {
                lbl += " (turn on World cam first)";
            }
            labels.add(lbl);
        }
        // Favourites first, then visible apps, then hidden apps (familiar order from Home).
        List<AppStore.App> favs = new ArrayList<>(), vis = new ArrayList<>(), hid = new ArrayList<>();
        for (AppStore.App app : installed) {
            if (app.hidden) hid.add(app);
            else if (app.fav) favs.add(app);
            else vis.add(app);
        }
        List<AppStore.App> ordered = new ArrayList<>(installed.size());
        ordered.addAll(favs); ordered.addAll(vis); ordered.addAll(hid);
        for (AppStore.App app : ordered) {
            targets.add(RemoteKeys.appTarget(app.pkg));
            labels.add(RemoteKeys.appTargetLabel(app.pkg, app.label, ta));
        }

        String cur = rk.target(id);
        int checked = cur != null ? targets.indexOf(cur) : -1;
        show(dialog("What should " + rk.label(id) + " do?", back)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    String chosen = targets.get(which);
                    if ((RemoteKeys.ACTION_WORLD_NEXT.equals(chosen) || RemoteKeys.ACTION_WORLD_PREV.equals(chosen))
                            && !cfg.worldCamsOn()) {
                        show(dialog("Turn on World cam first", () -> pickTarget(id, back))
                                .setMessage("This action steps through World Cams. Turn on World cam in Settings (or tap the camera icon in Home's top corner) first.")
                                .setPositiveButton("Turn on & map", (x, w) -> {
                                    cfg.set(HomeConfig.K_WORLD_CAMS, "on");
                                    WorldCams wc = new WorldCams(a);
                                    wc.setSlotUsesWorld(0, true);
                                    wc.setSlotUsesWorld(1, false);
                                    if (!cfg.camerasOn()) cfg.set(HomeConfig.K_CAMERAS, "on");
                                    rk.set(id, chosen);
                                    changed(rk.label(id) + " → " + RemoteKeys.targetLabel(chosen, installed, ta, cfg));
                                })
                                .setNegativeButton("Back", (x, w) -> pickTarget(id, back)));
                        return;
                    }
                    rk.set(id, chosen);
                    Toast.makeText(a, rk.label(id) + " → " + labels.get(which), Toast.LENGTH_SHORT).show();
                    remoteButtons();
                }));
    }

    private void enableRemoteService() {
        Toast.makeText(a, "Starting remote buttons…", Toast.LENGTH_SHORT).show();
        ensureRemoteService(ok -> {
            if (ok) {
                Toast.makeText(a, "Remote buttons ready", Toast.LENGTH_SHORT).show();
                remoteButtons();
                return;
            }
            show(dialog("Turn on remote buttons", this::remoteButtons)
                    .setMessage("Home couldn't turn on its remote-buttons service automatically.\n\n"
                            + "Open TV Settings > Device Preferences (or System) > Accessibility > Home remote buttons, and switch it on.")
                    .setPositiveButton("Open TV Settings", (d, w) -> RemoteKeys.execute(a, RemoteKeys.ACTION_SETTINGS))
                    .setNegativeButton("Back", (d, w) -> remoteButtons()));
        });
    }

    private void clearAllButtons() {
        show(dialog("Reset all remote buttons?", this::remoteButtons)
                .setMessage("Every remapped remote button goes back to how the TV handles it normally.")
                .setPositiveButton("Reset", (d, w) -> {
                    remoteKeys().clear();
                    disableRemoteService();
                    Toast.makeText(a, "All remote buttons back to normal", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (d, w) -> remoteButtons()));
    }

    // ---------- reset ----------

    private void reset() {
        show(dialog(RESET + "?", this::main)
                .setMessage("Every setting goes back to how Home came (its built-in config.xml). "
                        + "Screen time used today, remote buttons, and the app tiles' order, favourites and hidden apps are kept.")
                .setPositiveButton("Reset", (d, w) -> { cfg.reset(); screenTime().clearModeOverride(); changed("Settings reset to default"); })
                .setNegativeButton("Cancel", (d, w) -> main()));
    }

    // ---------- text ----------

    private void text(String title, String current, boolean uri, Runnable up, java.util.function.Consumer<String> done) {
        text(title, current, uri, null, up, done);
    }

    /**
     * Typed with the TV's on-screen keyboard. The keyboard comes up straight away, and its Done / Enter key saves,
     * so there's no need to find a button. echo (optional) shows below what the words will look like on Home.
     */
    private void text(String title, String current, boolean uri, java.util.function.Function<String, String> echo,
                      Runnable up, java.util.function.Consumer<String> done) {
        float dp = a.getResources().getDisplayMetrics().density;
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(uri ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        // Done on the keyboard, and keep the keyboard from going full screen over the dialog.
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setTextSize(uri ? 20 : 30);
        e.setGravity(Gravity.CENTER);
        e.setTextColor(a.getColor(R.color.text));
        e.setText(current);
        e.setSelection(e.getText().length());

        TextView shown = new TextView(a);
        shown.setGravity(Gravity.CENTER);
        shown.setTextSize(18);
        shown.setTextColor(a.getColor(R.color.soft));
        shown.setPadding(0, Math.round(10 * dp), 0, 0);
        TextView hint = new TextView(a);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(14);
        hint.setTextColor(a.getColor(R.color.muted));
        hint.setPadding(0, Math.round(14 * dp), 0, 0);
        hint.setText("Press ✓ on the keyboard to save");

        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(24 * dp);
        box.setPadding(pad, pad / 2, pad, pad);
        box.addView(e);
        if (echo != null) {
            box.addView(shown);
            shown.setText(echo.apply(current));
            e.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int st, int c, int af) { }
                @Override public void onTextChanged(CharSequence t, int st, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable t) { shown.setText(echo.apply(t.toString())); }
            });
        }
        box.addView(hint);

        AlertDialog d = dialog(title, up).setView(box)
                .setPositiveButton("Save", (x, w) -> done.accept(e.getText().toString()))
                .create();
        boolean[] saved = {false};
        e.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !enter) return false;
            if (saved[0]) return true;
            saved[0] = true;
            d.dismiss();
            done.accept(e.getText().toString());
            return true;
        });
        android.view.Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MainActivity.showDialog(d);
        e.requestFocus();
    }
}
