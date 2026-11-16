package com.torchons.app.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.api.ModuleGuideApi
import com.torchons.app.data.api.markGuideSeenSafe
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.ModuleView
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the guide shown at app open (module enabled by another member) and
 * posts guide-seen for the prompt path. Home-screen scoped.
 *
 * Every network call is guarded: offline, the guide is simply not shown and
 * the next open retries.
 */
@HiltViewModel
class ModuleGuideViewModel @Inject constructor(
    private val apiService: ApiService,
    private val guideApi: ModuleGuideApi,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _dueGuide = MutableStateFlow<ModuleGuide?>(null)

    // The registry as last served by the open-time GET /api/modules (no extra call).
    private val _modules = MutableStateFlow<List<ModuleView>>(emptyList())

    /** Home notices: collective modules another member turned off, not yet acknowledged. */
    val offNotices: StateFlow<List<ModuleView>> = combine(
        _modules, tokenManager.ackedModuleNoticesFlow, tokenManager.foyerIdFlow,
    ) { modules, acked, foyerId ->
        if (foyerId == null) emptyList()
        else pickModuleOffNotices(modules, foyerId, acked, Instant.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Acknowledge one notice: it will not come back unless the module changes again. */
    fun acknowledgeOffNotice(module: ModuleView) {
        viewModelScope.launch {
            val foyerId = tokenManager.getCachedFoyerId() ?: return@launch
            tokenManager.ackModuleNotice(moduleOffNoticeKey(foyerId, module))
        }
    }

    /** The guide to show, or null. Loaded once per ViewModel (= per app open). */
    val dueGuide: StateFlow<ModuleGuide?> = _dueGuide.asStateFlow()

    init {
        loadDueGuide()
    }

    private fun loadDueGuide() {
        viewModelScope.launch {
            try {
                val resp = apiService.getModules()
                if (resp.isSuccessful) {
                    // promptShowing = false here: the screen decides when to
                    // render it, so it never stacks on a prompt modal.
                    val modules = resp.body()?.modules.orEmpty()
                    _modules.value = modules
                    _dueGuide.value = pickDueGuide(modules, false)?.toGuide()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ModuleGuideViewModel", "loadDueGuide: ${e.message}")
            }
        }
    }

    /** Dismiss the open-time guide and record it as seen. */
    fun dismissDueGuide() {
        val guide = _dueGuide.value ?: return
        _dueGuide.value = null
        markSeen(guide.key)
    }

    private val _settingsGuide = MutableStateFlow<ModuleGuide?>(null)

    /** Guide opened from Settings (manual enable or the (i) button). */
    val settingsGuide: StateFlow<ModuleGuide?> = _settingsGuide.asStateFlow()

    private val seenThisSession = mutableSetOf<String>()

    /** Open a guide from Settings; record it as seen the first time only. */
    fun showSettingsGuide(module: ModuleView) {
        _settingsGuide.value = module.toGuide()
        if (!module.guideSeen && seenThisSession.add(module.key)) markSeen(module.key)
    }

    fun dismissSettingsGuide() { _settingsGuide.value = null }

    /** Record a guide as seen (prompt path). Failure is retried at next occasion. */
    fun markSeen(moduleKey: String) {
        viewModelScope.launch { guideApi.markGuideSeenSafe(moduleKey) }
    }
}
