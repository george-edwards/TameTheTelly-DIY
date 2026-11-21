// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Renders the redesigned home screen with the real layouts, drawables and drawing classes. */
class AfterTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = TV, theme = "HomeTheme", useDeviceResolution = true)

    private val d get() = paparazzi.context.resources.displayMetrics.density

    internal inner class Screen(minute: Int, greeting: String, date: String, wxText: String, wxCode: Int, wxDay: Boolean,
                       bins: Array<String>? = null, backlight: Float = 1f, val look: Look = Look.TWILIGHT,
                       val wide: Boolean = false, cornerFocusId: Int = 0) {
        val ctx = paparazzi.context
        // The TV backlight can't be rendered, so it's simulated: black over the whole frame, sized so the
        // result is the panel's light output scaled by `backlight` (linear light, sRGB-encoded frame).
        val root: View = android.widget.FrameLayout(ctx).apply {
            addView(LayoutInflater.from(ctx).inflate(R.layout.activity_main, this, false))
            addView(View(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                alpha = 1f - Math.pow(backlight.toDouble(), 1 / 2.2).toFloat()
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val sky = Sky()
        val tiles = HashMap<Int, View>()
        val frames = HashMap<Int, TileFrame>()
        val initial = HashMap<Int, Float>()       // position -> scale applied at bind
        private val rows = ArrayList<Any>()

        init {
            // All scenes use 5 Nov 2026's sun at Rankin Park (sunrise 5:51, sunset 7:22 pm, dark 8:20 pm).
            sky.setSun(-32.9252, 151.6802, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Australia/Sydney"))
                .apply { clear(); set(2026, 10, 5, 12, 0) })
            sky.setTime(minute); sky.setMood(Sky.moodFor(wxCode))
            root.findViewById<View>(R.id.left).background = sky
            root.findViewById<View>(R.id.right).visibility = if (wide) View.GONE else View.VISIBLE
            look.apply(root, sky, wide)
            if (cornerFocusId != 0) root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val btn = root.findViewById<View>(cornerFocusId)
                    btn.isFocusableInTouchMode = true
                    btn.requestFocus()
                    root.findViewById<TextView>(R.id.cornerLabel).apply { text = btn.contentDescription; alpha = 1f }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
            root.findViewById<TextView>(R.id.greeting).text = greeting
            root.findViewById<TextClock>(R.id.date).apply { format12Hour = "'$date'"; format24Hour = "'$date'" }
            root.findViewById<TextView>(R.id.weather).apply {
                text = wxText; setCompoundDrawablesRelativeWithIntrinsicBounds(Weather.icon(wxCode, wxDay), 0, 0, 0)
            }
            Bins.show(root.findViewById(R.id.bins), bins)
            fakeFeed(root.findViewById(R.id.cam1), camFrame(ctx, "cam1"))
            fakeFeed(root.findViewById(R.id.cam2), camFrame(ctx, "cam2"))
            root.findViewById<View>(R.id.cam1).findViewById<TextView>(R.id.name).text = "Front door"
            root.findViewById<View>(R.id.cam2).findViewById<TextView>(R.id.name).text = "Playroom"

            rows.add("Favourites"); rows.addAll(FAKE_APPS.filter { it.fav }); rows.add("All apps"); rows.addAll(FAKE_APPS.filter { !it.fav })
            val grid = root.findViewById<RecyclerView>(R.id.grid)
            grid.addItemDecoration(Glow(d).also { it.setLook(look) })
            grid.layoutManager = GridLayoutManager(ctx, 12).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {   // as MainActivity.cols
                    override fun getSpanSize(p: Int) = when (val r = rows[p]) {
                        is String -> 12; is FakeApp -> if (r.fav) (if (wide) 3 else 6) else (if (wide) 2 else 4); else -> 4 }
                }
            }
            grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemViewType(p: Int) = if (rows[p] is String) 0 else 1
                override fun getItemCount() = rows.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                    LayoutInflater.from(ctx).inflate(if (t == 0) R.layout.section else R.layout.tile, p, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
                    val r = rows[p]
                    if (r is String) { (h.itemView as TextView).text = r; (h.itemView as TextView).setTextColor(look.faint); return }
                    val banner = (r as FakeApp).banner(ctx)
                    val accent = TileFx.accentOf(banner)              // same as MainActivity/AppStore
                    val v = h.itemView
                    val frame = TileFrame(d).also { v.foreground = it; it.setLook(look); it.setAccent(accent) }
                    v.setTag(R.id.accent, accent)
                    val shadow = if (look.glowColour != 0) look.glowColour else accent
                    v.outlineSpotShadowColor = shadow; v.outlineAmbientShadowColor = shadow
                    v.findViewById<ImageView>(R.id.banner).setImageDrawable(banner)
                    v.findViewById<View>(R.id.iconBox).visibility = View.GONE
                    tiles[p] = v; frames[p] = frame
                    initial[p]?.let { focusTile(p, it) }
                }
            }
        }

        /** Raise tile `p` to `scale` (1 = idle, 1.08 = focused) exactly as the springs would. */
        fun focusTile(p: Int, scale: Float) {
            val v = tiles[p] ?: run { initial[p] = scale; return }
            val k = ((scale - 1f) / (TileFx.FOCUS - 1f)).coerceAtLeast(0f)
            TileFx.set(v, scale, TileFx.LIFT_DP * d * k)
            frames[p]!!.setFocused(k > 0.5f)
        }

        fun parallaxToward(nx: Float, ny: Float) = sky.setParallax(-nx * Sky.PARALLAX_X_DP * d, -ny * Sky.PARALLAX_Y_DP * d)
        fun clock(from: String, to: String, p: Float) = root.findViewById<RollingClock>(R.id.clock).freeze(from, to, p)
    }

    @Test fun morning() {
        val s = Screen(9 * 60 + 41, "Good morning, Smith family", "Monday 2 November",
            "21° Mostly sunny  ·  ↑26° ↓15°", 1, true)
        s.clock("9:41", "9:41", 1f)
        s.focusTile(1, TileFx.FOCUS)
        s.parallaxToward(-0.75f, -0.3f)
        paparazzi.snapshot(s.root, "1-morning")
    }

    @Test fun afternoonRain() {
        val s = Screen(15 * 60 + 5, "Good afternoon, Smith family", "Monday 2 November",
            "17° Rain  ·  ↑19° ↓14°", 63, true)
        s.clock("3:05", "3:05", 1f)
        s.focusTile(2, TileFx.FOCUS)
        s.parallaxToward(-0.35f, -0.3f)
        paparazzi.snapshot(s.root, "2-afternoon-rain")
    }

    @Test fun dusk() {
        val s = Screen(19 * 60 + 20, "Good evening, Smith family", "Tuesday 3 November",
            "22° Partly cloudy  ·  ↑27° ↓16°", 2, true)
        s.clock("7:20", "7:20", 1f)
        s.focusTile(7, TileFx.FOCUS)
        s.parallaxToward(-0.5f, 0.6f)
        paparazzi.snapshot(s.root, "3-dusk")
    }

    @Test fun binNightBedtime() {
        val s = Screen(19 * 60 + 45, "Good evening, Smith family", "Thursday 5 November",
            "18° Clear  ·  ↑25° ↓13°", 0, false, bins = arrayOf(Bins.GREEN, Bins.RED), backlight = 0.6f)
        s.clock("7:45", "7:45", 1f)
        s.focusTile(4, TileFx.FOCUS)
        s.parallaxToward(-0.15f, -0.1f)
        paparazzi.snapshot(s.root, "4-bin-night-bedtime")
    }

    @Test fun binNightDaytime() {
        val s = Screen(16 * 60 + 10, "Good afternoon, Smith family", "Thursday 12 November",
            "24° Thunderstorms  ·  ↑28° ↓17°", 95, true, bins = arrayOf(Bins.GREEN, Bins.YELLOW))
        s.clock("10:10", "10:10", 1f)   // widest clock + longest weather: checks nothing gets cut off
        s.focusTile(1, TileFx.FOCUS)
        s.parallaxToward(-0.75f, -0.3f)
        paparazzi.snapshot(s.root, "5-bin-night-landfill")
    }

    /** Every theme, beside the cameras and across the whole screen, by day and at bedtime. */
    @Test fun themes() {
        for (look in Look.ALL) for (wide in listOf(false, true)) {
            val day = Screen(10 * 60 + 30, "Good morning, Smith family", "Monday 2 November",
                "21° Mostly sunny  ·  ↑26° ↓15°", 1, true, look = look, wide = wide)
            day.clock("10:30", "10:30", 1f)
            day.focusTile(1, TileFx.FOCUS)
            day.parallaxToward(-0.6f, -0.3f)
            paparazzi.snapshot(day.root, "theme-${look.id}-${if (wide) "wide" else "split"}-day")
            val night = Screen(20 * 60 + 40, "Good evening, Smith family", "Thursday 5 November",
                "18° Clear  ·  ↑25° ↓13°", 0, false, bins = arrayOf(Bins.GREEN, Bins.RED), look = look, wide = wide)
            night.clock("8:40", "8:40", 1f)
            night.focusTile(if (wide) 3 else 2, TileFx.FOCUS)
            night.parallaxToward(-0.2f, -0.2f)
            paparazzi.snapshot(night.root, "theme-${look.id}-${if (wide) "wide" else "split"}-night")
        }
    }

    /** The four corner buttons (Adult/Kids toggle, Kids' time, Remote buttons, Settings) with focus, in both layouts. */
    @Test fun grownUpsButton() {
        for (wide in listOf(false, true)) {
            for ((name, id) in listOf("mode-btn" to R.id.modeBtn, "time-btn" to R.id.timeBtn, "remote-btn" to R.id.remoteBtn, "grown-ups" to R.id.grownups)) {
                val s = Screen(16 * 60 + 10, "Good afternoon, Smith family", "Thursday 12 November",
                    "24° Thunderstorms  ·  ↑28° ↓17°", 95, true, bins = arrayOf(Bins.GREEN, Bins.YELLOW), wide = wide,
                    cornerFocusId = id)
                s.clock("10:10", "10:10", 1f)
                paparazzi.snapshot(s.root, "$name-${if (wide) "wide" else "split"}")
            }
        }
    }

    /** Focus moves Netflix -> Plex while the clock rolls over: one frame per 1/30 s. */
    @Test fun focusMotion() {
        val s = Screen(10 * 60 + 59, "Good morning, Smith family", "Monday 2 November",
            "21° Mostly sunny  ·  ↑26° ↓15°", 1, true)
        s.focusTile(1, TileFx.FOCUS)
        s.parallaxToward(-0.75f, -0.3f)
        s.clock("10:59", "10:59", 1f)
        paparazzi.snapshot(s.root, "frame-000")
        val fps = 30
        for (f in 1..36) {
            val t = f / fps.toFloat()
            val x = spring(t)                                  // 0 -> 1, with overshoot
            s.focusTile(1, TileFx.FOCUS - (TileFx.FOCUS - 1f) * x)
            s.focusTile(2, 1f + (TileFx.FOCUS - 1f) * x)
            s.frames[1]!!.setFocused(false); s.frames[2]!!.setFocused(true)
            val sw = (t - 0.09f) / 0.75f
            s.frames[2]!!.setSweep(if (sw in 0f..1f) bezier(sw) else -1f)
            val pd = 1f - (1f - (t / 0.9f).coerceAtMost(1f)).let { it * it }   // decelerate(2)
            s.parallaxToward(-0.75f + (-0.15f + 0.75f) * pd, -0.3f)
            val c = ((t - 0.2f) / 0.65f).coerceIn(0f, 1f)
            if (t < 0.2f) s.clock("10:59", "10:59", 1f) else s.clock("10:59", "11:00", bezier(c, 0.3f, 0f, 0.1f, 1f))
            paparazzi.snapshot(s.root, "frame-%03d".format(f))
        }
    }

    /** Same spring as TileFx: stiffness 520, damping ratio 0.58, released from rest. */
    private fun spring(t: Float): Float {
        val w0 = sqrt(TileFx.STIFFNESS.toDouble()); val z = TileFx.DAMPING.toDouble(); val wd = w0 * sqrt(1 - z * z)
        return (1 - exp(-z * w0 * t) * (cos(wd * t) + z / sqrt(1 - z * z) * sin(wd * t))).toFloat()
    }

    /** CSS-style cubic bezier easing, default = TileFrame's sweep curve. */
    private fun bezier(x: Float, x1: Float = 0.4f, y1: Float = 0f, x2: Float = 0.2f, y2: Float = 1f): Float {
        fun b(t: Float, a: Float, c: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * c + t * t * t
        var lo = 0f; var hi = 1f
        repeat(30) { val m = (lo + hi) / 2; if (b(m, x1, x2) < x) lo = m else hi = m }
        return b((lo + hi) / 2, y1, y2)
    }
}
