// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BinsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 19) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }
    private val anchor = "2026-11-12"
    private fun out(vararg bins: String) = arrayOf(*bins)

    @Test fun greenEveryWeekLakeMac() {
        val g = Bins.GREEN
        assertArrayEquals(out(g, Bins.RED), Bins.tonight(at(2026, 11, 5), Calendar.THURSDAY, anchor, g))
        assertArrayEquals(out(g, Bins.YELLOW), Bins.tonight(at(2026, 11, 12), Calendar.THURSDAY, anchor, g))
        assertArrayEquals(out(g, Bins.RED), Bins.tonight(at(2026, 11, 19, 0), Calendar.THURSDAY, anchor, g))
        assertArrayEquals(out(g, Bins.YELLOW), Bins.tonight(at(2026, 10, 29), Calendar.THURSDAY, anchor, g))   // before the anchor
        assertArrayEquals(out(g, Bins.YELLOW), Bins.tonight(at(2027, 1, 7), Calendar.THURSDAY, anchor, g))     // across new year: 8 weeks on
        assertArrayEquals(out(g, Bins.RED), Bins.tonight(at(2027, 1, 14), Calendar.THURSDAY, anchor, g))
    }

    @Test fun redEveryWeekNewcastle() {
        val r = Bins.RED
        assertArrayEquals(out(r, Bins.YELLOW), Bins.tonight(at(2026, 11, 12), Calendar.THURSDAY, anchor, r))
        assertArrayEquals(out(r, Bins.GREEN), Bins.tonight(at(2026, 11, 19), Calendar.THURSDAY, anchor, r))
    }

    @Test fun yellowEveryWeek() {
        val y = Bins.YELLOW
        assertArrayEquals(out(y, Bins.GREEN), Bins.tonight(at(2026, 11, 12), Calendar.THURSDAY, anchor, y))
        assertArrayEquals(out(y, Bins.RED), Bins.tonight(at(2026, 11, 19), Calendar.THURSDAY, anchor, y))
    }

    @Test fun onlyOnBinNight() {
        assertNull(Bins.tonight(at(2026, 11, 4), Calendar.THURSDAY, anchor, Bins.GREEN))
        assertNull(Bins.tonight(at(2026, 11, 6), Calendar.THURSDAY, anchor, Bins.RED))
    }
}
