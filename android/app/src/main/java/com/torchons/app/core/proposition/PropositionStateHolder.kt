package com.torchons.app.core.proposition

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.PropositionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Etat partage des propositions d'objectifs (v0.8.1 Feature 3).
 *
 * Centralise la proposition active pour permettre au FCM handler (service)
 * et a l'UI (HomeViewModel / HomeScreen) de partager le meme etat. Quand une
 * proposition arrive (FCM data message), on fetch depuis le backend et on
 * push dans les StateFlows. L'UI observe et reagit (affiche le modal receveur,
 * l'ecran d'attente proposer, ou la celebration).
 *
 * Flows exposes :
 * - `currentProposition` : la proposition en cours d'interaction (celle affichee
 *   dans les modals, cote proposer ou receveur). Null quand aucune.
 * - `celebration` : set quand tous les users ont accepted. L'UI affiche alors
 *   la popup de felicitations (confetti + emoji). Se clear apres dismiss.
 * - `error` : message d'erreur transitoire (decline, expiration, ...)
 */
@Singleton
class PropositionStateHolder @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentProposition = MutableStateFlow<PropositionView?>(null)
    val currentProposition: StateFlow<PropositionView?> = _currentProposition.asStateFlow()

    private val _celebration = MutableStateFlow<PropositionView?>(null)
    val celebration: StateFlow<PropositionView?> = _celebration.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Set initial value apres creation par le proposer (retour synchrone de POST /create). */
    fun setCurrent(proposition: PropositionView?) {
        _currentProposition.value = proposition
    }

    fun dismissError() { _error.value = null }
    fun dismissCelebration() { _celebration.value = null }

    /** Appelee par le receveur quand il accepte ou refuse. */
    fun respond(propositionId: String, accepted: Boolean) {
        scope.launch {
            runCatching {
                val resp = apiService.respondToProposition(
                    propositionId,
                    com.torchons.app.data.model.PropositionResponseBody(accepted),
                )
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) {
                        handleStatus(body)
                    }
                } else if (resp.code() == 410) {
                    _currentProposition.value = null
                    _error.value = "Proposition expiree"
                }
            }.onFailure { _error.value = it.message }
        }
    }

    /** Annuler la proposition en cours (proposer uniquement). */
    fun cancel(propositionId: String) {
        scope.launch {
            runCatching {
                val resp = apiService.cancelProposition(propositionId)
                if (resp.isSuccessful) {
                    _currentProposition.value = null
                }
            }.onFailure { _error.value = it.message }
        }
    }

    /** Appelee par FCM handler quand un message propositions arrive. */
    fun handleFcmEvent(type: String, propositionId: String?) {
        scope.launch {
            when (type) {
                "objective_proposition_created" -> {
                    // Receveur : il faut afficher le modal accept/decline
                    refreshFromPending()
                }
                "objective_proposition_progress",
                "objective_proposition_declined",
                "objective_proposition_expired" -> {
                    // Proposer ou tous : refresh le detail
                    if (propositionId != null) {
                        refreshById(propositionId)
                    }
                }
                "objective_proposition_cancelled" -> {
                    // Le proposer a annule : tous les users ferment leur modal
                    _currentProposition.value = null
                    _error.value = "Proposition annulee"
                }
                "objective_proposition_accepted_all" -> {
                    if (propositionId != null) {
                        val fetched = fetchById(propositionId)
                        if (fetched != null) {
                            _celebration.value = fetched
                            _currentProposition.value = null
                        }
                    }
                }
            }
        }
    }

    /** Fallback polling : appele au LifecycleResume pour recuperer un pending perdu. */
    suspend fun refreshFromPending() {
        runCatching {
            // Pas de proposition si pas connecte (evite le 401 bruyant au boot)
            val token = tokenManager.tokenFlow.first()
            if (token.isNullOrBlank()) return@runCatching
            val resp = apiService.getPendingProposition()
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body != null) {
                    handleStatus(body)
                }
            }
        }
    }

    private suspend fun refreshById(propositionId: String) {
        val fetched = fetchById(propositionId)
        if (fetched != null) {
            handleStatus(fetched)
        }
    }

    private suspend fun fetchById(propositionId: String): PropositionView? {
        return runCatching {
            val resp = apiService.getProposition(propositionId)
            if (resp.isSuccessful) resp.body() else null
        }.getOrNull()
    }

    private fun handleStatus(prop: PropositionView) {
        when (prop.status) {
            "pending" -> _currentProposition.value = prop
            "accepted" -> {
                _celebration.value = prop
                _currentProposition.value = null
            }
            "declined" -> {
                _currentProposition.value = null
                _error.value = "Proposition refusee"
            }
            "expired" -> {
                _currentProposition.value = null
                _error.value = "Proposition expiree"
            }
            "cancelled" -> {
                _currentProposition.value = null
                _error.value = "Proposition annulee"
            }
        }
    }
}
