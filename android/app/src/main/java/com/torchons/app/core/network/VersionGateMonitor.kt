package com.torchons.app.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.torchons.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the verdict of the server-side version gate (HTTP 426).
 *
 * Its own DataStore, deliberately: the gate has to work when auth is broken,
 * so it must not depend on [com.torchons.app.data.local.TokenManager].
 *
 * Lifecycle of the verdict, and why it is remembered
 * --------------------------------------------------
 * - As long as no 426 has ever been received, the app runs normally -- online
 *   AND offline. A gate that blocked on the mere absence of an answer would
 *   kill an offline-first app in the underground.
 * - Once a 426 arrives, the verdict is persisted. From then on the app blocks
 *   even with no network: otherwise turning airplane mode on would be all it
 *   takes to walk around the gate, which is its whole point.
 * - The verdict clears itself when the installed build catches up
 *   ([BuildConfig.VERSION_CODE] >= the remembered threshold). Nothing else can
 *   clear it, so a wrong threshold is fixed on the SERVER, never by waiting.
 */
private val Context.gateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "version_gate_prefs"
)

@Singleton
class VersionGateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** What the client must show when the gate is closed. */
    data class Blocked(
        val minVersionCode: Int,
        val yourVersionCode: Int,
        val downloadUrl: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _blocked = MutableStateFlow<Blocked?>(null)

    /** Null while the app may run; non-null freezes the UI on the update screen. */
    val blocked: StateFlow<Blocked?> = _blocked.asStateFlow()

    init {
        // Restore a past verdict before the first request goes out, so a blocked
        // app stays blocked offline and does not flash its normal UI first.
        scope.launch { restore() }
    }

    private suspend fun restore() {
        val prefs = context.gateDataStore.data.first()
        val min = prefs[MIN_VERSION_CODE_KEY] ?: return
        if (BuildConfig.VERSION_CODE >= min) {
            // The build caught up: drop the verdict rather than keep a stale one.
            clear()
            return
        }
        _blocked.value = Blocked(
            minVersionCode = min,
            yourVersionCode = BuildConfig.VERSION_CODE,
            downloadUrl = prefs[DOWNLOAD_URL_KEY].orEmpty(),
        )
    }

    /** Called by [AppVersionInterceptor] on a 426 answer. */
    fun reportGateClosed(minVersionCode: Int, downloadUrl: String) {
        if (BuildConfig.VERSION_CODE >= minVersionCode) {
            // The server refused a version it should have accepted. Trusting it
            // here would lock out a build that is in fact current, so we treat
            // the answer as noise rather than fencing the user out on it.
            return
        }
        _blocked.value = Blocked(
            minVersionCode = minVersionCode,
            yourVersionCode = BuildConfig.VERSION_CODE,
            downloadUrl = downloadUrl,
        )
        scope.launch {
            context.gateDataStore.edit {
                it[MIN_VERSION_CODE_KEY] = minVersionCode
                it[DOWNLOAD_URL_KEY] = downloadUrl
            }
        }
    }

    private suspend fun clear() {
        _blocked.value = null
        context.gateDataStore.edit {
            it.remove(MIN_VERSION_CODE_KEY)
            it.remove(DOWNLOAD_URL_KEY)
        }
    }

    private companion object {
        val MIN_VERSION_CODE_KEY = intPreferencesKey("gate_min_version_code")
        val DOWNLOAD_URL_KEY = stringPreferencesKey("gate_download_url")
    }
}
