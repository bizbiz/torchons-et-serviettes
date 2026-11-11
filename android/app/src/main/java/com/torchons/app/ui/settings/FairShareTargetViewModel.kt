package com.torchons.app.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.R
import com.torchons.app.data.api.FairShareSaveResult
import com.torchons.app.data.api.FairShareTargetApi
import com.torchons.app.data.local.TokenManager
import com.torchons.app.worker.cacheMyTargetRatio
import com.torchons.app.data.api.loadHistorySafe
import com.torchons.app.data.api.loadTargetsSafe
import com.torchons.app.data.api.saveTargetsSafe
import com.torchons.app.data.model.FairShareHistoryEntry
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.FairShareTargetItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a load stands. [UNAVAILABLE] is final until a retry: it never spins. */
enum class FairShareLoad { LOADING, READY, UNAVAILABLE }

/** One row of the block: a member and the text of their field. */
data class FairShareMember(val userId: String, val name: String, val input: String)

data class FairShareTargetUiState(
    val load: FairShareLoad = FairShareLoad.LOADING,
    val members: List<FairShareMember> = emptyList(),
    val saving: Boolean = false,
    @StringRes val messageRes: Int? = null,
    val messageIsError: Boolean = false,
)

data class FairShareHistoryUiState(
    val open: Boolean = false,
    val load: FairShareLoad = FairShareLoad.LOADING,
    val entries: List<FairShareHistoryEntry> = emptyList(),
)

/**
 * Target share of each member, edited from the household screen.
 *
 * ONLINE only (server authoritative, no Room cache): offline, the block says so and offers
 * a retry instead of waiting for an answer.
 */
@HiltViewModel
class FairShareTargetViewModel @Inject constructor(
    private val api: FairShareTargetApi,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _state = MutableStateFlow(FairShareTargetUiState())
    val state: StateFlow<FairShareTargetUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(FairShareHistoryUiState())
    val history: StateFlow<FairShareHistoryUiState> = _history.asStateFlow()

    init {
        load()
    }

    /** Reads the targets. The block shows a spinner only on the first read. */
    fun load() {
        viewModelScope.launch {
            if (_state.value.load != FairShareLoad.READY) {
                _state.update { it.copy(load = FairShareLoad.LOADING) }
            }
            val statuses = api.loadTargetsSafe()
            if (statuses == null) {
                // Keep what is on screen after a failed refresh; only a first read fails the block.
                _state.update {
                    if (it.load == FairShareLoad.READY) it else it.copy(load = FairShareLoad.UNAVAILABLE)
                }
            } else {
                // The daily reminder reads my share offline: refresh its cache.
                tokenManager.cacheMyTargetRatio(statuses)
                _state.update { it.copy(load = FairShareLoad.READY, members = membersOf(statuses)) }
            }
        }
    }

    /** Called when the screen comes back: a block that opened offline tries again. */
    fun retryIfUnavailable() {
        if (_state.value.load == FairShareLoad.UNAVAILABLE) load()
    }

    fun setInput(userId: String, raw: String) {
        val text = filterPercentInput(raw)
        _state.update { st ->
            st.copy(
                members = st.members.map { if (it.userId == userId) it.copy(input = text) else it },
                messageRes = null,
            )
        }
    }

    fun save() {
        val current = _state.value
        if (!canSaveShares(totalPercent(current.members.map { it.input }), current.saving)) return
        val items = current.members.map {
            FairShareTargetItem(it.userId, percentToRatio(percentOf(it.input)))
        }
        _state.update { it.copy(saving = true, messageRes = null) }
        viewModelScope.launch {
            when (api.saveTargetsSafe(items)) {
                FairShareSaveResult.SAVED -> {
                    _state.update {
                        it.copy(saving = false, messageRes = R.string.fair_share_target_saved, messageIsError = false)
                    }
                    load()
                }
                FairShareSaveResult.REJECTED -> {
                    // Most likely a member joined or left meanwhile: read the list again.
                    _state.update {
                        it.copy(saving = false, messageRes = R.string.fair_share_target_rejected, messageIsError = true)
                    }
                    load()
                }
                FairShareSaveResult.OFFLINE -> _state.update {
                    it.copy(saving = false, messageRes = R.string.fair_share_target_offline_save, messageIsError = true)
                }
            }
        }
    }

    fun openHistory() {
        _history.value = FairShareHistoryUiState(open = true, load = FairShareLoad.LOADING)
        viewModelScope.launch {
            val entries = api.loadHistorySafe()
            _history.update {
                if (!it.open) it
                else if (entries == null) it.copy(load = FairShareLoad.UNAVAILABLE)
                else it.copy(load = FairShareLoad.READY, entries = entries)
            }
        }
    }

    fun closeHistory() {
        _history.value = FairShareHistoryUiState()
    }

    private fun membersOf(statuses: List<FairShareStatus>): List<FairShareMember> {
        val sorted = statuses.sortedBy { it.displayName.lowercase() }
        val percents = initialPercents(sorted.map { it.targetRatio })
        return sorted.mapIndexed { i, s -> FairShareMember(s.userId, s.displayName, percents[i].toString()) }
    }
}
