// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A FrameLayout that is always 16:9, sized from its width. Clips children to its rounded background. */
public class AspectFrameLayout extends FrameLayout {
    public AspectFrameLayout(Context c) { this(c, null); }
    public AspectFrameLayout(Context c, AttributeSet a) {
        super(c, a);
        setClipToOutline(true);
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = width * 9 / 16;
        if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED && height > MeasureSpec.getSize(h)) {
            height = MeasureSpec.getSize(h);           // too tall for the space: size from height instead
            width = height * 16 / 9;
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
