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

    /** Verifie si le WiFi actuel est un des SSIDs maison. */
    fun isAtHome(context: Context, homeSSIDs: Set<String>): Boolean {
        if (homeSSIDs.isEmpty()) return true // Pas configure = toujours a la maison
        val current = getCurrentSsid(context) ?: return false
        return homeSSIDs.any { it.equals(current, ignoreCase = true) }
    }
}
