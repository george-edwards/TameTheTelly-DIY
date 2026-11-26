// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * A tiny adb client that talks to the TV's own adbd (network debugging on 127.0.0.1:5555), so Home can
 * run the few shell commands an app isn't allowed to: sizing YouTube's window and restarting it framed.
 *
 * Legacy adb protocol (no TLS): CNXN, then AUTH with an RSA key. The first time, the TV asks
 * "Allow debugging?" for Home's key; tick "Always allow" and it's remembered (Home's key lives in its
 * private storage; the TV keeps the public half in /data/misc/adb/adb_keys).
 */
final class LocalAdb {
    private static final String TAG = "HomeAdb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo prefix for SHA-1: adbd's token is signed as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static KeyPair keys;

    /** Run `cmd` in an adb shell on this TV and return its output. Waits up to authWaitMs for the
     *  "Allow debugging?" prompt to be accepted the first time. Call off the main thread. */
    static synchronized String exec(Context c, String cmd, long authWaitMs) throws IOException {
        KeyPair kp = keys(c);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3_000);
            s.setSoTimeout((int) Math.max(10_000, authWaitMs));
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));

            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected " + Integer.toHexString(m.cmd));
                if (!sentSig) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    // Key not known yet: offer it. The TV shows its prompt; the reply (CNXN) waits for a tap.
                    Log.i(TAG, "asking the TV to allow Home's adb key");
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) kp.getPublic()));
                    sentKey = true;
                } else throw new IOException("adb key refused");
            }

            s.setSoTimeout(30_000);
            int local = 1;
            send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) remote = m.arg0;
                else if (m.cmd == WRTE) { result.write(m.data); send(out, OKAY, local, m.arg0, new byte[0]); }
                else if (m.cmd == CLSE) { send(out, CLSE, local, remote, new byte[0]); break; }
            }
            return result.toString("UTF-8");
        }
    }

    /** True if the TV accepts Home's key, waiting up to authWaitMs for the "Allow debugging?" prompt. */
    static boolean ready(Context c, long authWaitMs) {
        try { return exec(c, "echo ok", authWaitMs).trim().equals("ok"); }
        catch (IOException | RuntimeException e) { Log.w(TAG, "adb not available: " + e.getMessage()); return false; }
    }

    /** True if USB debugging (ADB) is currently enabled in Android Settings. Reading Settings.Global needs no permission. */
    static boolean isAdbEnabled(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** True if Developer options is unlocked in Android Settings. */
    static boolean isDevOptionsEnabled(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    static String enableInstructions(Context c) {
        if (isDevOptionsEnabled(c)) {
            return "Turn on USB debugging under Developer options, then come back and try again.";
        }
        return "Open About, click Build 7 times to unlock Developer options, then turn on USB debugging.";
    }

    static String settingsButtonLabel(Context c) {
        return isDevOptionsEnabled(c) ? "Open Developer options" : "Open About";
    }

    /** Open the TV's Developer options (if unlocked) or About screen (to tap Build 7 times), falling back to Settings. */
    static void openAdbSettings(Context c) {
        boolean devOn = isDevOptionsEnabled(c);
        String[] actions = devOn
                ? new String[]{Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS}
                : new String[]{Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS};
        String highlightKey = devOn ? "enable_adb" : "build";
        for (String action : actions) {
            try {
                Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Hint Settings to scroll/focus directly on USB debugging or Build number where supported.
                android.os.Bundle args = new android.os.Bundle();
                args.putString(":settings:fragment_args_key", highlightKey);
                i.putExtra(":settings:fragment_args_key", highlightKey);
                i.putExtra(":settings:show_fragment_args", args);
                c.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
    }

    /**
     * Tell the user USB debugging is off with clear, numbered TV-remote steps and a direct shortcut
     * to Developer options / About. Optional `onOpenAnyway` adds an "Open anyway" button (used when
     * opening a restricted app so kids aren't locked out).
     */
    static void showAdbRequiredDialog(Activity a, String reason, Runnable onOpenAnyway, Runnable onDone) {
        boolean devOn = isDevOptionsEnabled(a);
        float d = a.getResources().getDisplayMetrics().density;

        android.widget.LinearLayout body = new android.widget.LinearLayout(a);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padH = Math.round(22 * d), padV = Math.round(12 * d);
        body.setPadding(padH, padV, padH, Math.round(6 * d));

        android.widget.TextView intro = new android.widget.TextView(a);
        intro.setText(reason);
        intro.setTextColor(0xFFE2E8F0);
        intro.setTextSize(14.5f);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = Math.round(12 * d);
        body.addView(intro, ilp);

        String[] steps = devOn
                ? new String[]{
                        "Select \"" + settingsButtonLabel(a) + "\" below",
                        "Scroll down to Debugging → USB debugging",
                        "Turn USB debugging ON and confirm OK",
                        "Press Home on the remote to come back"
                }
                : new String[]{
                        "Select \"" + settingsButtonLabel(a) + "\" below",
                        "Scroll down to Android TV OS build and press OK 7 times",
                        "Press Back → open Developer options → turn USB debugging ON",
                        "Press Home on the remote to come back"
                };

        for (int i = 0; i < steps.length; i++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Math.round(8 * d);

            android.widget.TextView badge = new android.widget.TextView(a);
            badge.setText(String.valueOf(i + 1));
            badge.setTextColor(0xFF0F172A);
            badge.setTextSize(12.5f);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            pill.setColor(0xFFFFD166);
            badge.setBackground(pill);
            int sz = Math.round(22 * d);
            android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(sz, sz);
            blp.rightMargin = Math.round(10 * d);
            row.addView(badge, blp);

            android.widget.TextView txt = new android.widget.TextView(a);
            txt.setText(steps[i]);
            txt.setTextColor(0xFFF8FAFC);
            txt.setTextSize(14f);
            row.addView(txt, new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            body.addView(row, rlp);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(devOn ? "Turn on USB debugging" : "Enable Developer options & USB debugging")
                .setView(body)
                .setPositiveButton(settingsButtonLabel(a), (dialog, w) -> {
                    openAdbSettings(a);
                    if (onDone != null) onDone.run();
                })
                .setNegativeButton("Cancel", (dialog, w) -> {
                    if (onDone != null) onDone.run();
                })
                .setOnCancelListener(dialog -> {
                    if (onDone != null) onDone.run();
                });
        if (onOpenAnyway != null) {
            b.setNeutralButton("Open anyway", (dialog, w) -> onOpenAnyway.run());
        }
        AlertDialog dialog = b.create();
        if (a instanceof MainActivity) MainActivity.showDialog(dialog);
        else dialog.show();
    }

    // ---------- wire format ----------

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.arg0 = b.getInt(); m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("bad length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------- keys ----------

    private static KeyPair keys(Context c) throws IOException {
        if (keys != null) return keys;
        File f = new File(c.getFilesDir(), "adbkey.pk8");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (f.exists()) {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(f.toPath())));
                // Conscrypt's loaded key is a plain RSAPrivateKey (no CRT parts); the public exponent is the
                // generator's default, 65537 (F4), so the public half is the modulus + F4.
                BigInteger mod = ((java.security.interfaces.RSAPrivateKey) priv).getModulus();
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(mod, java.security.spec.RSAKeyGenParameterSpec.F4));
                return keys = new KeyPair(pub, priv);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new java.security.spec.RSAKeyGenParameterSpec(2048, java.security.spec.RSAKeyGenParameterSpec.F4));
            KeyPair kp = g.generateKeyPair();
            Files.write(f.toPath(), kp.getPrivate().getEncoded());
            return keys = kp;
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            throw new IOException(e);
        }
    }

    private static byte[] sign(PrivateKey k, byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(k);
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** adbd's public key format: Android's RSAPublicKey struct (2048-bit, little-endian words), base64, + name. */
    private static byte[] publicKey(RSAPublicKey k) {
        int words = 64;
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Home@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

}
