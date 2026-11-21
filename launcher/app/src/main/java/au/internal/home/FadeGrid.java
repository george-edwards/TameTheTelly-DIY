// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * The app grid. It draws into its padding (clipToPadding=false) so a raised tile's glow isn't cut
 * off at the first/last row, but its fading edges run from the view's outer edges rather than from
 * inside the padding, as ListView does. So tiles scrolled into the padding are faded out instead of
 * showing sharp under the header.
 */
public final class FadeGrid extends RecyclerView {
    public FadeGrid(Context c, AttributeSet a) { super(c, a); }

    @Override protected boolean isPaddingOffsetRequired() { return true; }
    @Override protected int getTopPaddingOffset() { return -getPaddingTop(); }
    @Override protected int getBottomPaddingOffset() { return getPaddingBottom(); }
    @Override protected int getLeftPaddingOffset() { return -getPaddingLeft(); }
    @Override protected int getRightPaddingOffset() { return getPaddingRight(); }
}
