package com.torchons.app.ui.onboarding

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
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
 *  - Preview  : confirmation visuelle ("C'est bien toi, X ?")
 *  - Done     : JWT recu, navigation vers MAIN
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
    @ApplicationContext private val context: Context,
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
                        context.getString(R.string.onboarding_error_code_not_found)
                    } else context.getString(R.string.common_error_code_format, resp.code())
                    _uiState.update { it.copy(isSubmitting = false, error = msg) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = context.getString(R.string.common_empty_response))
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
                    it.copy(isSubmitting = false, error = e.message ?: context.getString(R.string.common_error_network))
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
                        context.getString(R.string.onboarding_error_code_expired)
                    } else context.getString(R.string.common_error_code_format, resp.code())
                    _uiState.update { it.copy(isSubmitting = false, error = msg) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = context.getString(R.string.common_empty_response))
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
                    it.copy(isSubmitting = false, error = e.message ?: context.getString(R.string.common_error_network))
                }
            }
        }
    }
}
