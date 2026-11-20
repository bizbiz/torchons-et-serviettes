package com.torchons.app.ui.settings

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.BonusConfig
import com.torchons.app.data.repository.SettingsQueue
import com.torchons.app.ui.common.OnlineResult
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.runOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The writes of the settings screens, split out of SettingsViewModel (D7, 2026-11-20 audit: each
 * was a `try { ... } catch { log }`, so a refusal left the screen showing a value the server never
 * took). Two kinds, and the screen tells them apart:
 *  - [updateSetting]: a member setting (`user_settings`, a `/sync` table). Offline-first: the
 *    change is shown and the DataStore updated at once, the op is queued and sent by the worker.
 *    Nothing to roll back, nothing to disable offline.
 *  - [setHomeSsids] and [updateBonusConfig]: REST only (home Wi-Fi names are per household and
 *    never go through `/sync`; the bonus table is the household's). Their controls are disabled
 *    offline, and an online failure rolls back and is said ([actionError]).
 *
 * The flows are the ViewModel's; `internal` because SettingsViewModel is its only user.
 */
internal class SettingsWrites(
    private val scope: CoroutineScope,
    private val api: ApiService,
    private val writer: SettingsQueue,
    private val userId: suspend () -> String?,
    private val settings: MutableStateFlow<UserSettingsState>,
    private val bonusConfig: MutableStateFlow<BonusConfig?>,
    /** Copies the settings to the DataStore the workers and filters read (TokenManager). */
    private val syncLocal: suspend (UserSettingsState) -> Unit,
    private val errorOf: (Throwable) -> UserError,
) {
    /** The last failure of a write, shown by the screen until dismissed. */
    val actionError = MutableStateFlow<UserError?>(null)

    /** Bumped when a bonus change was refused: the sliders are rebuilt from the server's values. */
    val bonusResets = MutableStateFlow(0)

    /**
     * Queues [key] = [value] for `/sync`; the caller has already changed [settings]. The DataStore
     * copy follows (it is what the filters and workers read offline).
     */
    fun updateSetting(key: String, value: Any): Job = scope.launch {
        try {
            val user = userId()
            if (user != null) writer.queueUpdate(user, key, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionError.value = errorOf(e)
        }
        syncLocal(settings.value)
    }

    /** Home Wi-Fi names: shown at once, put back (and said) when the server refuses or is unreachable. */
    fun setHomeSsids(updated: List<String>): Job {
        val before = settings.value.homeSsids
        settings.value = settings.value.copy(homeSsids = updated)
        return scope.launch {
            val result = runOnline(
                errorOf,
                rollback = { settings.value = settings.value.copy(homeSsids = before) },
            ) { api.updateSettings(mapOf("home_ssids" to updated)) }
            if (result is OnlineResult.Failed) actionError.value = result.error
            syncLocal(settings.value)
        }
    }

    /** One bonus/malus threshold of the household. Nothing is shown before the answer. */
    fun updateBonusConfig(key: String, value: Any): Job = scope.launch {
        val result = runOnline(errorOf, rollback = { bonusResets.value += 1 }) {
            api.updateBonusConfig(mapOf(key to value))
        }
        when (result) {
            is OnlineResult.Done -> result.body?.let { bonusConfig.value = it }
            is OnlineResult.Failed -> actionError.value = result.error
        }
    }

    fun dismissError() {
        actionError.value = null
    }
}
