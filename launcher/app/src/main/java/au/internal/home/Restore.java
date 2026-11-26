// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * "Put the TV back": undo everything done to this TV, from the TV itself. The undo list is the repo's
 * restore.sh, built into the app as an asset (launcher/app/build.gradle), and mirrored into filesDir/restore.sh
 * when a household-specific script is bundled so generic in-app self-updates never clobber it. Home copies
 * the script to the TV over its own adb connection (LocalAdb: apps can't re-enable packages or change the home
 * screen themselves) and runs it with shell rights. The script ends by uninstalling Home and restarting the TV,
 * in the background, so it can finish after Home has gone.
 *
 * Before the script, Home puts back what only it can reach: the TV picture settings it changes through TCL's
 * API (HomeConfig restore_backlight / restore_blue_light).
 */
final class Restore {
    private static final String TAG = "HomeRestore";
    static final String ON_TV = "/data/local/tmp/home-restore.sh";
    private static final String TEMPLATE_MARKER = "# Examples of the shape each entry takes";
    private static final int CHUNK = 3_000;

    private static File savedFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "restore.sh");
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /**
     * If the APK bundles a household-specific restore.sh (i.e. not the generic template), persist it in
     * filesDir/restore.sh so later generic self-updates keep this TV's real undo list intact.
     */
    static void seedIfBundled(Context c) {
        try {
            String bundled = readAll(c.getAssets().open("restore.sh"));
            if (bundled.trim().isEmpty() || bundled.contains(TEMPLATE_MARKER)) return;
            File dst = savedFile(c);
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(bundled.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "seed restore.sh failed: " + e.getMessage());
        }
    }

    /** The TV's saved restore.sh if present, otherwise the script built into the APK. */
    static String script(Context c) throws IOException {
        File saved = savedFile(c);
        if (saved.isFile() && saved.length() > 0) {
            return readAll(new FileInputStream(saved));
        }
        return readAll(c.getAssets().open("restore.sh"));
    }

    /** Copy the script onto the TV (base64 in chunks, so no command gets long), ready to run. */
    private static void push(Context c) throws IOException {
        String b64 = Base64.encodeToString(script(c).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String tmp = ON_TV + ".b64";
        LocalAdb.exec(c, "rm -f " + tmp, 5_000);
        for (int i = 0; i < b64.length(); i += CHUNK)
            LocalAdb.exec(c, "echo -n '" + b64.substring(i, Math.min(b64.length(), i + CHUNK)) + "' >> " + tmp, 5_000);
        String out = LocalAdb.exec(c, "base64 -d " + tmp + " > " + ON_TV + " && rm " + tmp + " && echo pushed", 5_000);
        if (!out.trim().endsWith("pushed")) throw new IOException("couldn't copy the script to the TV: " + out.trim());
    }

    /** Copies the script to the TV and has its shell read it without running anything (sh -n). Returns "ok"
     *  plus its size, or the shell's complaint. For checking a build on the TV (adb RESTORE_CHECK). */
    static String check(Context c) {
        try {
            push(c);
            String out = LocalAdb.exec(c, "sh -n " + ON_TV + " && echo ok $(wc -l < " + ON_TV + ") lines; rm -f " + ON_TV, 5_000);
            return out.trim();
        } catch (IOException | RuntimeException e) {
            return "failed: " + e.getMessage();
        }
    }

    /** Couldn't get the script onto the TV (usually: Home's adb key isn't allowed), so nothing was changed. */
    static final class NotStarted extends IOException {
        NotStarted(IOException cause) { super(cause.getMessage(), cause); }
    }

    /** Puts the TV back. Blocking (a minute or so); call off the main thread. Returns the script's output. */
    static String run(Context c) throws IOException {
        try { push(c); } catch (IOException e) { throw new NotStarted(e); }
        HomeConfig cfg = new HomeConfig(c);
        int backlight = cfg.restoreBacklight();
        int blue = cfg.restoreBlueLight();
        if (blue >= 0) TvBlueLight.set(c, blue == 1);
        if (backlight >= 0) TvBacklight.fadeTo(c, null, backlight);
        Log.i(TAG, "running " + ON_TV);
        String out = LocalAdb.exec(c, "sh " + ON_TV + " < /dev/null 2>&1", 120_000);
        Log.i(TAG, out);
        return out;
    }
}
