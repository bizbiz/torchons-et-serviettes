package com.torchons.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel pour l'ecran "Inviter quelqu'un" (v0.30, Phase B6).
 *
 * Genere un invite_token via /api/pairing/init au mount + bouton refresh
 * pour generer un nouveau (apres expiration ou si le QR a ete photographie).
 *
 * Cf. docs/design-onboarding-public.md §3.
 */

data class InviteQrState(
    val inviteToken: String? = null,
    val expiresAt: String? = null,
    val foyerName: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class InviteQrViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteQrState())
    val uiState: StateFlow<InviteQrState> = _uiState.asStateFlow()

    init {
        generate()
    }

    fun generate() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.pairingInit()
                if (!resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Erreur ${resp.code()}",
                        )
                    }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Reponse vide")
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        inviteToken = body.inviteToken,
                        expiresAt = body.expiresAt,
                        foyerName = body.foyerName,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Erreur reseau")
                }
            }
        }
    }
}
