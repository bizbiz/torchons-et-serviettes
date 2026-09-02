package com.torchons.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The identity of THIS installation, and the one thing Auto Backup must not copy.
 *
 * It lives in its own DataStore for a single reason: `res/xml/data_extraction_rules.xml`
 * and `res/xml/full_backup_content.xml` exclude this file by path. Everything else --
 * session token, preferences, home WiFi, Room -- keeps being backed up and restored, so
 * moving to a new phone stays invisible.
 *
 * What the exclusion buys: a restored handset finds no identity here, mints a fresh one,
 * and announces it to the server, which registers it as its own device. The old handset
 * keeps its own. Without that, both phones would share one identity and revoking either
 * would cut both -- measured on 2026-09-02, the restore does carry `torchons_prefs` over.
 *
 * Do NOT move this key back into `torchons_prefs`: it would be backed up again, and the
 * failure is silent -- the app works perfectly, the device list just shows one row where
 * there are two handsets.
 *
 * Rationale and arbitrations: docs/appareils.fr.md
 */
private val Context.deviceIdentityStore: DataStore<Preferences> by preferencesDataStore(
    name = "device_identity"
)

@Singleton
class DeviceIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Stable id for this installation, minted once.
     *
     * [legacyId] is the value that used to live in `torchons_prefs`. On the first launch
     * after this change, reusing it keeps existing installs as ONE device instead of
     * making every phone in the field show up twice.
     */
    suspend fun getOrCreate(legacyId: String? = null): String {
        val existing = context.deviceIdentityStore.data.first()[DEVICE_ID_KEY]
        if (existing != null) return existing
        val id = legacyId ?: UUID.randomUUID().toString()
        context.deviceIdentityStore.edit { it[DEVICE_ID_KEY] = id }
        return id
    }

    /** Current id without minting one. Null before the first [getOrCreate]. */
    suspend fun peek(): String? = context.deviceIdentityStore.data.first()[DEVICE_ID_KEY]

    /** Drops the identity. Used when this device signs itself out. */
    suspend fun clear() {
        context.deviceIdentityStore.edit { it.remove(DEVICE_ID_KEY) }
    }

    private companion object {
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    }
}
