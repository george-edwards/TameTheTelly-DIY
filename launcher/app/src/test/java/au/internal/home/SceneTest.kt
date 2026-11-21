// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import android.graphics.Color

/** The YouTube bedtime scenes at points through the allowance, over a stand-in video frame. */
class SceneTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = TV, theme = "HomeTheme", useDeviceResolution = true)

    private val scenes = HomeConfig.SCENES.toList()

    private fun make(id: String, mode: Scene.Mode): Scene {
        val r = paparazzi.context.resources
        return when (id) {
            HomeConfig.SCENE_FAIRY -> FairyScene(mode)
            HomeConfig.SCENE_TRAIN -> TrainScene(mode)
            else -> SunScene(mode)
        }
    }

    /** The scene drawn into its own bitmap (so the hole really is transparent), over a "video". */
    private fun frame(id: String, mode: Scene.Mode, p: Float, t: Float = 7f, close: Float = 0f, bye: Float = 0f): View {
        val ctx = paparazzi.context
        val scene = make(id, mode).also { it.p = p; it.t = t; it.close = close; it.bye = bye
            it.minutesLeft = Math.ceil((1.0 - p) * 30 - 1e-6).toInt().coerceAtLeast(0) }   // a 30-minute allowance
        val bmp = Bitmap.createBitmap(Scene.W, Scene.H, Bitmap.Config.ARGB_8888)
        // Two frames, like the TV: paint state left over from one frame must not leak into the next.
        scene.draw(Canvas(bmp), Scene.W, Scene.H)
        bmp.eraseColor(0)
        scene.draw(Canvas(bmp), Scene.W, Scene.H)
        return FrameLayout(ctx).apply {
            if (mode == Scene.Mode.OVERLAY) addView(object : View(ctx) {
                // Anonymous illustrated stand-in: snapshots never contain a household camera image.
                override fun onDraw(c: Canvas) {
                    val h = Scene.HOLE
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val save = c.save()
                    c.clipRect(h)
                    paint.shader = android.graphics.LinearGradient(0f, h.top, 0f, h.bottom,
                        0xFF89B2B0.toInt(), 0xFFD7DFC2.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(h, paint)
                    paint.shader = null
                    paint.color = 0xFFEDE6C7.toInt()
                    c.drawCircle(1260f, 370f, 62f, paint)
                    val hill = android.graphics.Path().apply {
                        moveTo(240f, 760f); cubicTo(570f, 400f, 870f, 850f, 1160f, 690f)
                        quadTo(1450f, 540f, 1680f, 720f); lineTo(1680f, 910f); lineTo(240f, 910f); close()
                    }
                    paint.color = 0xFF8EAF96.toInt()
                    c.drawPath(hill, paint)
                    hill.reset()
                    hill.moveTo(240f, 880f); hill.quadTo(760f, 630f, 1110f, 840f)
                    hill.quadTo(1430f, 990f, 1680f, 800f); hill.lineTo(1680f, 910f)
                    hill.lineTo(240f, 910f); hill.close()
                    paint.color = 0xFF6D947E.toInt()
                    c.drawPath(hill, paint)
                    c.restoreToCount(save)
                }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** The picture opening stays transparent, including after paint is reused. */
    @Test fun videoApertureAndRepeatedFrames() { for (id in scenes) aperture(id) }

    private fun aperture(id: String) {
        val scene = make(id, Scene.Mode.OVERLAY)
        val bmp = Bitmap.createBitmap(Scene.W, Scene.H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        scene.p = 0.5f
        scene.t = 7f
        bmp.eraseColor(0)
        scene.draw(canvas, Scene.W, Scene.H)
        assertEquals(0, Color.alpha(bmp.getPixel(960, 500)))
        assertEquals(255, Color.alpha(bmp.getPixel(100, 500)))
        val first = IntArray(Scene.W * Scene.H)
        bmp.getPixels(first, 0, Scene.W, 0, 0, Scene.W, Scene.H)
        bmp.eraseColor(0)
        scene.draw(canvas, Scene.W, Scene.H)
        val second = IntArray(first.size)
        bmp.getPixels(second, 0, Scene.W, 0, 0, Scene.W, Scene.H)
        assertArrayEquals("$id: paint state must not leak between frames", first, second)
        scene.close = 1f
        scene.draw(canvas, Scene.W, Scene.H)
        assertEquals(255, Color.alpha(bmp.getPixel(960, 500)))
    }

    @Test fun progression() {
        for (id in scenes) {
            for (p in listOf(0f, 0.25f, 0.5f, 0.68f, 0.74f, 0.8f, 0.85f, 0.9f, 0.95f, 1f))
                paparazzi.snapshot(frame(id, Scene.Mode.OVERLAY, p), "$id-p%03d".format((p * 100).toInt()))
            paparazzi.snapshot(frame(id, Scene.Mode.OVERLAY, 1f, close = 1f), "$id-p100-closed")
        }
    }

    @Test fun goodnight() {
        for (id in scenes) {
            paparazzi.snapshot(frame(id, Scene.Mode.GOODNIGHT, 1f), "$id-goodnight")
            paparazzi.snapshot(frame(id, Scene.Mode.GOODNIGHT, 1f, bye = 0.25f), "$id-goodnight-bye25")
            paparazzi.snapshot(frame(id, Scene.Mode.GOODNIGHT, 1f, bye = 0.6f), "$id-goodnight-bye60")
        }
    }

    /** The scene tile at morning, sunset and nearly-night, side by side at tile size, for each scene. */
    @Test fun sceneTile() {
        val ctx = paparazzi.context
        val font = ctx.resources.getFont(R.font.inter_semibold)
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E13.toInt()); setPadding(40, 20, 40, 20) }
        for (id in scenes) {
            val row = android.widget.LinearLayout(ctx)
            for (p in listOf(0.1f, 0.78f, 0.95f)) {
                val b = SceneTile("YouTube", font, make(id, Scene.Mode.TILE), 0xFFFF0033.toInt()).also { it.setProgress(p) }
                row.addView(ImageView(ctx).apply { setImageDrawable(b); scaleType = ImageView.ScaleType.FIT_XY },
                    android.widget.LinearLayout.LayoutParams(560, 315).apply { setMargins(20, 10, 20, 10) })
            }
            col.addView(row)
        }
        paparazzi.snapshot(col, "scene-tile")
    }

    /** Settings: config.xml defaults are valid, and codes/scenes read as they should. (Layoutlib has no real
     *  SharedPreferences, so saving and resetting were checked on the TV over adb SET instead.) */
    @Test fun settings() {
        val r = paparazzi.context.resources
        assertEquals(true, HomeConfig.validCode(r.getString(R.string.parent_code)))
        assertEquals(false, HomeConfig.validCode("U"))
        assertEquals(false, HomeConfig.validCode("UXD"))
        assertEquals(false, HomeConfig.validCode("UUUUUUUUU"))
        assertEquals(true, HomeConfig.indexOf(HomeConfig.SCENES, r.getString(R.string.scene)) >= 0)
        assertEquals(true, r.getString(R.string.weather_postcode).isEmpty() || Postcodes.valid(r.getString(R.string.weather_postcode)))
        assertEquals(false, Postcodes.valid("228"))
        assertEquals("Australia/Sydney", Postcodes.timeZone("2287", "NSW"))
        assertEquals("Australia/Broken_Hill", Postcodes.timeZone("2880", "NSW"))
        assertEquals("Australia/Perth", Postcodes.timeZone("6000", "WA"))
        assertEquals("30 minutes", SettingsMenu.minutesLabel(30))
        assertEquals("1 hour", SettingsMenu.minutesLabel(60))
        assertEquals("1½ hours", SettingsMenu.minutesLabel(90))
        assertEquals("2 hours", SettingsMenu.minutesLabel(120))
        assertEquals("Steam train", HomeConfig.sceneName("train"))
        for (day in 1..7) assertEquals(day, SettingsMenu.nextDay(day).get(java.util.Calendar.DAY_OF_WEEK))
    }
}
