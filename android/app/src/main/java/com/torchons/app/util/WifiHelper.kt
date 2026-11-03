package com.torchons.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/**
 * Helper pour detecter le SSID WiFi actuel.
 * Necessite la permission ACCESS_FINE_LOCATION sur Android 10+.
 */
object WifiHelper {

    /** Retourne le SSID du WiFi connecte, ou null si pas connecte ou pas de permission. */
    fun getCurrentSsid(context: Context): String? {
        // Verifier la permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        // Verifier qu'on est en WiFi
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        // Lire le SSID
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo ?: return null
        val ssid = info.ssid ?: return null

        // Android entoure le SSID de guillemets
        return ssid.removeSurrounding("\"").let {
            if (it == "<unknown ssid>") null else it
        }
    }

    /**
     * False only when the phone is known to be away from home: off WiFi, or on a WiFi whose
     * name is readable and not in the list.
     *
     * A WiFi whose name cannot be read counts as home. Android hides the SSID without location
     * access, and the app only has it in the foreground: the widget, drawn in the background,
     * always read null, and hid the "on site" tasks at home (Pixel 11, 2026-11-03, appops
     * FINE_LOCATION rejected). Being on WiFi at all needs no location.
     */
    fun isAtHome(context: Context, homeSSIDs: Set<String>): Boolean =
        isAtHome(homeSSIDs, isOnWifi(context), getCurrentSsid(context))

    /** The decision of [isAtHome], without Android: unit-tested in WifiHelperTest. */
    fun isAtHome(homeSSIDs: Set<String>, onWifi: Boolean, ssid: String?): Boolean = when {
        homeSSIDs.isEmpty() -> true // Not configured: always at home
        !onWifi -> false
        ssid == null -> true
        else -> homeSSIDs.any { it.equals(ssid, ignoreCase = true) }
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
