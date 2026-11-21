// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    @Test fun keyIdentityPrefersKeyCodeAndFallsBackToScanCode() {
        assertEquals("k:176", RemoteKeys.keyId(KeyEvent.KEYCODE_SETTINGS, 139))
        assertEquals("k:4062", RemoteKeys.keyId(4062, 705))
        assertEquals("s:705", RemoteKeys.keyId(KeyEvent.KEYCODE_UNKNOWN, 705))
        assertNull(RemoteKeys.keyId(0, 0))
        assertEquals(4062, RemoteKeys.keyCodeOf("k:4062"))
        assertEquals(0, RemoteKeys.keyCodeOf("s:705"))
        assertEquals(705, RemoteKeys.scanCodeOf("s:705"))
        assertTrue(RemoteKeys.validId("k:176"))
        assertTrue(RemoteKeys.validId("s:705"))
        assertFalse(RemoteKeys.validId("k:0"))
        assertFalse(RemoteKeys.validId("k:999999999999"))
        assertFalse(RemoteKeys.validId("bad"))
        assertEquals(4062, RemoteKeys.slot(4062, 705))
        assertEquals(-705, RemoteKeys.slot(0, 705))
        assertEquals(0, RemoteKeys.slot(0, 0))
        assertEquals(4062, RemoteKeys.slotOf("k:4062"))
        assertEquals(-705, RemoteKeys.slotOf("s:705"))
    }

    @Test fun essentialNavigationAndHardwareKeysAreBlocked() {
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_HOME))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_BACK))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_POWER))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(RemoteKeys.blocked(KeyEvent.KEYCODE_VOLUME_MUTE))

        assertFalse(RemoteKeys.blocked(KeyEvent.KEYCODE_SETTINGS))
        assertFalse(RemoteKeys.blocked(KeyEvent.KEYCODE_PROG_RED))
        assertFalse(RemoteKeys.blocked(KeyEvent.KEYCODE_BUTTON_1))
        assertFalse(RemoteKeys.blocked(4062))

        assertTrue(RemoteKeys.passThroughInCapture(KeyEvent.KEYCODE_BACK))
        assertTrue(RemoteKeys.passThroughInCapture(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(RemoteKeys.passThroughInCapture(KeyEvent.KEYCODE_HOME))
        assertFalse(RemoteKeys.passThroughInCapture(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun defaultLabelsAreReadable() {
        assertEquals("Settings button", RemoteKeys.defaultKeyLabel("k:" + KeyEvent.KEYCODE_SETTINGS))
        assertEquals("Red button", RemoteKeys.defaultKeyLabel("k:" + KeyEvent.KEYCODE_PROG_RED))
        assertEquals("Button 3", RemoteKeys.defaultKeyLabel("k:" + KeyEvent.KEYCODE_BUTTON_3))
        assertEquals("Remote button #4062", RemoteKeys.defaultKeyLabel("k:4062"))
        assertEquals("Remote button (scan 705)", RemoteKeys.defaultKeyLabel("s:705"))
    }

    @Test fun targetsValidate() {
        assertTrue(RemoteKeys.validTarget(RemoteKeys.ACTION_HOME))
        assertTrue(RemoteKeys.validTarget(RemoteKeys.ACTION_SETTINGS))
        assertTrue(RemoteKeys.validTarget(RemoteKeys.ACTION_PICTURE))
        assertTrue(RemoteKeys.validTarget(RemoteKeys.ACTION_NOOP))
        assertTrue(RemoteKeys.validTarget(RemoteKeys.appTarget("com.plexapp.android")))
        assertFalse(RemoteKeys.validTarget("app:"))
        assertFalse(RemoteKeys.validTarget("action:bogus"))
        assertFalse(RemoteKeys.validTarget(""))
    }

    @Test fun mergeServicePreservesOtherServicesAndDeduplicates() {
        val svc = RemoteKeys.SERVICE
        assertEquals(svc, RemoteKeys.mergeService("null", svc, true))
        assertEquals(svc, RemoteKeys.mergeService("", svc, true))
        assertEquals("com.other/.Svc:$svc", RemoteKeys.mergeService("com.other/.Svc", svc, true))
        assertEquals("com.other/.Svc:$svc", RemoteKeys.mergeService("com.other/.Svc:au.internal.home/.RemoteKeyService", svc, true))
        assertEquals("com.other/.Svc", RemoteKeys.mergeService("com.other/.Svc:$svc", svc, false))
        assertEquals("", RemoteKeys.mergeService(svc, svc, false))
    }
}
