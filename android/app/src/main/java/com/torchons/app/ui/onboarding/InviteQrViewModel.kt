package com.torchons.app.ui.onboarding

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
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
 */

data class InviteQrState(
    val inviteToken: String? = null,
    val expiresAt: String? = null,
    val foyerName: String? = null,
    /** Mode actuel : "foyer_join" ou "add_device" (echo du backend). */
    val kind: String = "foyer_join",
    val isLoading: Boolean = false,
    val error: UserError? = null,
)

@HiltViewModel
class InviteQrViewModel @Inject constructor(
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
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
                    val err = UserErrors.fromResponse(context, resp)
                    _uiState.update { it.copy(isLoading = false, error = err) }
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = UserError(context.getString(R.string.common_empty_response)),
                        )
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
                val err = UserErrors.from(context, e)
                _uiState.update { it.copy(isLoading = false, error = err) }
            }
        }
    }
}
