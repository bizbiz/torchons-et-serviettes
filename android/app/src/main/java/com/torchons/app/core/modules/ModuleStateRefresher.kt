package com.torchons.app.core.modules

import android.util.Log
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the foyer's module states into TokenManager, where `moduleEnabled()`
 * reads them.
 *
 * Until 2026-11-03 only the Settings screen fetched them, so on a fresh install
 * every module read as ON (the `fallback = true` of `moduleEnabled`) until the
 * user opened Settings: re-vote buttons and the Stats tab showed for modules
 * that were off. Called at every app start (MainScreen) so the window shrinks
 * to one network round-trip.
 */
@Singleton
class ModuleStateRefresher @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
) {
    suspend fun refresh() {
        runCatching {
            if (tokenManager.tokenFlow.first().isNullOrBlank()) return
            val resp = apiService.getModules()
            if (resp.isSuccessful) {
                val list = resp.body()?.modules.orEmpty()
                tokenManager.setModuleStates(list.associate { it.key to it.enabled })
            }
        }.onFailure { Log.w("ModuleStateRefresher", "refresh: ${it.message}") }
    }
}
