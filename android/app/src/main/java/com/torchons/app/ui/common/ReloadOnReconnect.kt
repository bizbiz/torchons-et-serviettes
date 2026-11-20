package com.torchons.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * True when a screen that shows a load error must load again: the app has just GONE from offline
 * to online (a transition, not a state) and the screen is in error.
 *
 * A transition on purpose: keyed on "online and in error" it would reload for ever while the
 * server answers an error that is not a network one (a 500 puts the screen in error again, which
 * would reload again).
 */
internal fun shouldReloadOnReconnect(wasOnline: Boolean, isOnline: Boolean, hasError: Boolean): Boolean =
    !wasOnline && isOnline && hasError

/**
 * Emulator finding of 2026-11-20: "Mes appareils" and the Stats cards kept "Impossible de joindre
 * le serveur" after the network was back, until the user found "Réessayer" (the Stats cards have
 * none). Put this next to the error block of a screen: when the app comes back online
 * ([LocalIsOnline], from `BackendHealthMonitor`) and [hasError] is true, [reload] runs once.
 */
@Composable
fun ReloadOnReconnect(hasError: Boolean, reload: () -> Unit) {
    val isOnline = LocalIsOnline.current
    var wasOnline by remember { mutableStateOf(isOnline) }
    val errorNow by rememberUpdatedState(hasError)
    val reloadNow by rememberUpdatedState(reload)
    LaunchedEffect(isOnline) {
        if (shouldReloadOnReconnect(wasOnline, isOnline, errorNow)) reloadNow()
        wasOnline = isOnline
    }
}
