package com.torchons.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.BackendHealthMonitor
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val monitor: BackendHealthMonitor,
    private val homeRepository: HomeRepository,
    pendingDao: PendingOperationDao,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = monitor.state

    val pendingOpsCount: StateFlow<Int> = pendingDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _cacheTimestamp = MutableStateFlow<Long?>(null)
    val cacheTimestamp: StateFlow<Long?> = _cacheTimestamp.asStateFlow()

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Offline) {
                    _cacheTimestamp.value = homeRepository.getHomeSnapshotAge()
                }
            }
        }
    }

    fun retry() {
        monitor.retryNow()
    }
}
