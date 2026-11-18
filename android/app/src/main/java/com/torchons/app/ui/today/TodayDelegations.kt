package com.torchons.app.ui.today

import com.torchons.app.data.model.Delegation
import com.torchons.app.data.repository.DelegationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The « À faire aujourd'hui » side of a screen's ViewModel (list, detail, home): the cached
 * delegations, by task, and the two writes. Both are offline-first (DelegationRepository).
 */
class TodayDelegations(
    private val scope: CoroutineScope,
    private val repository: DelegationRepository,
) {
    val all: StateFlow<List<Delegation>> = repository.observeActive()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val byTask: StateFlow<Map<String, Delegation>> = repository.observeActive()
        .map { list -> list.associateBy { it.taskId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun refresh(): Boolean = repository.refresh()

    fun hand(taskId: String, recipientIds: List<String>) {
        scope.launch { repository.handTask(taskId, recipientIds) }
    }

    fun cancel(delegation: Delegation) {
        scope.launch { repository.cancel(delegation) }
    }
}
