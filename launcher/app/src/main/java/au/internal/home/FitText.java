// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * One line of text that shrinks (down to a minimum) to fit its width rather than being cut off: the greeting
 * ("…Smith family") and the weather. Used instead of TextView's autosize, whose range can't be changed reliably
 * once the view exists, and which needs a fixed width (the weather wraps its text when Home is wide).
 */
public class FitText extends TextView {
    private float min, max;
    private final TextPaint probe = new TextPaint();

    public FitText(Context c, AttributeSet a) {
        super(c, a);
        setSingleLine(true);
        max = getTextSize();
        min = max * 0.75f;
    }

    /** The largest and smallest size, in sp. */
    void setRange(float minSp, float maxSp) {
        min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, getResources().getDisplayMetrics());
        max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, getResources().getDisplayMetrics());
        requestLayout();
    }

    /** TextView skips measuring again for new text when its size is fixed; this needs to, to fit it. */
    @Override protected void onTextChanged(CharSequence t, int start, int before, int after) {
        super.onTextChanged(t, start, before, after);
        requestLayout();
    }

    @Override protected void onMeasure(int ws, int hs) {
        float size = max;
        if (MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED && getText().length() > 0) {
            probe.set(getPaint());
            probe.setTextSize(max);
            float need = probe.measureText(getText(), 0, getText().length());
            float room = MeasureSpec.getSize(ws) - getCompoundPaddingLeft() - getCompoundPaddingRight() - 1;
            if (need > room) size = Math.max(min, max * room / need);
        }
        // Only when it changes, so a measure never asks for another one.
        if (Math.abs(size - getTextSize()) > 0.25f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        super.onMeasure(ws, hs);
    }
}
