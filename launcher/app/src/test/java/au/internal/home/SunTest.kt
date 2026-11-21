// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Rankin Park NSW; expected values are approximate published Newcastle times (from memory, not looked up), ±4 min. */
class SunTest {
    private val tz = TimeZone.getTimeZone("Australia/Sydney")
    private val lat = -32.9252; private val lon = 151.6802
    private fun day(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min) }
    private fun hm(ms: Long) = Calendar.getInstance(tz).apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun near(expectH: Int, expectM: Int, ms: Long) =
        assertTrue("got ${hm(ms) / 60}:${hm(ms) % 60}", Math.abs(hm(ms) - (expectH * 60 + expectM)) <= 4)

    @Test fun summerAndWinter() {
        Sun.crossings(lat, lon, day(2026, 11, 5), Sun.SUNSET)!!.let { near(5, 50, it[0]); near(19, 26, it[1]) }   // AEDT
        Sun.crossings(lat, lon, day(2026, 6, 21), Sun.SUNSET)!!.let { near(6, 58, it[0]); near(16, 57, it[1]) }   // AEST
    }

    @Test fun stepsInOrderThroughAnEvening() {
        val s = Bedtime.steps(lat, lon, day(2026, 11, 5), 100, 60, 40)
        val noon = Bedtime.around(s, day(2026, 11, 5, 12).timeInMillis)
        assertEquals(100, noon[0]!!.level); assertEquals("sunset", noon[1]!!.name)
        val evening = Bedtime.around(s, day(2026, 11, 5, 19, 45).timeInMillis)
        assertEquals(60, evening[0]!!.level); assertEquals("dark", evening[1]!!.name)
        near(20, 22, evening[1]!!.at)                                                          // nautical dusk
        assertEquals(40, Bedtime.around(s, day(2026, 11, 5, 23, 30).timeInMillis)[0]!!.level)
        assertEquals(40, Bedtime.around(s, day(2026, 11, 5, 3).timeInMillis)[0]!!.level)    // previous night's dark
        assertEquals(60, Bedtime.around(s, day(2026, 11, 5, 5, 30).timeInMillis)[0]!!.level) // dawn

        // Blue Light Filter: on from sunset (through dark), off from sunrise (through the day)
        assertEquals(true, Bedtime.lastBlue(s, day(2026, 11, 5, 23, 30).timeInMillis)!!.blue)
        assertEquals(true, Bedtime.lastBlue(s, day(2026, 11, 5, 5, 30).timeInMillis)!!.blue)
        assertEquals(false, Bedtime.lastBlue(s, day(2026, 11, 5, 12).timeInMillis)!!.blue)
    }
}
