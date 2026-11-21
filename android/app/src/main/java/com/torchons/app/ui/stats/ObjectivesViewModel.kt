package com.torchons.app.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Objective
import com.torchons.app.data.model.ObjectiveCreateRequest
import com.torchons.app.ui.common.OnlineResult
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.ui.common.runOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel des objectifs (v0.31). Charge la liste + permet de creer/supprimer.
 *
 * Pas d'edit pour la v1 — le user supprime + recree si besoin de modifier.
 */
@HiltViewModel
class ObjectivesViewModel @Inject constructor(
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class State(
        val objectives: List<Objective> = emptyList(),
        val isLoading: Boolean = false,
        val isCreating: Boolean = false,
        val error: UserError? = null,
        /** The LIST could not be loaded (an action error does not set it): no "no objective" text. */
        val loadFailed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null, loadFailed = false) }
        viewModelScope.launch {
            try {
                val resp = apiService.listObjectives()
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            objectives = resp.body() ?: emptyList(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, loadFailed = true,
                            error = UserErrors.fromResponse(context, resp),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, loadFailed = true,
                        error = UserErrors.from(context, e),
                    )
                }
            }
        }
    }

    fun create(title: String, period: String, targetCount: Int) {
        if (_uiState.value.isCreating || title.isBlank()) return
        _uiState.update { it.copy(isCreating = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.createObjective(
                    ObjectiveCreateRequest(
                        title = title.trim(),
                        period = period,
                        targetCount = targetCount,
                        kind = "shared",
                    )
                )
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(isCreating = false) }
                    load()
                } else {
                    _uiState.update {
                        it.copy(isCreating = false,
                            error = UserErrors.fromResponse(context, resp),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCreating = false,
                        error = UserErrors.from(context, e),
                    )
                }
            }
        }
    }

    fun delete(objectiveId: String) {
        viewModelScope.launch {
            // Online-only (the dialog disables the button offline): a failure is shown (D7).
            val result = runOnline({ UserErrors.from(context, it) }) { apiService.deleteObjective(objectiveId) }
            when (result) {
                is OnlineResult.Done -> load()
                is OnlineResult.Failed -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }
}
