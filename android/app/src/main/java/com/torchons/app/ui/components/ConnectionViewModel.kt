package com.torchons.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.BackendHealthMonitor
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val monitor: BackendHealthMonitor,
    private val homeRepository: HomeRepository,
    private val syncTrigger: SyncTrigger,
    pendingDao: PendingOperationDao,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = monitor.state

    val pendingOpsCount: StateFlow<Int> = pendingDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Pending offline ops with their retry metadata (attemptCount, lastError). */
    val pendingOps: StateFlow<List<PendingOperationEntity>> = pendingDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cacheTimestamp = MutableStateFlow<Long?>(null)
    val cacheTimestamp: StateFlow<Long?> = _cacheTimestamp.asStateFlow()

    /**
     * Result of the two-probe connectivity diagnosis run when the sync
     * dialog opens while offline: is the internet reachable at all, and
     * does the backend answer /api/health? Distinguishes "your connection
     * is down" from "the server is down". Null = not run / running.
     */
    data class SyncDiagnosis(val internetOk: Boolean, val serverOk: Boolean)

    private val _diagnosis = MutableStateFlow<SyncDiagnosis?>(null)
    val diagnosis: StateFlow<SyncDiagnosis?> = _diagnosis.asStateFlow()

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Offline) {
                    _cacheTimestamp.value = homeRepository.getHomeSnapshotAge()
                }
            }
        }
    }

    /** Probes internet (captive-portal endpoint) then the backend health. */
    fun runDiagnosis() {
        viewModelScope.launch {
            _diagnosis.value = null
            val internetOk = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(INTERNET_PROBE_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    val ok = conn.responseCode in 200..399
                    conn.disconnect()
                    ok
                } catch (_: Exception) {
                    false
                }
            }
            val serverOk = monitor.checkNow()
            _diagnosis.value = SyncDiagnosis(internetOk = internetOk, serverOk = serverOk)
        }
    }

    fun clearDiagnosis() {
        _diagnosis.value = null
    }

    /** Retry now: backend health check + immediate sync attempt. */
    fun retrySync() {
        monitor.retryNow()
        syncTrigger.requestSync()
    }

    private companion object {
        /** Google connectivity-check endpoint, returns 204 with no body. */
        const val INTERNET_PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
    }
}
