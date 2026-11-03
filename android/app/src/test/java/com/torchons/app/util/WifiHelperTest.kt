package com.torchons.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home decision of WifiHelper.isAtHome (bug of 2026-11-03: the widget hid home tasks at home).
 *
 * PATHS that read it (callers of WifiHelper.isAtHome, grepped on 2026-11-03):
 *   (1) TaskWidget.observeTasks, in the background ........ COVERED by the pure decision
 *   (2) TaskListViewModel.observeCache, in the foreground .. COVERED by the pure decision
 * non couvert : getCurrentSsid et isOnWifi eux-mêmes, parce qu'ils lisent l'état d'Android
 * (ConnectivityManager, WifiManager), sans double en test JVM ; constatés sur le téléphone.
 */
class WifiHelperTest {
    private val home = setOf("Maison")

    @Test
    fun `an unreadable WiFi name counts as home`() {
        // What the widget gets in the background: on WiFi, SSID hidden without location access.
        assertTrue(WifiHelper.isAtHome(home, onWifi = true, ssid = null))
    }

    @Test
    fun `off WiFi is away`() {
        assertFalse(WifiHelper.isAtHome(home, onWifi = false, ssid = null))
    }

    @Test
    fun `another readable WiFi is away, the home one is home`() {
        assertFalse(WifiHelper.isAtHome(home, onWifi = true, ssid = "Café"))
        assertTrue(WifiHelper.isAtHome(home, onWifi = true, ssid = "maison"))
    }

    @Test
    fun `no home WiFi configured is always home`() {
        assertTrue(WifiHelper.isAtHome(emptySet(), onWifi = false, ssid = null))
    }
}
