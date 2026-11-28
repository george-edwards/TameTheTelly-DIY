// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks a remote latest.json manifest (hosted on GitHub) for newer signed Home APKs, verifies
 * SHA-256, and installs the update cleanly via LocalAdb (detached with setsid so pm install -r
 * survives Home's own process restart and relaunches Home afterwards).
 */
final class Updater {
    private static final String TAG = "HomeUpdater";

    interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    static final class Release {
        final int versionCode;
        final String versionName;
        final String url;
        final String sha256;
        final String notes;

        Release(int versionCode, String versionName, String url, String sha256, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    /**
     * Unattended auto-install is safe when the TV screen is asleep/off, or during the quiet
     * overnight window (3:00–4:59 am) while nobody is actively watching a timed app.
     */
    static boolean isSafeAutoInstallWindow(boolean screenInteractive, int hourOfDay, boolean watching) {
        if (!screenInteractive) return true;
        return hourOfDay >= 3 && hourOfDay < 5 && !watching;
    }

    static Release parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            int code = o.optInt("versionCode", 0);
            String name = o.optString("versionName", "").trim();
            String url = o.optString("url", "").trim();
            String sha = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
            String notes = o.optString("notes", "").trim();
            if (code <= 0 || name.isEmpty() || !url.startsWith("https://")) return null;
            if (!sha.matches("[0-9a-f]{64}")) return null;
            return new Release(code, name, url, sha, notes);
        } catch (JSONException e) {
            return null;
        }
    }

    static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String currentVersionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Fetch and parse latest.json from `manifestUrl`. Call off the main thread. */
    static Release fetch(String manifestUrl) throws IOException {
        if (manifestUrl == null || !manifestUrl.startsWith("https://")) {
            throw new IOException("No update URL configured");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(manifestUrl).openConnection();
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("User-Agent", "HomeTV-Updater");
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            Release r = parse(sb.toString());
            if (r == null) throw new IOException("Invalid update manifest");
            return r;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download `r.url` into external cache and verify its SHA-256 against `r.sha256`.
     * Reuses an already-downloaded file if its SHA-256 matches. Call off the main thread.
     */
    static File download(Context c, Release r, ProgressListener onProgress, AtomicBoolean cancelled) throws IOException {
        File dir = c.getExternalCacheDir();
        if (dir == null) throw new IOException("External cache unavailable");
        File apk = new File(dir, "home-update.apk");
        if (apk.isFile() && r.sha256.equalsIgnoreCase(sha256File(apk))) {
            long len = apk.length();
            if (onProgress != null) onProgress.onProgress(len, len);
            return apk;
        }
        File part = new File(dir, "home-update.apk.part");
        if (part.exists()) part.delete();

        String gotSha = downloadStream(r.url, part, onProgress, cancelled);
        if (!r.sha256.equalsIgnoreCase(gotSha)) {
            part.delete();
            throw new IOException("Checksum mismatch (expected " + r.sha256.substring(0, 8) + "…)");
        }
        if (apk.exists()) apk.delete();
        if (!part.renameTo(apk)) {
            part.delete();
            throw new IOException("Could not save downloaded APK");
        }
        return apk;
    }

    /** Install a previously downloaded and verified APK via detached `pm install -r` over LocalAdb. */
    static void installDownloaded(Context c, Release r, File apk) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("Downloaded APK missing");
        // Preserve household defaults, restore.sh, and any bundled custom scene in app data before overwriting the APK.
        new HomeConfig(c).freezeToPrefs();
        Restore.seedIfBundled(c);
        CustomScene.seedIfBundled(c);

        String src = apk.getAbsolutePath().replace("'", "'\\''");
        String tmp = "/data/local/tmp/home-update.apk";
        String pkg = c.getPackageName();
        String cmd = "cp '" + src + "' " + tmp
                + " && setsid sh -c 'sleep 1; pm install -r " + tmp
                + "; rm -f " + tmp + " '\''" + src + "'\''; "
                + "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n " + pkg + "/.MainActivity'"
                + " </dev/null >/dev/null 2>&1 & sleep 1";
        Log.i(TAG, "installing update v" + r.versionName + " (" + r.versionCode + ")");
        LocalAdb.exec(c, cmd, 15_000);
    }

    /** Convenience: download, verify, and install in one call. Call off the main thread. */
    static void downloadAndInstall(Context c, Release r) throws IOException {
        File apk = download(c, r, null, null);
        installDownloaded(c, r, apk);
    }

    private static String sha256File(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String downloadStream(String urlStr, File dest, ProgressListener onProgress, AtomicBoolean cancelled)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "HomeTV-Updater");
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("Download failed (HTTP " + status + ")");
            long total = conn.getContentLengthLong();
            long done = 0L;
            long lastReport = 0L;
            if (onProgress != null) onProgress.onProgress(0L, total);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled != null && cancelled.get()) {
                        throw new IOException("Cancelled");
                    }
                    if (n == 0) continue;
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    done += n;
                    if (onProgress != null && (done - lastReport >= 32 * 1024 || (total > 0 && done >= total))) {
                        lastReport = done;
                        onProgress.onProgress(done, total);
                    }
                }
            } catch (IOException e) {
                dest.delete();
                throw e;
            }
            if (onProgress != null) onProgress.onProgress(done, total > 0 ? total : done);
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            conn.disconnect();
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
}
