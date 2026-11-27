// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldCamsTest {
    @After fun tearDown() {
        WorldCams.clearOnlineForTest()
    }

    @Test fun parsesBundledStreamsCatalog() {
        val file = File("../../streams.json")
        assertTrue("streams.json should exist at repo root", file.exists())
        val streams = WorldCams.parse(file.readText())
        assertTrue("should parse curated streams", streams.size >= 20)

        val keys = streams.map { it.key }
        assertEquals("all stream keys should be unique", keys.size, keys.toSet().size)

        val newcastle = streams.first { it.name.contains("Newcastle") }
        assertEquals("youtube_channel", newcastle.type)
        assertTrue(newcastle.embedUrl().contains("embed/live_stream?channel=UCTGhjtFloAPlbLy4_yrkoWg"))
        assertTrue(newcastle.embedUrl().contains("autoplay=1&mute=1"))

        val nasa = streams.first { it.name.contains("NASA") }
        assertEquals("youtube_multi", nasa.type)
        assertTrue(nasa.embedUrl().contains("embed/ROboUHfPUrU?"))

        val sydney = streams.first { it.name.contains("Sydney Harbour") }
        assertEquals("youtube_channel", sydney.type)
        assertEquals("UCLav_kTu9PmAEChvGyrPbhQ", sydney.channelId)
        assertTrue(sydney.displayName().startsWith("🇦🇺 "))
    }

    @Test fun extractsVideoIdsFromAllYoutubeFormats() {
        assertEquals("dypAtzvl24s", WorldCams.extractVideoId("https://www.youtube.com/watch?v=dypAtzvl24s"))
        assertEquals("dfVK7ld38Ys", WorldCams.extractVideoId("https://www.youtube.com/live/dfVK7ld38Ys?si=abc"))
        assertEquals("ROboUHfPUrU", WorldCams.extractVideoId("https://youtu.be/ROboUHfPUrU"))
    }

    @Test fun nextAndPrevSkipKnownOfflineStreams() {
        val s0 = WorldCams.Stream("S0", "Cat", "youtube_channel", "https://youtube.com/@s0/live", "UC0", "")
        val s1 = WorldCams.Stream("S1", "Cat", "youtube_channel", "https://youtube.com/@s1/live", "UC1", "")
        val s2 = WorldCams.Stream("S2", "Cat", "youtube_channel", "https://youtube.com/@s2/live", "UC2", "")
        val s3 = WorldCams.Stream("S3", "Cat", "youtube_channel", "https://youtube.com/@s3/live", "UC3", "")
        val pool = listOf(s0, s1, s2, s3)

        WorldCams.setOnlineForTest(s0, true)
        WorldCams.setOnlineForTest(s1, false) // offline -> should skip
        // s2 left unknown (null) -> should NOT skip
        WorldCams.setOnlineForTest(s3, false) // offline -> should skip

        assertNull(WorldCams.onlineStatus(s2))
        assertTrue(WorldCams.isOnline(s0))
        assertTrue(WorldCams.isKnownOffline(s1))
        assertFalse(WorldCams.isKnownOffline(s2))
        assertEquals(1, WorldCams.onlineCount(pool))

        assertEquals(s2.key, WorldCams.pickStep(0, 1, pool, null).key)
        assertEquals(s0.key, WorldCams.pickStep(2, 1, pool, null).key)
        assertEquals(s2.key, WorldCams.pickStep(0, -1, pool, null).key)

        // Fallback when all streams are known offline: still steps instead of getting stuck
        WorldCams.setOnlineForTest(s0, false)
        WorldCams.setOnlineForTest(s2, false)
        assertEquals(s1.key, WorldCams.pickStep(0, 1, pool, null).key)
    }

    @Test fun sortsStreamsWestToEastByLongitudeAndComputesDaylight() {
        val file = File("../../streams.json")
        val sorted = WorldCams.sortedByLongitude(WorldCams.parse(file.readText()))
        assertTrue(sorted.first().name.contains("Hawaii"))
        val earth = sorted.filter { it.hasCoords }
        assertTrue("most streams should have coordinates", earth.size >= 50)
        for (i in 1 until earth.size) {
            assertTrue("should be non-decreasing longitude", earth[i - 1].lon <= earth[i].lon)
        }
        assertFalse("roving/space streams should be at end", sorted.last().hasCoords)

        val sub = Sun.subsolar(1_764_201_600_000L) // 2025-11-27 00:00 UTC (noon near 180°, midnight near 0°)
        val altNoon = Sun.altitude(0.0, sub[1], sub[0], sub[1])
        val altMidnight = Sun.altitude(0.0, ((sub[1] + 180.0 + 180.0) % 360.0) - 180.0, sub[0], sub[1])
        assertTrue("subsolar point should be high day", altNoon > 60.0)
        assertTrue("antipode should be deep night", altMidnight < -60.0)
    }
}
