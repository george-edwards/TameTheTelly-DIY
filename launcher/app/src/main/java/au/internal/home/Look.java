// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home's themes (Config > Theme) and its two layouts: apps beside the cameras, or apps across the whole screen when
 * cameras are off. A theme is a time-of-day sky (Sky), text and surface colours, how focused tiles glow, and the
 * clock's typeface. The bedtime scenes and the scene tile are the same in every theme.
 */
final class Look {
    final String id, name;
    /** Sky keys, as in Sky: {gradient top-left, bottom-right, glow colour, glow alpha}, midnight round to midnight. */
    final int[][] sky;
    /** Ribbons of light across the top (empty for none), how bright, and whether they're brightest at night
     *  (Aurora) or by day (Forest's shafts of sun). */
    final int[] ribbons;
    final float ribbonStrength;
    final boolean ribbonsAtNight;
    final int overcast;
    final int bg, text, soft, muted, faint, divider, tile, pill, pillText, ring, idle;
    /** How much of the app's colour tints the focus ring (0..1). */
    final float ringAccent;
    /** Light under a focused tile: strength 0..1, and a fixed colour, or 0 for each app's own colour. */
    final float glow;
    final int glowColour;
    final int clockFont, greetingFont;
    final float clockSp, greetingSp;
    /** Light background: dark ink, and the weather icons are inked to match. */
    final boolean light;

    private Look(Builder b) {
        id = b.id; name = b.name; sky = b.sky; ribbons = b.ribbons; ribbonStrength = b.ribbonStrength; ribbonsAtNight = b.ribbonsAtNight; overcast = b.overcast;
        bg = b.bg; text = b.text; soft = b.soft; muted = b.muted; faint = b.faint; divider = b.divider; tile = b.tile;
        pill = b.pill; pillText = b.pillText; ring = b.ring; idle = b.idle; ringAccent = b.ringAccent;
        glow = b.glow; glowColour = b.glowColour; clockFont = b.clockFont; greetingFont = b.greetingFont;
        clockSp = b.clockSp; greetingSp = b.greetingSp; light = b.light;
    }

    // ---------- the themes ----------

    /** George's original: navy dusk, a sun glow that crosses the sky through the day, each app's own colour. */
    static final Look TWILIGHT = new Builder("twilight", "Twilight")
            .sky(new int[][]{
                    {0xFF0D1324, 0xFF07090F, 0xFF4A5FA8, 40},   // midnight, moonlight
                    {0xFF0D1324, 0xFF07090F, 0xFF4A5FA8, 30},   // late night
                    {0xFF141523, 0xFF08090F, 0xFF8A6A9A, 12},   // first light (nautical dawn), glow fades as it moves
                    {0xFF2A2236, 0xFF0C1018, 0xFFFF9E6B, 56},   // sunrise
                    {0xFF182436, 0xFF0B0E13, 0xFF7FB7FF, 40},   // morning
                    {0xFF19283C, 0xFF0B0F15, 0xFF8CC4FF, 44},   // solar noon
                    {0xFF262236, 0xFF0D0E15, 0xFFFFB070, 46},   // late afternoon
                    {0xFF2D1A2E, 0xFF0C0B14, 0xFFFF7A6B, 58},   // sunset
                    {0xFF24160F, 0xFF0B0806, 0xFFFF9A4A, 50},   // dark (nautical dusk): bedtime amber
                    {0xFF16101A, 0xFF08070B, 0xFFB9724A, 30},   // late evening
                    {0xFF0D1324, 0xFF07090F, 0xFF4A5FA8, 40}})  // midnight
            .ink(0xFF0B0E13, 0xFFECEFF4, 0xFFB7C0CC, 0xFF8D97A6, 0xFF5E6877, 0x1FFFFFFF)
            .surfaces(0xFF1A2029, 0xFF262F3B, 0xFFECEFF4, 0xFFFFFFFF, 0x14FFFFFF, 0.14f)
            .build();

    /** Warm paper and ink, a serif clock and greeting, soft shadows instead of coloured light, a burnt-sienna focus
     *  ring; the sun is a warm wash, and at night it settles into lamplight. */
    static final Look PAPER = new Builder("paper", "Paper")
            .sky(new int[][]{
                    {0xFFE3D9C8, 0xFFD2C4AE, 0xFFFFC27A, 44},   // midnight: lamplight
                    {0xFFE3D9C8, 0xFFD2C4AE, 0xFFFFC27A, 36},
                    {0xFFE9E0D2, 0xFFDACFBE, 0xFFF0B3A0, 34},   // first light
                    {0xFFF4E7DA, 0xFFE6D9C9, 0xFFFFAA80, 120},  // sunrise: peach
                    {0xFFF6F1E7, 0xFFE9E2D4, 0xFFFFD9A6, 96},   // morning
                    {0xFFF8F4EC, 0xFFECE6DA, 0xFFFFE6B8, 84},   // solar noon
                    {0xFFF6EEE1, 0xFFE8DECD, 0xFFFFC688, 104},  // late afternoon
                    {0xFFF3E3D6, 0xFFE2D1C0, 0xFFFF9C78, 124},  // sunset: rose
                    {0xFFEADBC6, 0xFFD8C6AD, 0xFFFFB066, 92},   // dark: lamplight
                    {0xFFE5D9C6, 0xFFD4C5AF, 0xFFFFBC78, 60},   // late evening
                    {0xFFE3D9C8, 0xFFD2C4AE, 0xFFFFC27A, 44}})
            .overcast(0xFFC9C6C0)
            .ink(0xFFE9E2D4, 0xFF1E1A15, 0xFF3F382F, 0xFF6A6255, 0xFF978C7C, 0x26000000)
            .surfaces(0xFFFFFDF8, 0xFF1E1A15, 0xFFF6F1E8, 0xFFB4532A, 0x1A000000, 0f)   // focus: a burnt-sienna ring
            .glow(0.42f, 0xFF4A3A28)
            .fonts(R.font.instrument_serif, 82, R.font.instrument_serif, 25)
            .light()
            .build();

    /** Near-black polar night with ribbons of green, cyan and violet light: bright at night, faint by day. */
    static final Look AURORA = new Builder("aurora", "Aurora")
            .sky(new int[][]{
                    {0xFF060A12, 0xFF020306, 0xFF2E6B8F, 22},   // midnight
                    {0xFF060A12, 0xFF020306, 0xFF2E6B8F, 18},
                    {0xFF0B0C16, 0xFF030407, 0xFF8A6AB0, 18},   // first light
                    {0xFF1A1426, 0xFF06070C, 0xFFFF8FA3, 42},   // sunrise
                    {0xFF0C1824, 0xFF04080D, 0xFF5FD3C8, 30},   // morning
                    {0xFF0D1B28, 0xFF050A0F, 0xFF6FE0D4, 30},   // solar noon
                    {0xFF141826, 0xFF06080D, 0xFFB69CFF, 30},   // late afternoon
                    {0xFF1C1228, 0xFF07060D, 0xFFFF7FA8, 46},   // sunset
                    {0xFF0A0E1C, 0xFF030409, 0xFF57E3B0, 30},   // dark
                    {0xFF070B16, 0xFF020306, 0xFF3FD6A0, 26},   // late evening
                    {0xFF060A12, 0xFF020306, 0xFF2E6B8F, 22}})
            .ribbons(1.6f, true, 0xFF3DFFB0, 0xFF2EC8FF, 0xFFA66BFF)
            .ink(0xFF03050A, 0xFFE8F3F1, 0xFFB4C7C9, 0xFF7F9598, 0xFF4F6266, 0x1AFFFFFF)
            .surfaces(0xFF0F161B, 0xFF16222A, 0xFFE8F3F1, 0xFFFFFFFF, 0x14FFFFFF, 0.22f)
            .build();

    /** Deep evergreen, cream text, a serif clock and shafts of sun through the canopy by day; firelight at night. */
    static final Look FOREST = new Builder("forest", "Forest")
            .sky(new int[][]{
                    {0xFF0C1A16, 0xFF050B09, 0xFF7FA894, 22},   // midnight: green moonlight
                    {0xFF0C1A16, 0xFF050B09, 0xFF7FA894, 18},
                    {0xFF13201C, 0xFF060C0A, 0xFFB08A7A, 14},   // first light
                    {0xFF2A2A1E, 0xFF0A100C, 0xFFFFB27A, 54},   // sunrise
                    {0xFF15382A, 0xFF07150F, 0xFFE9D08A, 44},   // morning
                    {0xFF173D2D, 0xFF081711, 0xFFF2DC98, 48},   // solar noon
                    {0xFF213A24, 0xFF0A150E, 0xFFF0B86A, 52},   // late afternoon: gold
                    {0xFF2E2419, 0xFF0D0F0B, 0xFFFF9257, 58},   // sunset
                    {0xFF1C1A12, 0xFF080A07, 0xFFE89A4A, 44},   // dark: firelight
                    {0xFF111A15, 0xFF050907, 0xFFA8805A, 26},   // late evening
                    {0xFF0C1A16, 0xFF050B09, 0xFF7FA894, 22}})
            .ribbons(0.75f, false, 0xFFF3D27A, 0xFF8FD18A, 0xFFE9B35F)
            .ink(0xFF06100C, 0xFFF2ECDD, 0xFFCFC7B1, 0xFF9E9A86, 0xFF6B7366, 0x22F2ECDD)
            .surfaces(0xFF15241D, 0xFF1D3128, 0xFFF2ECDD, 0xFFF6EAD0, 0x18F2ECDD, 0.12f)
            .fonts(R.font.instrument_serif, 80, R.font.inter_medium, 18)
            .build();

    /** Quiet black and white: no colour but the apps' own, a thin clock, hairlines. */
    static final Look GRAPHITE = new Builder("graphite", "Graphite")
            .sky(new int[][]{
                    {0xFF0B0B0C, 0xFF000000, 0xFFFFFFFF, 6},
                    {0xFF0B0B0C, 0xFF000000, 0xFFFFFFFF, 5},
                    {0xFF0D0D0E, 0xFF010101, 0xFFFFFFFF, 6},
                    {0xFF131211, 0xFF020202, 0xFFFFE2C8, 12},
                    {0xFF121314, 0xFF030303, 0xFFFFFFFF, 11},
                    {0xFF141516, 0xFF030303, 0xFFFFFFFF, 12},
                    {0xFF131313, 0xFF030303, 0xFFFFF0E0, 11},
                    {0xFF141211, 0xFF020202, 0xFFFFDCC4, 12},
                    {0xFF0E0D0D, 0xFF010101, 0xFFFFE8D4, 8},
                    {0xFF0C0C0C, 0xFF000000, 0xFFFFFFFF, 6},
                    {0xFF0B0B0C, 0xFF000000, 0xFFFFFFFF, 6}})
            .overcast(0xFFFFFFFF)
            .ink(0xFF000000, 0xFFF4F4F4, 0xFFBFBFBF, 0xFF8C8C8C, 0xFF5C5C5C, 0x1AFFFFFF)
            .surfaces(0xFF141414, 0xFF1E1E1E, 0xFFF4F4F4, 0xFFFFFFFF, 0x12FFFFFF, 0f)
            .glow(0.22f, 0xFFFFFFFF)
            .fonts(R.font.inter_extralight, 66, R.font.inter_regular, 18)
            .build();

    static final Look[] ALL = {TWILIGHT, PAPER, AURORA, FOREST, GRAPHITE};

    static Look byId(String id) {
        for (Look l : ALL) if (l.id.equals(id)) return l;
        return TWILIGHT;
    }

    static String[] ids() { String[] s = new String[ALL.length]; for (int i = 0; i < s.length; i++) s[i] = ALL[i].id; return s; }
    static String[] names() { String[] s = new String[ALL.length]; for (int i = 0; i < s.length; i++) s[i] = ALL[i].name; return s; }

    // ---------- putting it on screen ----------

    /**
     * Colour Home's views (activity_main) and lay them out: `wide` when there are no cameras, so the apps and the
     * header run across the whole screen. Tiles and section headers are coloured as they're bound (MainActivity).
     */
    void apply(View root, Sky sky, boolean wide) {
        android.content.Context c = root.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        root.findViewById(R.id.root).setBackgroundColor(bg);
        sky.setLook(this);

        RollingClock clock = root.findViewById(R.id.clock);
        clock.setLook(c.getResources().getFont(clockFont), text, wide ? clockSp * 1.15f : clockSp);
        FitText greeting = root.findViewById(R.id.greeting);
        greeting.setTypeface(c.getResources().getFont(greetingFont));
        float gMax = wide ? greetingSp * 1.2f : greetingSp;
        greeting.setRange(gMax * 0.7f, gMax);
        greeting.getLayoutParams().height = Math.round(gMax * 1.35f * d);
        greeting.setTextColor(text);
        ((TextView) root.findViewById(R.id.date)).setTextColor(soft);
        FitText weather = root.findViewById(R.id.weather);
        weather.setTextColor(muted);
        weather.setCompoundDrawableTintList(light ? ColorStateList.valueOf(muted) : null);
        ((TextView) root.findViewById(R.id.binsText)).setTextColor(muted);
        root.findViewById(R.id.divider).setBackgroundColor(divider);
        TextView status = root.findViewById(R.id.status);
        status.setBackgroundTintList(ColorStateList.valueOf(pill));
        status.setTextColor(pillText);
        ((TextView) root.findViewById(R.id.cornerLabel)).setTextColor(text);
        for (int id : new int[]{R.id.modeBtn, R.id.timeBtn, R.id.remoteBtn, R.id.grownups})
            styleCornerIcon(root.findViewById(id), d);
        for (int id : new int[]{R.id.cam1, R.id.cam2}) {
            View cam = root.findViewById(id);
            cam.setForeground(ring(10 + 2, d, ring, idle));
            ((TextView) cam.findViewById(R.id.state)).setTextColor(muted);
        }

        // Layout: the weather and bins sit under the date beside the cameras, or at the far right when wide.
        View glance = root.findViewById(R.id.glance);
        ViewGroup header = root.findViewById(R.id.header), stack = root.findViewById(R.id.stack);
        ViewGroup to = wide ? header : stack;
        if (glance.getParent() != to) {
            ((ViewGroup) glance.getParent()).removeView(glance);
            if (wide) {
                header.addView(glance, new LinearLayout.LayoutParams(Math.round(340 * d), -2));
            } else stack.addView(glance, new LinearLayout.LayoutParams(-1, -2));
        }
        ((LinearLayout) glance).setGravity(wide ? android.view.Gravity.END : android.view.Gravity.START);
        weather.setRange(wide ? 12 : 10.5f, wide ? 15 : 13);
        weather.getLayoutParams().height = Math.round((wide ? 24 : 21) * d);
        ((TextView) root.findViewById(R.id.date)).setTextSize(TypedValue.COMPLEX_UNIT_SP, wide ? 15.5f : 13.5f);
        header.setPaddingRelative(Math.round((wide ? 52 : 30) * d), 0, Math.round((wide ? 44 : 24) * d), 0);
        View grid = root.findViewById(R.id.grid);
        grid.setPaddingRelative(Math.round((wide ? 44 : 22) * d), grid.getPaddingTop(), Math.round((wide ? 40 : 18) * d),
                grid.getPaddingBottom());
        // Room above the header for the Grown-ups button in the corner.
        View content = root.findViewById(R.id.content);
        content.setPadding(0, Math.round((wide ? 50 : 40) * d), 0, 0);
        View gu = root.findViewById(R.id.corner);
        android.widget.FrameLayout.LayoutParams glp = (android.widget.FrameLayout.LayoutParams) gu.getLayoutParams();
        glp.topMargin = Math.round((wide ? 16 : 10) * d);
        glp.setMarginEnd(Math.round((wide ? 34 : 14) * d));
        gu.setLayoutParams(glp);
    }

    /** A corner icon button: quiet outlined circle when idle, brighter ring when activated (Adult mode), solid fill when focused. */
    private void styleCornerIcon(ImageView icon, float d) {
        GradientDrawable focused = new GradientDrawable(), active = new GradientDrawable(), off = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL); focused.setColor(text);
        active.setShape(GradientDrawable.OVAL); active.setColor((text & 0xFFFFFF) | 0x26000000);
        active.setStroke(Math.round(1.5f * d), text);
        off.setShape(GradientDrawable.OVAL); off.setColor(0); off.setStroke(Math.round(1 * d), divider);
        StateListDrawable bgd = new StateListDrawable();
        bgd.addState(new int[]{android.R.attr.state_focused}, focused);
        bgd.addState(new int[]{android.R.attr.state_activated}, active);
        bgd.addState(new int[0], off);
        icon.setBackground(bgd);
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_activated}, {}};
        icon.setImageTintList(new ColorStateList(states, new int[]{bg | 0xFF000000, text, muted}));
    }

    /** Hairline ring, brighter when focused (camera cards). */
    static StateListDrawable ring(float radiusDp, float d, int focused, int idle) {
        GradientDrawable on = new GradientDrawable(), off = new GradientDrawable();
        on.setCornerRadius(radiusDp * d); on.setStroke(Math.round(2.5f * d), (focused & 0xFFFFFF) | 0xF2000000);
        off.setCornerRadius(radiusDp * d); off.setStroke(Math.round(1 * d), idle);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[0], off);
        return s;
    }

    // ---------- building ----------

    private static final class Builder {
        final String id, name;
        int[][] sky; int[] ribbons = new int[0]; float ribbonStrength; boolean ribbonsAtNight; int overcast = 0xFF97A3B3;
        int bg, text, soft, muted, faint, divider, tile, pill, pillText, ring, idle;
        float ringAccent, glow = 1f; int glowColour;
        int clockFont = R.font.inter_extralight, greetingFont = R.font.inter_medium;
        float clockSp = 60, greetingSp = 18;
        boolean light;

        Builder(String id, String name) { this.id = id; this.name = name; }
        Builder sky(int[][] k) { sky = k; return this; }
        Builder ribbons(float strength, boolean atNight, int... c) {
            ribbonStrength = strength; ribbonsAtNight = atNight; ribbons = c; return this;
        }
        Builder overcast(int c) { overcast = c; return this; }
        Builder ink(int bg, int text, int soft, int muted, int faint, int divider) {
            this.bg = bg; this.text = text; this.soft = soft; this.muted = muted; this.faint = faint; this.divider = divider;
            return this;
        }
        Builder surfaces(int tile, int pill, int pillText, int ring, int idle, float ringAccent) {
            this.tile = tile; this.pill = pill; this.pillText = pillText; this.ring = ring; this.idle = idle;
            this.ringAccent = ringAccent;
            return this;
        }
        Builder glow(float strength, int colour) { glow = strength; glowColour = colour; return this; }
        Builder fonts(int clock, float clockSp, int greeting, float greetingSp) {
            clockFont = clock; this.clockSp = clockSp; greetingFont = greeting; this.greetingSp = greetingSp;
            return this;
        }
        Builder light() { light = true; return this; }
        Look build() { return new Look(this); }
    }
}
