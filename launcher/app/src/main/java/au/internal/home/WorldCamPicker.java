// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Longitude-sorted World Cam picker with a live day/night terminator world plot at the top
 * and matching daylight/twilight/night row tinting down the list.
 */
final class WorldCamPicker {

    interface OnPick {
        void onPick(WorldCams.Stream stream);
    }

    static void show(Activity a, String title, List<WorldCams.Stream> rawPool,
                     WorldCams worldCams, WorldCams.Stream current, OnPick onPick) {
        if (rawPool == null || rawPool.isEmpty()) return;
        List<WorldCams.Stream> pool = WorldCams.sortedByLongitude(rawPool);
        double[] sub = Sun.subsolar(System.currentTimeMillis());
        final double decl = sub[0];
        final double subLon = sub[1];

        int initialIdx = 0;
        if (current != null) {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).key.equals(current.key)) { initialIdx = i; break; }
            }
        }

        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF00B1120);
        bg.setCornerRadius(14f * d);
        bg.setStroke(Math.max(1, Math.round(1f * d)), 0x33FFFFFF);
        root.setBackground(bg);

        // Header row
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(d, 18), dp(d, 14), dp(d, 18), dp(d, 8));

        TextView titleTv = new TextView(a);
        titleTv.setText(title);
        titleTv.setTextColor(0xFFF8FAFC);
        titleTv.setTextSize(18f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subTv = new TextView(a);
        subTv.setTextColor(0xB3CBD5E1);
        subTv.setTextSize(12f);
        Runnable updateHeader = () -> {
            int online = WorldCams.onlineCount(pool);
            subTv.setText("West → East  ·  " + online + "/" + pool.size() + " online");
        };
        updateHeader.run();
        header.addView(subTv);
        root.addView(header);

        // Live Day/Night Terminator World Map
        DaylightMapView mapView = new DaylightMapView(a, pool, decl, subLon);
        mapView.setSelectedIndex(initialIdx);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(d, 122));
        mlp.setMargins(dp(d, 16), 0, dp(d, 16), dp(d, 10));
        root.addView(mapView, mlp);

        // Scrollable stream list with per-row live daylight/twilight/night tinting
        ListView lv = new ListView(a);
        lv.setDivider(new ColorDrawable(0x1FFFFFFF));
        lv.setDividerHeight(Math.max(1, Math.round(0.75f * d)));
        lv.setVerticalScrollBarEnabled(true);
        lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        StateListDrawable selector = new StateListDrawable();
        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0x44FFFFFF);
        focusBg.setStroke(Math.max(1, Math.round(1.5f * d)), 0xCCFFD166);
        selector.addState(new int[]{android.R.attr.state_focused}, focusBg);
        selector.addState(new int[]{android.R.attr.state_selected}, focusBg);
        selector.addState(new int[]{android.R.attr.state_pressed}, focusBg);
        selector.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        lv.setSelector(selector);
        lv.setDrawSelectorOnTop(true);

        // Precompute static row tints & longitude/daylight labels once for the dialog's lifetime
        final int[] rowTints = new int[pool.size()];
        final String[] rightLabels = new String[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            WorldCams.Stream s = pool.get(i);
            if (s.hasCoords) {
                double alt = Sun.altitude(s.lat, s.lon, decl, subLon);
                rowTints[i] = rowTintForAltitude(alt);
                String icon = alt > 6.0 ? "☀ " : (alt > -12.0 ? "🌅 " : "🌙 ");
                int lonInt = (int) Math.round(Math.abs(s.lon));
                String hemi = s.lon >= 0 ? "°E" : "°W";
                rightLabels[i] = icon + lonInt + hemi;
            } else {
                rowTints[i] = 0x281E293B;
                rightLabels[i] = "Space".equalsIgnoreCase(s.category) || s.name.startsWith("Space")
                        ? "🛰 Orbit" : "⛵ Roving";
            }
        }

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return pool.size(); }
            @Override public Object getItem(int pos) { return pool.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                LinearLayout row;
                TextView left, right;
                if (convertView == null) {
                    row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(d, 18), dp(d, 9), dp(d, 18), dp(d, 9));

                    left = new TextView(a);
                    left.setTextColor(0xFFF1F5F9);
                    left.setTextSize(14.5f);
                    left.setSingleLine(true);
                    left.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    right = new TextView(a);
                    right.setTextColor(0xCCE2E8F0);
                    right.setTextSize(12.5f);
                    right.setSingleLine(true);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.leftMargin = dp(d, 12);
                    row.addView(right, rlp);
                    row.setTag(new TextView[]{left, right});
                } else {
                    row = (LinearLayout) convertView;
                    TextView[] views = (TextView[]) row.getTag();
                    left = views[0];
                    right = views[1];
                }

                WorldCams.Stream s = pool.get(pos);
                Boolean st = WorldCams.onlineStatus(s);
                String dot = Boolean.TRUE.equals(st) ? "🟢 " : (Boolean.FALSE.equals(st) ? "○ " : "   ");
                String heart = worldCams.isHearted(s) ? "♥ " : "";
                String curMark = (current != null && current.key.equals(s.key)) ? "✓ " : "";
                left.setText(dot + curMark + heart + s.displayName());
                left.setAlpha(Boolean.FALSE.equals(st) ? 0.55f : 1f);
                row.setBackgroundColor(rowTints[pos]);
                right.setText(rightLabels[pos]);
                return row;
            }
        };
        lv.setAdapter(adapter);
        lv.setSelection(initialIdx);
        lv.setOnKeyListener((v, keyCode, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN || pool.size() < 2) return false;
            int pos = lv.getSelectedItemPosition();
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && pos == 0) {
                lv.setSelection(pool.size() - 1);
                mapView.setSelectedIndex(pool.size() - 1);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && pos == pool.size() - 1) {
                lv.setSelection(0);
                mapView.setSelectedIndex(0);
                return true;
            }
            return false;
        });
        lv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                mapView.setSelectedIndex(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(lv, llp);

        AlertDialog dialog = new AlertDialog.Builder(a).setView(root).create();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            dialog.dismiss();
            if (onPick != null) onPick.onPick(pool.get(pos));
        });

        final Runnable refreshUi = () -> {
            if (!dialog.isShowing()) return;
            updateHeader.run();
            adapter.notifyDataSetChanged();
            mapView.invalidate();
        };
        final Runnable listener = () -> {
            lv.removeCallbacks(refreshUi);
            lv.postDelayed(refreshUi, 250L);
        };
        WorldCams.setStatusListener(listener);
        dialog.setOnDismissListener(di -> {
            lv.removeCallbacks(refreshUi);
            WorldCams.clearStatusListener(listener);
        });

        MainActivity.showDialog(dialog);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.74f), Math.round(dm.heightPixels * 0.86f));
        }
        lv.requestFocus();
    }

    private static int rowTintForAltitude(double altDeg) {
        if (altDeg > 12.0) return 0x30387CC4;      // bright day
        if (altDeg > 4.0) return 0x2A5B8FB9;       // low sun / morning / afternoon
        if (altDeg > -4.0) return 0x3AD97736;      // golden hour / sunrise / sunset
        if (altDeg > -12.0) return 0x325A3E85;     // twilight
        return 0x48070D1F;                         // night
    }

    private static int dp(float d, int v) {
        return Math.round(v * d);
    }

    /**
     * Equirectangular live Day/Night Terminator plot (West -180° to East +180°) with solar
     * position, glowing camera pins, and a live gold crosshair tracking the focused list row.
     */
    private static final class DaylightMapView extends View {
        private static final String[] AXIS_LABELS = {"180°W", "90°W", "0°", "90°E", "180°E"};
        private static final int[] AXIS_LONS = {-180, -90, 0, 90, 180};

        private final List<WorldCams.Stream> streams;
        private final double decl;
        private final double subLon;
        private final Bitmap bgBitmap;
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint landFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint landStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path landPath = new Path();
        private final Rect srcRect;
        private final RectF dstRect = new RectF();
        private int selectedIdx = -1;
        private int landW, landH;

        /** Simplified continent/island outlines in (lon, lat) pairs for the equirectangular daylight plot. */
        private static final float[][] LAND_POLYS = {
                // North & Central America
                {-168, 65, -158, 70, -140, 70, -125, 70, -110, 68, -95, 68, -80, 63, -64, 59, -55, 52,
                        -60, 46, -67, 45, -70, 42, -76, 35, -81, 30, -80, 25, -83, 29, -90, 29, -97, 26,
                        -97, 19, -90, 21, -87, 21, -88, 16, -83, 10, -78, 8, -80, 7, -85, 10, -92, 15,
                        -105, 19, -110, 24, -117, 32, -123, 38, -125, 48, -135, 57, -150, 60, -162, 55, -166, 60},
                // Greenland
                {-52, 60, -42, 60, -35, 66, -22, 70, -18, 78, -32, 82, -55, 81, -70, 76, -56, 68},
                // South America
                {-78, 9, -72, 12, -62, 10, -50, 1, -35, -6, -38, -13, -41, -22, -48, -26, -54, -34,
                        -62, -39, -66, -46, -68, -55, -73, -53, -74, -42, -71, -30, -70, -18, -81, -5, -79, 2},
                // Eurasia (Europe + Asia)
                {-9, 37, -9, 43, -2, 43, -5, 48, 2, 51, 9, 54, 12, 56, 5, 60, 12, 66, 20, 70, 31, 70,
                        42, 67, 60, 70, 75, 73, 105, 76, 135, 72, 170, 66, 178, 65, 162, 56, 156, 51,
                        142, 54, 135, 44, 129, 35, 126, 38, 122, 37, 121, 31, 120, 24, 110, 20, 108, 12,
                        103, 2, 100, 14, 95, 16, 88, 22, 80, 10, 77, 8, 72, 20, 66, 25, 56, 27, 48, 30,
                        51, 24, 59, 22, 53, 16, 44, 13, 35, 29, 36, 36, 27, 37, 23, 38, 18, 40, 13, 45,
                        8, 44, 3, 42, -5, 36},
                // UK & Ireland
                {-6, 50, 1, 51, -1, 55, -3, 58, -6, 57, -5, 53},
                {-10, 52, -6, 52, -6, 55, -9, 55},
                // Africa
                {-17, 15, -13, 28, -6, 36, 10, 37, 11, 33, 25, 32, 33, 31, 37, 22, 43, 12, 51, 11,
                        41, -2, 40, -15, 35, -25, 28, -33, 18, -34, 15, -26, 12, -16, 9, -2, 9, 4,
                        2, 6, -8, 5, -16, 11},
                // Madagascar
                {44, -25, 47, -25, 50, -15, 49, -12, 45, -16},
                // Japan
                {130, 31, 136, 34, 141, 36, 141, 41, 145, 44, 141, 45, 139, 39, 135, 35, 130, 33},
                // Philippines
                {120, 18, 123, 15, 126, 7, 122, 7, 120, 14},
                // Indonesia (Sumatra/Java/Borneo)
                {95, 5, 105, -6, 115, -8, 106, -7, 98, 1},
                {109, 2, 118, 5, 118, -3, 111, -3},
                // New Guinea
                {131, -2, 141, -3, 150, -10, 141, -8, 134, -4},
                // Australia
                {114, -22, 122, -18, 130, -12, 136, -12, 136, -15, 141, -17, 143, -11, 146, -18,
                        153, -27, 150, -37, 141, -38, 136, -35, 131, -32, 122, -34, 115, -34, 113, -26},
                // Tasmania
                {145, -41, 148, -41, 147, -43.5f, 145.5f, -43},
                // New Zealand
                {173, -35, 178, -38, 175, -41, 173, -41, 170, -46, 167, -46, 172, -41}
        };

        DaylightMapView(Context c, List<WorldCams.Stream> streams, double decl, double subLon) {
            super(c);
            this.streams = streams;
            this.decl = decl;
            this.subLon = subLon;
            this.bgBitmap = buildTerminatorBitmap(decl, subLon);
            this.srcRect = new Rect(0, 0, bgBitmap.getWidth(), bgBitmap.getHeight());

            float d = c.getResources().getDisplayMetrics().density;
            gridPaint.setColor(0x26FFFFFF);
            gridPaint.setStrokeWidth(Math.max(1f, 0.75f * d));

            landFillPaint.setStyle(Paint.Style.FILL);
            landFillPaint.setColor(0x1CFFFFFF);
            landStrokePaint.setStyle(Paint.Style.STROKE);
            landStrokePaint.setColor(0x52FFFFFF);
            landStrokePaint.setStrokeWidth(Math.max(1f, 0.85f * d));
            landStrokePaint.setStrokeJoin(Paint.Join.ROUND);

            textPaint.setColor(0xAAFFFFFF);
            textPaint.setTextSize(9.5f * d);
            textPaint.setTextAlign(Paint.Align.CENTER);

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(0xFFFFD166);
            ringPaint.setStrokeWidth(2f * d);

            crosshairPaint.setColor(0x88FFD166);
            crosshairPaint.setStrokeWidth(1.2f * d);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(0x44FFFFFF);
            borderPaint.setStrokeWidth(1f * d);

            sunPaint.setStyle(Paint.Style.FILL);
        }

        void setSelectedIndex(int idx) {
            if (selectedIdx != idx) {
                selectedIdx = idx;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            float labelH = 14f * d;
            float mapH = h - labelH;
            dstRect.set(0, 0, w, mapH);

            canvas.drawBitmap(bgBitmap, srcRect, dstRect, bmpPaint);

            // Subtle world coastline / continent silhouettes (cached per size)
            if (w != landW || h != landH) {
                landW = w;
                landH = h;
                landPath.rewind();
                for (float[] poly : LAND_POLYS) {
                    if (poly.length < 4) continue;
                    landPath.moveTo(lonToX(poly[0], w), latToY(poly[1], mapH));
                    for (int i = 2; i < poly.length; i += 2) {
                        landPath.lineTo(lonToX(poly[i], w), latToY(poly[i + 1], mapH));
                    }
                    landPath.close();
                }
            }
            canvas.drawPath(landPath, landFillPaint);
            canvas.drawPath(landPath, landStrokePaint);

            // Subtle latitude/longitude gridlines
            canvas.drawLine(0, mapH * 0.5f, w, mapH * 0.5f, gridPaint); // Equator
            for (int lon = -120; lon <= 120; lon += 60) {
                float x = lonToX(lon, w);
                canvas.drawLine(x, 0, x, mapH, gridPaint);
            }

            // Bottom longitude axis labels
            float textY = h - 2.5f * d;
            for (int i = 0; i < AXIS_LABELS.length; i++) {
                float x = lonToX(AXIS_LONS[i], w);
                if (i == 0) textPaint.setTextAlign(Paint.Align.LEFT);
                else if (i == AXIS_LABELS.length - 1) textPaint.setTextAlign(Paint.Align.RIGHT);
                else textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(AXIS_LABELS[i], x, textY, textPaint);
            }

            // Subsolar point (glowing sun)
            float sx = lonToX(subLon, w);
            float sy = latToY(decl, mapH);
            sunPaint.setColor(0x44FFE066);
            canvas.drawCircle(sx, sy, 10f * d, sunPaint);
            sunPaint.setColor(0xFFFFE57F);
            canvas.drawCircle(sx, sy, 4.2f * d, sunPaint);

            // Camera pins
            for (int i = 0; i < streams.size(); i++) {
                WorldCams.Stream s = streams.get(i);
                if (!s.hasCoords) continue;
                float cx = lonToX(s.lon, w);
                float cy = latToY(s.lat, mapH);
                Boolean st = WorldCams.onlineStatus(s);
                dotPaint.setColor(Boolean.TRUE.equals(st) ? 0xFF4ADE80
                        : (Boolean.FALSE.equals(st) ? 0x8864748B : 0xDDE2E8F0));
                canvas.drawCircle(cx, cy, 2.6f * d, dotPaint);
            }

            // Highlighted focused stream crosshair + gold ring
            if (selectedIdx >= 0 && selectedIdx < streams.size()) {
                WorldCams.Stream sel = streams.get(selectedIdx);
                if (sel.hasCoords) {
                    float cx = lonToX(sel.lon, w);
                    float cy = latToY(sel.lat, mapH);
                    canvas.drawLine(cx, 0, cx, mapH, crosshairPaint);
                    dotPaint.setColor(0xFFFFD166);
                    canvas.drawCircle(cx, cy, 3.6f * d, dotPaint);
                    canvas.drawCircle(cx, cy, 7.2f * d, ringPaint);
                }
            }

            // Crisp border around map
            canvas.drawRect(0.5f, 0.5f, w - 0.5f, mapH - 0.5f, borderPaint);
        }

        private static float lonToX(double lon, float w) {
            return (float) ((lon + 180.0) / 360.0 * w);
        }

        private static float latToY(double lat, float h) {
            double clamped = Math.max(-80.0, Math.min(80.0, lat));
            return (float) ((80.0 - clamped) / 160.0 * h);
        }

        /** Render a smooth 180×80 equirectangular day/night terminator plot bitmap (<2ms). */
        private static Bitmap buildTerminatorBitmap(double declDeg, double subLonDeg) {
            final int bw = 180, bh = 80;
            int[] px = new int[bw * bh];
            double declRad = Math.toRadians(declDeg);
            double sinD = Math.sin(declRad), cosD = Math.cos(declRad);
            double[] cosDLon = new double[bw];
            for (int x = 0; x < bw; x++) {
                double lonDeg = -180.0 + (x + 0.5) * (360.0 / bw);
                cosDLon[x] = Math.cos(Math.toRadians(lonDeg - subLonDeg));
            }

            for (int y = 0; y < bh; y++) {
                double latDeg = 80.0 - (y + 0.5) * (160.0 / bh);
                double latRad = Math.toRadians(latDeg);
                double sinL = Math.sin(latRad), cosL = Math.cos(latRad);
                int rowOff = y * bw;
                for (int x = 0; x < bw; x++) {
                    double sinAlt = sinL * sinD + cosL * cosD * cosDLon[x];
                    if (sinAlt > 1.0) sinAlt = 1.0;
                    else if (sinAlt < -1.0) sinAlt = -1.0;
                    double altDeg = Math.toDegrees(Math.asin(sinAlt));
                    px[rowOff + x] = colorForAltitude(altDeg);
                }
            }
            return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
        }

        private static int colorForAltitude(double alt) {
            if (alt >= 12.0) {
                float t = (float) Math.min(1.0, (alt - 12.0) / 60.0);
                return Sky.lerpColor(0xFF3E7BB6, 0xFF7EC8E3, t);
            }
            if (alt >= 2.0) {
                float t = (float) ((alt - 2.0) / 10.0);
                return Sky.lerpColor(0xFFD98A4E, 0xFF3E7BB6, t);
            }
            if (alt >= -2.0) {
                // Bright golden horizon band right on the terminator curve
                float t = (float) ((alt + 2.0) / 4.0);
                return Sky.lerpColor(0xFF8C4A5E, 0xFFE6A15C, t);
            }
            if (alt >= -12.0) {
                float t = (float) ((alt + 12.0) / 10.0);
                return Sky.lerpColor(0xFF0E1730, 0xFF5A3B6E, t);
            }
            return 0xFF080E1E;
        }
    }
}
