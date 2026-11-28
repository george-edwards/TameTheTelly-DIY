// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    private val sample = """
        # Changelog

        ## v26.11.28.1 — 2026-11-28
        - Keep camera vignette anchored to top when `labelOffsetLines` lowers the label.
        - Add About screen in Settings.

        ## v26.11.27.13 - 2026-11-27
        - World Cam pre-fetch and live videoId cache.
    """.trimIndent()

    @Test fun parsesEntriesAndStripsBackticks() {
        val entries = Changelog.parse(sample)
        assertEquals(2, entries.size)
        assertEquals("26.11.28.1", entries[0].version)
        assertEquals("2026-11-28", entries[0].date)
        assertEquals(2, entries[0].notes.size)
        assertEquals(
            "Keep camera vignette anchored to top when labelOffsetLines lowers the label.",
            entries[0].notes[0]
        )
        assertEquals("26.11.27.13", entries[1].version)
        assertEquals("2026-11-27", entries[1].date)
    }

    @Test fun matchesVersionOrFallsBackToLatest() {
        val entries = Changelog.parse(sample)
        val exact = Changelog.forVersion(entries, "26.11.27.13")
        assertNotNull(exact)
        assertEquals("26.11.27.13", exact!!.version)

        val fallback = Changelog.forVersion(entries, "99.0.0.1")
        assertEquals("26.11.28.1", fallback!!.version)
    }

    @Test fun formatsAboutAndFullChangelog() {
        val entries = Changelog.parse(sample)
        val about = Changelog.formatAbout("26.11.28.1", 0L, entries)
        assertTrue(about.contains("Version: v26.11.28.1"))
        assertTrue(about.contains("Updated: 28 Nov 2026"))
        assertTrue(about.contains("• Add About screen in Settings."))

        val all = Changelog.formatAll(entries)
        assertTrue(all.contains("v26.11.28.1  ·  28 Nov 2026"))
        assertTrue(all.contains("v26.11.27.13  ·  27 Nov 2026"))
    }
}
