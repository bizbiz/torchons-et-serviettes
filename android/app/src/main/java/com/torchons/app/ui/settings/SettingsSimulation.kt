package com.torchons.app.ui.settings

import android.util.Log
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Catch-up simulation data (ProjectionSimulationDialog) of the settings
 * screens, split out of SettingsViewModel, which re-exposes these flows and
 * keeps the public methods.
 *
 * `internal` because SettingsViewModel is its only user.
 */
internal class SettingsSimulation(
    private val scope: CoroutineScope,
    private val apiService: ApiService,
) {

    // --- Simulation rattrapage (reutilise ProjectionSimulationDialog) ---
    // Les memes endpoints que HomeViewModel.refreshProjection(). Le flow
    // "Proposer au foyer" (live FCM) reste pilote par HomeViewModel : ici
    // on supporte uniquement "Juste pour moi" (cf P1, design decision 7).

    private val _simPace = MutableStateFlow<PaceResponse?>(null)
    val simPace: StateFlow<PaceResponse?> = _simPace.asStateFlow()

    private val _simRetard = MutableStateFlow<RetardResponse?>(null)
    val simRetard: StateFlow<RetardResponse?> = _simRetard.asStateFlow()

    private val _simMaintenance = MutableStateFlow<MaintenanceRateResponse?>(null)
    val simMaintenance: StateFlow<MaintenanceRateResponse?> = _simMaintenance.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    /** Charge les 3 endpoints projection (pace/retard/maintenance) + l'id
     *  du user courant, pour alimenter ProjectionSimulationDialog. */
    fun loadSimulationData() {
        scope.launch {
            try {
                val meResp = apiService.getMe()
                if (meResp.isSuccessful) _currentUserId.value = meResp.body()?.id
            } catch (_: Exception) {}
            try {
                val r = apiService.getRetard()
                if (r.isSuccessful) _simRetard.value = r.body()
            } catch (_: Exception) {}
            try {
                val p = apiService.getPace()
                if (p.isSuccessful) _simPace.value = p.body()
            } catch (_: Exception) {}
            try {
                val m = apiService.getMaintenanceRate()
                if (m.isSuccessful) _simMaintenance.value = m.body()
            } catch (_: Exception) {}
        }
    }

    /** Sauve l'objectif perso de l'user courant (bouton "Juste pour moi"
     *  du dialog de simulation). Refresh les donnees apres. */
    fun saveObjective(minutes: Int?, unit: String?) {
        scope.launch {
            try {
                apiService.updateObjective(
                    ObjectiveUpdate(dailyObjectiveMinutes = minutes, displayUnit = unit),
                )
                loadSimulationData()
            } catch (e: Exception) {
                Log.e("SettingsVM", "saveObjective failed", e)
            }
        }
    }
}
