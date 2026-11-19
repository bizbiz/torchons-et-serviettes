package com.torchons.app.ui.onboarding

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.PairingJoinRequest
import kotlinx.coroutines.flow.first
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.service.TokenRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel du flow "Rejoindre un foyer" (v0.30).
 *
 * Etapes :
 *  - Scan : QR scanne via Quickie -> invite_token capture en state
 *  - Form : saisie display_name + bouton "Rejoindre"
 *  - Submitting : appel /api/pairing/join
 *  - Done  : recovery_code affiche, bouton "Continuer" -> navigate MAIN
 */

data class JoinState(
    val inviteToken: String? = null,
    val displayName: String = "",
    val isSubmitting: Boolean = false,
    val recoveryCode: String? = null,
    val foyerName: String? = null,
    val error: UserError? = null,
    val done: Boolean = false,
    /** v0.31 : "foyer_join" (par defaut) ou "add_device" — connu apres
     * le probe /pairing/info qui suit le scan QR. */
    val kind: String? = null,
)

@HiltViewModel
class JoinFoyerViewModel @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val tokenRegistrar: TokenRegistrar,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinState())
    val uiState: StateFlow<JoinState> = _uiState.asStateFlow()

    fun setInviteToken(value: String) {
        _uiState.update { it.copy(inviteToken = value, error = null) }
        // v0.31 : probe le kind pour adapter l'UI (et auto-submit si add_device).
        probeKind(value)
    }

    private fun probeKind(token: String) {
        viewModelScope.launch {
            try {
                val resp = apiService.pairingInfo(mapOf("invite_token" to token))
                if (!resp.isSuccessful) {
                    val err = UserErrors.fromResponse(context, resp, ::knownInviteError)
                    _uiState.update {
                        it.copy(error = err, inviteToken = null)
                    }
                    return@launch
                }
                val info = resp.body() ?: return@launch
                _uiState.update {
                    it.copy(kind = info.kind, foyerName = info.foyerName)
                }
                // Pour add_device, on peut soumettre directement : pas besoin
                // de display_name, le user existe deja cote backend.
                if (info.kind == "add_device") {
                    submit()
                }
            } catch (_: Exception) {
                // Probe echoue silencieusement : on retombe sur le flow
                // foyer_join classique (display_name demande).
            }
        }
    }

    fun setDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun finish() {
        _uiState.update { it.copy(done = true) }
    }

    fun submit() {
        val s = _uiState.value
        val token = s.inviteToken ?: return
        if (s.isSubmitting || s.done) return
        // Pour foyer_join : display_name requis ; pour add_device : pas requis.
        val isAddDevice = s.kind == "add_device"
        if (!isAddDevice && s.displayName.isBlank()) {
            _uiState.update {
                it.copy(error = UserError(context.getString(R.string.onboarding_error_name_required)))
            }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val deviceId = tokenManager.getOrCreateDeviceId()
                val resp = apiService.pairingJoin(
                    PairingJoinRequest(
                        inviteToken = token,
                        displayName = s.displayName.trim(),
                        deviceId = deviceId,
                    )
                )
                if (!resp.isSuccessful) {
                    val err = UserErrors.fromResponse(context, resp, ::knownInviteError)
                    _uiState.update { it.copy(isSubmitting = false, error = err) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = UserError(context.getString(R.string.common_empty_response)),
                        )
                    }
                    return@launch
                }
                authRepository.saveAuthDirect(
                    token = body.token,
                    userId = body.userId,
                    username = "",
                    displayName = body.displayName.ifBlank { s.displayName.trim() },
                    foyerId = body.foyerId,
                )
                tokenRegistrar.registerFcmToken()
                if (body.kind == "add_device") {
                    // Pas de recovery code a afficher : on passe directement
                    // a l'ecran principal.
                    _uiState.update { it.copy(isSubmitting = false, done = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            recoveryCode = body.recoveryCode,
                            foyerName = body.foyerName,
                        )
                    }
                }
            } catch (e: Exception) {
                val err = UserErrors.from(context, e, ::knownInviteError)
                _uiState.update { it.copy(isSubmitting = false, error = err) }
            }
        }
    }

    /** Server answers this screen can name; anything else goes to the generic translator. */
    private fun knownInviteError(e: ApiHttpException): String? = when (e.httpCode) {
        400 -> context.getString(R.string.onboarding_error_data_invalid)
        404 -> context.getString(R.string.onboarding_error_invite_invalid)
        409 -> context.getString(R.string.onboarding_error_invite_used)
        410 -> context.getString(R.string.onboarding_error_invite_expired)
        else -> null
    }
}
