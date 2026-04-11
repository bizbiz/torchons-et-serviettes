package com.torchons.app.ui.components

import androidx.lifecycle.ViewModel
import com.torchons.app.core.network.BackendHealthMonitor
import com.torchons.app.core.network.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel partage qui expose l'etat de connexion au backend a l'UI.
 *
 * Tres simple wrapper autour du [BackendHealthMonitor] singleton, fait
 * pour etre injecte via hiltViewModel() dans n'importe quel composable
 * (notamment [OfflineBanner]).
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val monitor: BackendHealthMonitor,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = monitor.state

    /** Force un retry immediat (bouton "Reessayer" du bandeau). */
    fun retry() {
        monitor.retryNow()
    }
}
