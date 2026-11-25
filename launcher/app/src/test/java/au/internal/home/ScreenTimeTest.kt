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
        assertEquals(1f, ScreenTime.progress(30 * 60_000L, 30 * 60_000L), 1e-6f)
        assertEquals(1f, ScreenTime.progress(35 * 60_000L, 30 * 60_000L), 1e-6f)
    }

    @Test fun freshJourneyAfterExpiry() {
        val m = 60_000L
        // 30 min used of 30 min, then +10 min granted -> fresh journey from 0% over those 10 min
        val p0 = ScreenTime.progress(30 * m, 40 * m, 30 * m)
        assertEquals(0f, p0, 1e-6f)
        assertEquals(10 * m, ScreenTime.journeySpan(30 * m, 40 * m, 30 * m))
        assertEquals(10, ScreenTime.minutesLeft(p0, 10 * m))

        // 5 min into the 10-min top-up -> halfway across, 5 min left
        val p5 = ScreenTime.progress(35 * m, 40 * m, 30 * m)
        assertEquals(0.5f, p5, 1e-6f)
        assertEquals(5, ScreenTime.minutesLeft(p5, ScreenTime.journeySpan(35 * m, 40 * m, 30 * m)))

        // Adding another +10 min before watching any of the first top-up keeps 0% with 20 min left
        val p20 = ScreenTime.progress(30 * m, 50 * m, 30 * m)
        assertEquals(0f, p20, 1e-6f)
        assertEquals(20, ScreenTime.minutesLeft(p20, ScreenTime.journeySpan(30 * m, 50 * m, 30 * m)))

        // Daily limit lowered below journeyStart: still safe and clamped to asleep
        assertEquals(1f, ScreenTime.progress(30 * m, 25 * m, 30 * m), 1e-6f)
        assertEquals(0L, ScreenTime.journeySpan(30 * m, 25 * m, 30 * m))
    }

    @Test fun describeCapsUsedAtAllowance() {
        assertEquals("0 of 20 min used today", ScreenTime.describe(0, 20 * 60_000L))
        assertEquals("12 of 20 min used today", ScreenTime.describe(12 * 60_000L, 20 * 60_000L))
        assertEquals("20 of 20 min used today", ScreenTime.describe(20 * 60_000L, 20 * 60_000L))
        assertEquals("20 of 20 min used today", ScreenTime.describe(22 * 60_000L, 20 * 60_000L))
        assertEquals("0 of 0 min used today", ScreenTime.describe(5 * 60_000L, 0))
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
