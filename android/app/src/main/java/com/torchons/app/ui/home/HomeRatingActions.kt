package com.torchons.app.ui.home

import android.app.Application
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.data.repository.VoteRepository
import com.torchons.app.ui.common.OnlineResult
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.ui.common.runOnline
import com.torchons.app.util.PopupSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The rating pop-up (affinity, effort, frequency) and the personal objective of the home screen,
 * split out of HomeViewModel. Reads and writes the ViewModel's [uiState] (passed in, never
 * exposed); [refreshProjection] reloads the projection cards after an objective changed.
 *
 * `internal` because HomeViewModel is its only user.
 */
internal class HomeRatingActions(
    private val scope: CoroutineScope,
    private val application: Application,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val apiService: ApiService,
    private val votes: VoteRepository,
    private val taskRepository: TaskRepository,
    private val homeRepository: HomeRepository,
    private val tokenManager: TokenManager,
    private val ratingCadence: RatingCadence,
    private val popupSessionGate: PopupSessionGate,
    private val refresh: () -> Unit,
    private val refreshProjection: suspend () -> Unit,
) {
    private fun showError(error: UserError) {
        uiState.value = uiState.value.copy(error = error)
    }

    private fun errorOf(t: Throwable) = UserErrors.from(application, t)

    /**
     * Save l'objectif perso de l'user courant (appele depuis le popup simulation mode "Juste pour
     * moi"). Online-only: a refusal or a network failure is told (D7), the cards reload on success.
     */
    fun saveObjective(minutes: Int?, unit: String?) {
        scope.launch {
            val result = runOnline(::errorOf) {
                apiService.updateObjective(ObjectiveUpdate(dailyObjectiveMinutes = minutes, displayUnit = unit))
            }
            when (result) {
                is OnlineResult.Done -> refreshProjection()
                is OnlineResult.Failed -> showError(result.error)
            }
        }
    }

    /** Saves the rating and closes the pop-up; a refused vote is shown and the pop-up stays open. */
    fun submitRating(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        scope.launch {
            try {
                saveRatingData(votes, taskRepository, taskId, affinity, effortPoints, recurrence)
                popupSessionGate.disarm()
                uiState.value = uiState.value.copy(showAffinityPopup = false)
                ratingCadence.onRated()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(errorOf(e))
            }
        }
    }

    /**
     * Saves the rating, then opens the next task to rate. The vote is saved (or queued) before
     * the next one is looked up: when that lookup fails (offline), the pop-up simply closes, the
     * vote is not lost and nothing is reported as an error.
     */
    fun submitRatingAndNext(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        scope.launch {
            try {
                saveRatingData(votes, taskRepository, taskId, affinity, effortPoints, recurrence)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(errorOf(e))
                return@launch
            }
            uiState.value = uiState.value.copy(showAffinityPopup = false)
            ratingCadence.onRated()
            val next = try {
                nextTaskToRate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            uiState.value = if (next != null) {
                uiState.value.copy(pendingAffinity = next, showAffinityPopup = true)
            } else {
                uiState.value.copy(error = UserError(application.getString(R.string.home_all_rated)))
            }
        }
    }

    /** Unrated tasks first, then the oldest rating (re-rating); throws when the server is unreachable. */
    private suspend fun nextTaskToRate(): PendingAffinity? {
        homeRepository.refreshPendingAffinity(tokenManager.affinityExpiryFlow.first())
        val unrated = homeRepository.observePendingAffinity().first()?.firstOrNull()
        if (unrated != null) return unrated
        val resp = apiService.getOldestRating()
        return if (resp.isSuccessful) resp.body()?.firstOrNull() else null
    }

    /**
     * Opens the pop-up on the oldest rating. Needs the server (the button is disabled offline):
     * a failure is shown instead of leaving the tap without effect (D7).
     */
    fun showManualRating() {
        scope.launch {
            val result = runOnline(::errorOf) { apiService.getOldestRating() }
            if (result is OnlineResult.Failed) return@launch showError(result.error)
            val task = (result as OnlineResult.Done).body?.firstOrNull()
            if (task != null) {
                popupSessionGate.arm()
                ratingCadence.onManualOpened()
                uiState.value = uiState.value.copy(pendingAffinity = task, showAffinityPopup = true)
            } else {
                showError(UserError(application.getString(R.string.home_all_rated)))
            }
        }
    }
}
