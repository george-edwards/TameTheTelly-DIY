// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ScreenTimeTest {
    private fun at(d: Int, h: Int, m: Int = 0) = Calendar.getInstance().apply { clear(); set(2026, 10, d, h, m) }.timeInMillis

    @Test fun dayTurnsOverAtFourAm() {
        assertEquals("2026-11-02", ScreenTime.dayOf(at(2, 4), 4))
        assertEquals("2026-11-02", ScreenTime.dayOf(at(2, 21), 4))
        assertEquals("2026-11-02", ScreenTime.dayOf(at(3, 3, 59), 4))   // late night still counts as yesterday
        assertEquals("2026-11-03", ScreenTime.dayOf(at(3, 4), 4))
    }

    @Test fun progress() {
        assertEquals(0.5f, ScreenTime.progress(15 * 60_000L, 30 * 60_000L), 1e-6f)
        assertEquals(1f, ScreenTime.progress(1, 0), 1e-6f)
    }

    @Test fun adultScheduleWindowAndTimeFormatting() {
        // Same-day window: 19:30 to 23:00
        val s = HomeConfig.parseTimeMin("19:30")
        val e = HomeConfig.parseTimeMin("23:00")
        assertEquals(19 * 60 + 30, s)
        assertEquals(23 * 60, e)
        assertEquals("19:30", HomeConfig.formatTime24(s))
        assertEquals("7:30 pm", HomeConfig.formatTime12(s))
        assertEquals("11:00 pm", HomeConfig.formatTime12(e))
        assertEquals("12:00 am", HomeConfig.formatTime12(0))
        assertEquals("12:05 pm", HomeConfig.formatTime12(12 * 60 + 5))
        assertEquals(-1, HomeConfig.parseTimeMin(""))
        assertEquals(-1, HomeConfig.parseTimeMin("25:00"))

        org.junit.Assert.assertFalse(ScreenTime.inWindow(19 * 60 + 29, s, e))
        org.junit.Assert.assertTrue(ScreenTime.inWindow(19 * 60 + 30, s, e))
        org.junit.Assert.assertTrue(ScreenTime.inWindow(22 * 60 + 59, s, e))
        org.junit.Assert.assertFalse(ScreenTime.inWindow(23 * 60, s, e))

        // Overnight window: 20:00 to 05:00
        val os = HomeConfig.parseTimeMin("20:00")
        val oe = HomeConfig.parseTimeMin("05:00")
        org.junit.Assert.assertTrue(ScreenTime.inWindow(23 * 60 + 30, os, oe))
        org.junit.Assert.assertTrue(ScreenTime.inWindow(2 * 60, os, oe))
        org.junit.Assert.assertFalse(ScreenTime.inWindow(5 * 60, os, oe))
        org.junit.Assert.assertFalse(ScreenTime.inWindow(19 * 60 + 59, os, oe))

        // Next occurrence: later today vs tomorrow
        assertEquals(at(2, 23, 0), ScreenTime.nextOccurrence(at(2, 14, 0), 23 * 60))
        assertEquals(at(3, 19, 30), ScreenTime.nextOccurrence(at(2, 20, 0), 19 * 60 + 30))
        assertEquals(at(3, 19, 30), ScreenTime.nextOccurrence(at(2, 19, 30), 19 * 60 + 30))

        // Scheduled override caps late-night adult extensions at the 4 am reset instead of ~24 h
        assertEquals(at(2, 23, 0), ScreenTime.scheduledOverrideUntil(at(2, 14, 0), true, s, e, 4))
        assertEquals(at(3, 4, 0), ScreenTime.scheduledOverrideUntil(at(2, 23, 5), true, s, e, 4))
        assertEquals(at(3, 19, 30), ScreenTime.scheduledOverrideUntil(at(2, 20, 0), false, s, e, 4))
        // Overnight schedule spanning the 4 am reset falls back at the schedule end (5 am), not 4 am
        assertEquals(at(3, 5, 0), ScreenTime.scheduledOverrideUntil(at(2, 14, 0), true, os, oe, 4))
        // Negative sentinel normalization
        assertEquals("23:59", HomeConfig.formatTime24(-1))
        assertEquals("11:59 pm", HomeConfig.formatTime12(-1))
    }
}
