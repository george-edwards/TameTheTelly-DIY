// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.cash.paparazzi.DeviceConfig
import com.android.resources.Density
import com.android.resources.ScreenOrientation

/** The TV: 1920x1080 at 320 dpi (960x540 dp). */
val TV: DeviceConfig = DeviceConfig.PIXEL_5.copy(
    screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320,
    density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
)

/** Stand-in banners for the render only: flat colour + name (real banners come from each app). */
data class FakeApp(val label: String, val bg: Int, val fg: Int, val fav: Boolean = false)

val FAKE_APPS = listOf(
    FakeApp("Netflix", 0xFF0B0B0B.toInt(), 0xFFE50914.toInt(), fav = true),
    FakeApp("Plex", 0xFF1F2326.toInt(), 0xFFE5A00D.toInt(), fav = true),
    FakeApp("ABC iview", 0xFF0A0A0A.toInt(), Color.WHITE, fav = true),
    FakeApp("YouTube", 0xFFF9F9F9.toInt(), 0xFFFF0033.toInt(), fav = true),
    FakeApp("Prime Video", 0xFF0F79AF.toInt(), Color.WHITE),
    FakeApp("Disney+", 0xFF0E1A4F.toInt(), Color.WHITE),
    FakeApp("SBS On Demand", 0xFFFFD100.toInt(), 0xFF111111.toInt()),
    FakeApp("Spotify", 0xFF121212.toInt(), 0xFF1DB954.toInt()),
    FakeApp("Kayo", 0xFF050505.toInt(), 0xFF2DE38A.toInt()),
    FakeApp("Binge", 0xFF3B1462.toInt(), Color.WHITE),
    FakeApp("BritBox", 0xFF1B2A6B.toInt(), Color.WHITE),
    FakeApp("9Now", 0xFF0078D2.toInt(), Color.WHITE),
    FakeApp("10 Play", 0xFF101010.toInt(), Color.WHITE),
    FakeApp("7plus", 0xFFE2231A.toInt(), Color.WHITE),
    FakeApp("VLC", 0xFFFF8800.toInt(), Color.WHITE),
)

fun FakeApp.banner(ctx: Context): Drawable {
    val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(bg)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = if (label.length > 9) 34f else 46f
    }
    c.drawText(label, 160f, 90f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(ctx.resources, bmp)
}

/** A camera still from src/test/resources/<name>.jpg (gitignored: any 640x360 jpg), or a plain grey frame. */
fun camFrame(ctx: Context, name: String): Drawable {
    val stream = FakeApp::class.java.classLoader!!.getResourceAsStream("$name.jpg")
    val bmp = stream?.let { android.graphics.BitmapFactory.decodeStream(it) }
        ?: Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF39424E.toInt()) }
    return BitmapDrawable(ctx.resources, bmp)
}

/** Replace a camera card's TextureView (layoutlib can't show video) with a still frame. */
fun fakeFeed(cam: android.view.View, frame: Drawable) {
    val box = cam as android.view.ViewGroup
    val iv = android.widget.ImageView(cam.context).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageDrawable(frame)
    }
    box.addView(iv, 1, android.widget.FrameLayout.LayoutParams(-1, -1))
    cam.findViewById<android.view.View>(R.id.state).visibility = android.view.View.GONE
}
