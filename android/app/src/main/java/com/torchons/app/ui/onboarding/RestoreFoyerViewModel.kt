package com.torchons.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.RecoveryRestoreConfirmRequest
import com.torchons.app.data.model.RecoveryRestoreRequest
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
 * ViewModel du flow "Retrouver mon foyer" (v0.30, Phase B5).
 *
 * Etapes :
 *  - Form     : saisie code de recuperation
 *  - Preview  : confirmation visuelle ("C'est bien toi, Quentin ?")
 *  - Done     : JWT recu, navigation vers MAIN
 *
 * Cf. docs/design-onboarding-public.md §5.
 */

data class RestoreState(
    val code: String = "",
    val isSubmitting: Boolean = false,
    val preview: PreviewData? = null,
    val error: String? = null,
    val done: Boolean = false,
)

data class PreviewData(
    val displayName: String,
    val foyerName: String?,
    val role: String?,
    val ratingsCount: Int,
    val completionsCount: Int,
    val restoreToken: String,
)

@HiltViewModel
class RestoreFoyerViewModel @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val tokenRegistrar: TokenRegistrar,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RestoreState())
    val uiState: StateFlow<RestoreState> = _uiState.asStateFlow()

    fun setCode(value: String) {
        _uiState.update { it.copy(code = value, error = null) }
    }

    fun resetPreview() {
        _uiState.update { it.copy(preview = null, error = null) }
    }

    fun lookup() {
        val code = _uiState.value.code
        if (_uiState.value.isSubmitting || code.isBlank()) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.recoveryRestore(RecoveryRestoreRequest(code.trim()))
                if (!resp.isSuccessful) {
                    val msg = if (resp.code() == 404) {
                        "Code introuvable. Verifie qu'il est bien tape."
                    } else "Erreur ${resp.code()}"
                    _uiState.update { it.copy(isSubmitting = false, error = msg) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = "Reponse vide")
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        preview = PreviewData(
                            displayName = body.displayName,
                            foyerName = body.foyerName,
                            role = body.role,
                            ratingsCount = body.ratingsCount,
                            completionsCount = body.completionsCount,
                            restoreToken = body.restoreToken,
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = e.message ?: "Erreur reseau")
                }
            }
        }
    }

    fun confirm() {
        val preview = _uiState.value.preview ?: return
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val deviceId = tokenManager.getOrCreateDeviceId()
                val resp = apiService.recoveryRestoreConfirm(
                    RecoveryRestoreConfirmRequest(
                        restoreToken = preview.restoreToken,
                        deviceId = deviceId,
                    )
                )
                if (!resp.isSuccessful) {
                    val msg = if (resp.code() == 401) {
                        "Le code a expire, recommence."
                    } else "Erreur ${resp.code()}"
                    _uiState.update { it.copy(isSubmitting = false, error = msg) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = "Reponse vide")
                    }
                    return@launch
                }
                authRepository.saveAuthDirect(
                    token = body.token,
                    userId = body.userId,
                    username = "",
                    displayName = preview.displayName,
                    foyerId = body.foyerId,
                )
                tokenRegistrar.registerFcmToken()
                _uiState.update { it.copy(isSubmitting = false, done = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = e.message ?: "Erreur reseau")
                }
            }
        }
    }
}
