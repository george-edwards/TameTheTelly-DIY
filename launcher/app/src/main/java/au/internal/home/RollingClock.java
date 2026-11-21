// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.Calendar;

/** Big clock whose changing digits roll up into place, odometer-style. Tabular figures so nothing shifts sideways. */
public class RollingClock extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final android.graphics.Rect bounds = new android.graphics.Rect();
    private String cur = "", prev = "";
    private float t = 1f;          // roll progress, 1 = settled
    private ValueAnimator anim;
    private boolean frozen;        // renders set the time explicitly

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { update(true); }
    };

    public RollingClock(Context c, AttributeSet a) {
        super(c, a);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 60, getResources().getDisplayMetrics()));
        paint.setTypeface(getResources().getFont(R.font.inter_extralight));
        paint.setColor(getResources().getColor(R.color.text, null));
        paint.setLetterSpacing(-0.02f);
        paint.setFontFeatureSettings("tnum");
        update(false);
    }

    /** The theme's typeface, colour and size. */
    void setLook(android.graphics.Typeface face, int colour, float sp) {
        paint.setTypeface(face);
        paint.setColor(colour);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        requestLayout(); invalidate();
    }

    /** For renders: show `from` rolling to `to`, frozen at progress p (0..1). */
    void freeze(String from, String to, float p) {
        frozen = true;
        if (anim != null) anim.cancel();
        prev = from; cur = to; t = p;
        requestLayout(); invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        update(false);
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        getContext().unregisterReceiver(tick);
        super.onDetachedFromWindow();
    }

    private void update(boolean animate) {
        if (frozen) return;
        String s = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", Calendar.getInstance()).toString();
        if (s.equals(cur)) return;
        prev = cur; cur = s;
        if (paint.measureText(prev) != paint.measureText(cur)) requestLayout();
        if (!animate || prev.isEmpty() || !isShown()) { t = 1f; invalidate(); return; }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f).setDuration(650);
        anim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onMeasure(int w, int h) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        setMeasuredDimension((int) Math.ceil(paint.measureText(cur)) + 2, (int) Math.ceil(fm.descent - fm.ascent));
    }

    @Override protected void onDraw(Canvas c) {
        float base = -paint.getFontMetrics().ascent;
        float dist = paint.getTextSize() * 0.9f;
        if (t < 1f) {
            // Odometer window: digits roll in and out at the edges of the figures themselves.
            paint.getTextBounds("0", 0, 1, bounds);
            float pad = paint.getTextSize() * 0.06f;
            c.save();
            c.clipRect(0, base + bounds.top - pad, getWidth(), base + bounds.bottom + pad);
        }
        boolean sameShape = prev.length() == cur.length();
        for (int i = 0; i < cur.length(); i++) {
            float x = paint.measureText(cur, 0, i);
            boolean changed = t < 1f && (!sameShape || prev.charAt(i) != cur.charAt(i));
            if (!changed) { paint.setAlpha(255); c.drawText(cur, i, i + 1, x, base, paint); continue; }
            paint.setAlpha((int) (255 * t));
            c.drawText(cur, i, i + 1, x, base + dist * (1f - t), paint);
        }
        if (t < 1f) {
            for (int i = 0; i < prev.length(); i++) {
                if (sameShape && prev.charAt(i) == cur.charAt(i)) continue;
                paint.setAlpha((int) (255 * (1f - t)));
                c.drawText(prev, i, i + 1, paint.measureText(prev, 0, i), base - dist * t, paint);
            }
            c.restore();
        }
        paint.setAlpha(255);
    }
}
