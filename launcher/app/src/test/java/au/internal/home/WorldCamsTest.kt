// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldCamsTest {
    @Test fun parsesBundledStreamsCatalog() {
        val file = File("../../streams.json")
        assertTrue("streams.json should exist at repo root", file.exists())
        val streams = WorldCams.parse(file.readText())
        assertTrue("should parse curated streams", streams.size >= 20)

        val newcastle = streams.first { it.name.contains("Newcastle") }
        assertEquals("youtube_channel", newcastle.type)
        assertTrue(newcastle.embedUrl().contains("embed/live_stream?channel=UCTGhjtFloAPlbLy4_yrkoWg"))
        assertTrue(newcastle.embedUrl().contains("autoplay=1&mute=1"))

        val nasa = streams.first { it.name.contains("NASA") }
        assertEquals("youtube_multi", nasa.type)
        assertTrue(nasa.embedUrl().contains("embed/ROboUHfPUrU?"))
    }

    @Test fun extractsVideoIdsFromAllYoutubeFormats() {
        assertEquals("dypAtzvl24s", WorldCams.extractVideoId("https://www.youtube.com/watch?v=dypAtzvl24s"))
        assertEquals("dfVK7ld38Ys", WorldCams.extractVideoId("https://www.youtube.com/live/dfVK7ld38Ys?si=abc"))
        assertEquals("ROboUHfPUrU", WorldCams.extractVideoId("https://youtu.be/ROboUHfPUrU"))
    }
}
