package com.torchons.app.core.proposition

import android.content.Context
import com.torchons.app.R
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PropositionCreate
import com.torchons.app.data.model.PropositionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentProposition = MutableStateFlow<PropositionView?>(null)
    val currentProposition: StateFlow<PropositionView?> = _currentProposition.asStateFlow()

    private val _celebration = MutableStateFlow<PropositionView?>(null)
    val celebration: StateFlow<PropositionView?> = _celebration.asStateFlow()

    private val _error = MutableStateFlow<UserError?>(null)
    val error: StateFlow<UserError?> = _error.asStateFlow()

    /** Set initial value apres creation par le proposer (retour synchrone de POST /create). */
    fun setCurrent(proposition: PropositionView?) {
        _currentProposition.value = proposition
    }

    /**
     * Proposes a goal to the foyer (live flow). Shared by the home screen and the
     * Foyer screen, so both "Propose to household" buttons run the same code. The
     * proposition shows on the home tab (see FollowLiveProposition); failures land
     * in [error].
     */
    fun propose(sliderValues: Map<String, Int>, unit: String) {
        scope.launch {
            runCatching {
                // Save the member's display unit first, as the home flow always did.
                apiService.updateObjective(ObjectiveUpdate(displayUnit = unit))
                val resp = apiService.createProposition(PropositionCreate(sliderValues))
                if (!resp.isSuccessful) throw ApiHttpException.from(resp)
                resp.body()?.let { _currentProposition.value = it }
            }.onFailure { _error.value = UserErrors.from(context, it) }
        }
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
                    _error.value = UserError(context.getString(R.string.proposition_expired))
                } else {
                    // Any other refusal (400, 403, 404, 5xx...) is told, not swallowed (D7): the
                    // answer did not go through and the member must know to try again.
                    _error.value = UserErrors.fromResponse(context, resp)
                }
            }.onFailure { _error.value = UserErrors.from(context, it) }
        }
    }

    /** Annuler la proposition en cours (proposer uniquement). */
    fun cancel(propositionId: String) {
        scope.launch {
            runCatching {
                val resp = apiService.cancelProposition(propositionId)
                if (resp.isSuccessful) {
                    _currentProposition.value = null
                } else {
                    // 409: it was already answered or expired. Show its real outcome
                    // instead of leaving the waiting dialog stuck.
                    refreshById(propositionId)
                }
            }.onFailure { _error.value = UserErrors.from(context, it) }
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
                    _error.value = UserError(context.getString(R.string.proposition_cancelled))
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
                val shown = _currentProposition.value
                if (body != null) {
                    handleStatus(body)
                } else if (shown != null) {
                    // Nothing pending any more, yet a dialog is open: the proposition
                    // was answered, declined or expired while we missed the push.
                    // Fetch its final state (celebration, or the reason it ended).
                    refreshById(shown.id)
                }
            }
        }
    }

    /**
     * Polls the server while the app is visible (KeepLiveStateFresh runs it under
     * repeatOnLifecycle(STARTED)). Push stays the fast path; this is what makes the
     * flow work without it: a dialog follows its proposition every few seconds, and a
     * new proposition from another member shows up within [IDLE_TICKS] ticks (16 s).
     */
    suspend fun pollWhileVisible() {
        // One tick every LIVE_POLL_MS: a dialog opened mid-wait (the user just
        // proposed) is followed from the next tick, not after a full idle wait.
        var ticksSincePending = IDLE_TICKS
        while (true) {
            val shown = _currentProposition.value
            if (shown != null) {
                refreshById(shown.id)
            } else if (ticksSincePending >= IDLE_TICKS) {
                refreshFromPending()
                ticksSincePending = 0
            }
            ticksSincePending++
            delay(LIVE_POLL_MS)
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
                _error.value = UserError(context.getString(R.string.proposition_declined))
            }
            "expired" -> {
                _currentProposition.value = null
                _error.value = UserError(context.getString(R.string.proposition_expired))
            }
            "cancelled" -> {
                _currentProposition.value = null
                _error.value = UserError(context.getString(R.string.proposition_cancelled))
            }
        }
    }

    private companion object {
        /** While a dialog is open: the 3-minute window needs a quick follow-up. */
        const val LIVE_POLL_MS = 4_000L

        /** Otherwise: one GET /pending every 4 ticks (16 s) while the app is in the foreground. */
        const val IDLE_TICKS = 4
    }
}
