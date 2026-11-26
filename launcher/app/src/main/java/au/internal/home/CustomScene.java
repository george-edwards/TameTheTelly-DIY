// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Optional household-supplied painted bedtime scene loaded from filesDir/custom_scene/ (or seeded from
 * APK assets/custom_scene/ when built with a private household overlay). Keeping the artwork in filesDir
 * lets generic in-app self-updates preserve the household's custom scene without bundling private art in
 * public releases.
 *
 * Expected files: scene.properties (name=..., optional tag=...), sky.webp, riding.webp, sleep.webp.
 */
final class CustomScene extends PaintedScene {
    private static final String TAG = "HomeCustomScene";
    private static final String DIR = "custom_scene";
    private static final String PROPS = "scene.properties";
    private static final String[] FILES = {PROPS, "sky.webp", "riding.webp", "sleep.webp"};

    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String plaqueTag;

    CustomScene(Context c, Mode mode) {
        super(mode, art(HomeConfig.SCENE_CUSTOM, () -> loadArt(c)));
        plaqueTag = loadProps(c).getProperty("tag", "").trim();
        namePaint.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setTextSize(30);
    }

    private static File dir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), DIR);
    }

    /** True if a custom scene is installed in filesDir or bundled in APK assets. */
    static boolean isAvailable(Context c) {
        if (new File(dir(c), PROPS).isFile()) return true;
        try (InputStream ignored = c.getAssets().open(DIR + "/" + PROPS)) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String displayName(Context c) {
        String n = loadProps(c).getProperty("name", "").trim();
        return n.isEmpty() ? "Custom scene" : n;
    }

    /**
     * Copy bundled assets/custom_scene/* into filesDir/custom_scene/ so a later generic self-update
     * (which has no custom_scene assets) preserves the scene on the TV.
     */
    static void seedIfBundled(Context c) {
        try (InputStream ignored = c.getAssets().open(DIR + "/" + PROPS)) {
            // Bundled custom scene exists; mirror all files to filesDir.
        } catch (IOException | RuntimeException e) {
            return;
        }
        File dstDir = dir(c);
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) return;
        byte[] buf = new byte[16 * 1024];
        for (String f : FILES) {
            try (InputStream in = c.getAssets().open(DIR + "/" + f);
                 FileOutputStream out = new FileOutputStream(new File(dstDir, f))) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException e) {
                Log.w(TAG, "seed " + f + " failed: " + e.getMessage());
            }
        }
    }

    private static Properties loadProps(Context c) {
        Properties props = new Properties();
        File f = new File(dir(c), PROPS);
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                props.load(in);
                return props;
            } catch (IOException ignored) { }
        }
        try (InputStream in = c.getAssets().open(DIR + "/" + PROPS)) {
            props.load(in);
        } catch (IOException | RuntimeException ignored) { }
        return props;
    }

    private static Bitmap loadImage(Context c, String fileName, boolean opaque) {
        File f = new File(dir(c), fileName);
        if (f.isFile()) {
            Bitmap bm = decodeFile(f, opaque);
            if (bm != null) return bm;
        }
        try {
            return decodeStream(c.getAssets().open(DIR + "/" + fileName), opaque);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "load " + fileName + " failed: " + e.getMessage());
            return null;
        }
    }

    private static Art loadArt(Context c) {
        return new Art(HomeConfig.SCENE_CUSTOM,
                loadImage(c, "sky.webp", true),
                loadImage(c, "riding.webp", false),
                loadImage(c, "sleep.webp", false));
    }

    private static final float[] SLEEP = {1798, 895, 150};

    @Override int barColour(float p) { return Sky.lerpColor(0xFFCDC2DC, 0xFF777198, ramp(p, .62f, 1)); }
    @Override int tilePanel() { return 0xC453486D; }   // quiet plum
    @Override int washColour() { return 0xFF343052; }
    @Override float washAmount() { return .55f; }

    @Override void homeGlow(Canvas c, float amount) {
        glow(c, 1840, 760, 95, alpha(0xFFFFD3A0, .25f * amount));
        glow(c, 1798, 855, 120, alpha(0xFFFFE3C2, .20f * amount));
    }

    @Override void drawRider(Canvas c, float x, float y, float a) {
        glow(c, x, y - 16, 80, alpha(0xFFFFDCC8, .14f * a));
        float width = Math.min(165, 118f * art.riding.getWidth() / art.riding.getHeight());
        sprite(c, art.riding, x, y, width, a);
    }

    @Override float[] sleepAt() { return SLEEP; }

    /** Optional stitched name plaque below the nest, clear of the video window. */
    @Override void decorate(Canvas c, float night) {
        if (plaqueTag.isEmpty()) return;
        line.setStrokeWidth(2);
        line.setColor(Sky.lerpColor(0xFFC7A4BC, 0xFF82758F, night));
        c.drawLine(1744, 891, 1744, 914, line);
        c.drawLine(1852, 891, 1852, 914, line);
        rect.set(1719, 907, 1877, 957);
        paint.setColor(Sky.lerpColor(0xFFF5E4D1, 0xFFC2AEC6, night));
        c.drawRoundRect(rect, 18, 18, paint);
        line.setColor(Sky.lerpColor(0xFFD6B9C6, 0xFF96849F, night));
        line.setStrokeWidth(1.5f);
        c.drawRoundRect(rect, 18, 18, line);
        for (int x = 1734; x <= 1862; x += 128) {
            c.drawLine(x - 2, 929, x + 2, 935, line);
            c.drawLine(x + 2, 929, x - 2, 935, line);
        }
        namePaint.setColor(Sky.lerpColor(0xFF78617F, 0xFF574762, night));
        drawCenteredText(c, plaqueTag, 1798, 932, namePaint);
    }

    @Override void drawMinutes(Canvas c, float night) {
        number(c, 1774, 708, 7, Sky.lerpColor(0xFF78617F, 0xFF574762, night));
    }
}
