// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private val validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun parsesValidReleaseManifest() {
        val json = """
            {
              "versionCode": 64,
              "versionName": "2.21.0",
              "url": "https://github.com/george-edwards/TameTheTelly-DIY/releases/download/v2.21.0/home-v2.21.0.apk",
              "sha256": "$validSha",
              "notes": "Self-updater + World Cams"
            }
        """.trimIndent()
        val r = Updater.parse(json)
        assertNotNull(r)
        assertEquals(64, r!!.versionCode)
        assertEquals("2.21.0", r.versionName)
        assertEquals(validSha, r.sha256)
        assertEquals("Self-updater + World Cams", r.notes)
    }

    @Test fun rejectsInsecureOrMalformedManifests() {
        assertNull(Updater.parse("{}"))
        assertNull(Updater.parse("""{"versionCode":64,"versionName":"2.21.0","url":"http://example.com/a.apk","sha256":"$validSha"}"""))
        assertNull(Updater.parse("""{"versionCode":64,"versionName":"2.21.0","url":"https://example.com/a.apk","sha256":"notasha"}"""))
        assertNull(Updater.parse("""{"versionCode":0,"versionName":"2.21.0","url":"https://example.com/a.apk","sha256":"$validSha"}"""))
    }
}
