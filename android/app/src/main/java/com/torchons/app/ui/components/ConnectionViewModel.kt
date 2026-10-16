package com.torchons.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.BackendHealthMonitor
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.core.sync.PendingOpRow
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.core.sync.describePendingOps
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val taskRepository: TaskRepository,
    pendingDao: PendingOperationDao,
    taskDao: TaskDao,
    userDao: UserDao,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = monitor.state

    val pendingOpsCount: StateFlow<Int> = pendingDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Pending offline ops with their retry metadata (attemptCount, lastError). */
    val pendingOps: StateFlow<List<PendingOperationEntity>> = pendingDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The queue as the dialog lists it: what each op is about, and why it waits. */
    val pendingRows: StateFlow<List<PendingOpRow>> = combine(
        pendingDao.observeAll(),
        taskDao.observeAll(),
        userDao.observeAll(),
    ) { ops, tasks, users ->
        describePendingOps(
            ops,
            taskTitles = tasks.associate { it.id to it.title },
            memberNames = users.associate { it.id to it.displayName },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Gives up on a queued op: it will never be sent, its local effect is undone. */
    fun discardPendingOp(clientOpId: String) {
        viewModelScope.launch { taskRepository.discardPendingOp(clientOpId) }
    }

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
