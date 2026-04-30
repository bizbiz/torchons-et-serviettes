package com.torchons.app.ui.onboarding

import androidx.lifecycle.SavedStateHandle
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
 * ViewModel pour l'ecran "Inviter quelqu'un" (v0.30 Phase B6) et "Ajouter
 * un de mes appareils" (v0.31).
 *
 * Le mode est determine par l'argument de nav `addDevice` :
 *  - false (defaut) -> POST /api/pairing/init pour creer un QR foyer_join
 *    qui ajoutera un nouveau membre.
 *  - true -> POST /api/pairing/init-add-device pour creer un QR add_device
 *    qui ajoutera un nouvel appareil au compte de l'user courant.
 *
 * Cf. docs/design-onboarding-public.md §3 et §2 cas (B).
 */

data class InviteQrState(
    val inviteToken: String? = null,
    val expiresAt: String? = null,
    val foyerName: String? = null,
    /** Mode actuel : "foyer_join" ou "add_device" (echo du backend). */
    val kind: String = "foyer_join",
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class InviteQrViewModel @Inject constructor(
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Lu depuis l'argument de nav route. Vrai si on est en mode "ajouter
     * un de mes appareils". False (defaut) pour "inviter quelqu'un".
     */
    private val addDevice: Boolean =
        savedStateHandle.get<Boolean>("addDevice") ?: false

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
                val resp = if (addDevice) apiService.pairingInitAddDevice()
                else apiService.pairingInit()
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
                        kind = body.kind,
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
