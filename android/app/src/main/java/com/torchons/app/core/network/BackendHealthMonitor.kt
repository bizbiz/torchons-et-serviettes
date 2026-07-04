package com.torchons.app.core.network

import com.torchons.app.data.api.ApiService
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moniteur de connexion au backend (singleton Hilt, partage par toute l'app).
 *
 * Strategie hybride :
 * 1. Reactif : chaque appel API normal notifie le moniteur via [reportSuccess]
 *    ou [reportFailure] (pousse par [BackendHealthInterceptor]). Cela permet
 *    de basculer Online/Offline immediatement sans attendre un poll.
 * 2. Polling actif : quand on est Offline, on lance une coroutine qui ping
 *    GET /api/health toutes les [POLL_INTERVAL_MS] ms. Le polling s'arrete
 *    automatiquement des qu'on bascule Online (que ce soit via le poll ou
 *    via un appel API qui a finalement reussi).
 *
 * Ce design garantit zero overhead reseau quand tout va bien, et reactivite
 * automatique quand le backend revient.
 *
 * Note d'injection : on injecte [Lazy<ApiService>] pour eviter une dependance
 * circulaire (ApiService -> OkHttp -> BackendHealthInterceptor -> Monitor).
 */
@Singleton
class BackendHealthMonitor @Inject constructor(
    private val apiServiceLazy: Lazy<ApiService>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollMutex = Mutex()
    private var pollJob: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Online)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Notifie qu'un appel API a reussi (HTTP 2xx). */
    fun reportSuccess() {
        if (_state.value !is ConnectionState.Online) {
            _state.value = ConnectionState.Online
            stopPolling()
        }
    }

    /** Notifie qu'un appel API a echoue (exception reseau ou HTTP 5xx). */
    fun reportFailure() {
        if (_state.value !is ConnectionState.Offline) {
            _state.value = ConnectionState.Offline(System.currentTimeMillis())
            startPolling()
        }
    }

    /**
     * Force un check immediat de /api/health. Appele par le bouton "Reessayer"
     * du dialog de statut de synchronisation.
     */
    fun retryNow() {
        scope.launch { ping() }
    }

    /**
     * One-shot health check that reports the result back to the caller
     * (used by the sync-status diagnosis dialog to tell "server down" apart
     * from "internet down"). Also flips the state to Online on success.
     */
    suspend fun checkNow(): Boolean = try {
        val resp = apiServiceLazy.get().health()
        if (resp.isSuccessful) {
            reportSuccess()
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    private fun startPolling() {
        scope.launch {
            pollMutex.withLock {
                if (pollJob?.isActive == true) return@withLock
                pollJob = scope.launch {
                    while (_state.value is ConnectionState.Offline) {
                        delay(POLL_INTERVAL_MS)
                        ping()
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        scope.launch {
            pollMutex.withLock {
                pollJob?.cancel()
                pollJob = null
            }
        }
    }

    private suspend fun ping() {
        try {
            val resp = apiServiceLazy.get().health()
            if (resp.isSuccessful) {
                reportSuccess()
            } else {
                // Code HTTP non-2xx : on reste offline.
            }
        } catch (_: Exception) {
            // Echec reseau : on reste offline.
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }
}
