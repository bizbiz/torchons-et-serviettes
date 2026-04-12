package com.torchons.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Extension pour creer le DataStore de preferences. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "torchons_prefs")

/**
 * Gestionnaire du token JWT et des informations utilisateur.
 * Utilise DataStore Preferences pour la persistance.
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("jwt_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
        // Reglages notifications
        private val NOTIF_DAILY_KEY = booleanPreferencesKey("notif_daily")
        private val NOTIF_URGENT_KEY = booleanPreferencesKey("notif_urgent")
        private val NOTIF_COMPLETED_KEY = booleanPreferencesKey("notif_completed")
        private val NOTIF_HOUR_KEY = intPreferencesKey("notif_hour")
        // Reglages affinite
        private val AFFINITY_POPUP_KEY = booleanPreferencesKey("affinity_popup_enabled")
        // Seuil de fraicheur pour suggestions (en %)
        private val FRESHNESS_THRESHOLD_KEY = intPreferencesKey("freshness_threshold")
        // SSIDs WiFi maison (separes par |)
        private val HOME_SSIDS_KEY = stringPreferencesKey("home_ssids")
        // Duree de persistance affinites/efforts en mois
        private val AFFINITY_EXPIRY_KEY = intPreferencesKey("affinity_expiry_months")
        // Sync offline
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val SYNC_CURSOR_KEY = longPreferencesKey("last_server_version")
    }

    /** Flow du token JWT. Emet null si aucun token stocke. */
    val tokenFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[TOKEN_KEY]
    }

    /** Flow de l'identifiant utilisateur. */
    val userIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USER_ID_KEY]
    }

    /** Flow du nom d'utilisateur. */
    val usernameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    /** Flow du nom d'affichage. */
    val displayNameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[DISPLAY_NAME_KEY]
    }

    // --- Reglages ---

    val notifDailyFlow: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_DAILY_KEY] ?: true }
    val notifUrgentFlow: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_URGENT_KEY] ?: true }
    val notifCompletedFlow: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_COMPLETED_KEY] ?: false }
    val notifHourFlow: Flow<Int> = context.dataStore.data.map { it[NOTIF_HOUR_KEY] ?: 9 }
    val affinityPopupFlow: Flow<Boolean> = context.dataStore.data.map { it[AFFINITY_POPUP_KEY] ?: true }

    suspend fun setNotifDaily(enabled: Boolean) { context.dataStore.edit { it[NOTIF_DAILY_KEY] = enabled } }
    suspend fun setNotifUrgent(enabled: Boolean) { context.dataStore.edit { it[NOTIF_URGENT_KEY] = enabled } }
    suspend fun setNotifCompleted(enabled: Boolean) {
        context.dataStore.edit { it[NOTIF_COMPLETED_KEY] = enabled }
        // Aussi dans SharedPreferences pour le FCM Service (pas acces au DataStore)
        context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .edit().putBoolean("notif_completed", enabled).apply()
    }
    suspend fun setNotifHour(hour: Int) { context.dataStore.edit { it[NOTIF_HOUR_KEY] = hour } }
    suspend fun setAffinityPopup(enabled: Boolean) { context.dataStore.edit { it[AFFINITY_POPUP_KEY] = enabled } }
    val freshnessThresholdFlow: Flow<Int> = context.dataStore.data.map { it[FRESHNESS_THRESHOLD_KEY] ?: 10 }
    suspend fun setFreshnessThreshold(value: Int) { context.dataStore.edit { it[FRESHNESS_THRESHOLD_KEY] = value } }

    val homeSsidsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[HOME_SSIDS_KEY]?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }
    suspend fun addHomeSsid(ssid: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[HOME_SSIDS_KEY]?.split("|")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            current.add(ssid)
            prefs[HOME_SSIDS_KEY] = current.joinToString("|")
        }
    }
    val affinityExpiryFlow: Flow<Int> = context.dataStore.data.map { it[AFFINITY_EXPIRY_KEY] ?: 6 }
    suspend fun setAffinityExpiry(months: Int) { context.dataStore.edit { it[AFFINITY_EXPIRY_KEY] = months } }

    suspend fun removeHomeSsid(ssid: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[HOME_SSIDS_KEY]?.split("|")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            current.remove(ssid)
            prefs[HOME_SSIDS_KEY] = current.joinToString("|")
        }
    }

    /** Sauvegarde le token et les informations utilisateur apres connexion. */
    suspend fun saveAuthData(token: String, userId: String, username: String, displayName: String) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
            preferences[USER_ID_KEY] = userId
            preferences[USERNAME_KEY] = username
            preferences[DISPLAY_NAME_KEY] = displayName
        }
    }

    /** Supprime toutes les donnees d'authentification (deconnexion). */
    suspend fun clearAuthData() {
        context.dataStore.edit { preferences ->
            preferences.remove(TOKEN_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(USERNAME_KEY)
            preferences.remove(DISPLAY_NAME_KEY)
        }
    }

    // --- Sync offline ---

    /** UUID stable par installation. Genere une seule fois, persiste ensuite. */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.first()[DEVICE_ID_KEY]
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[DEVICE_ID_KEY] = newId }
        return newId
    }

    suspend fun getSyncCursor(): Long {
        return context.dataStore.data.first()[SYNC_CURSOR_KEY] ?: 0L
    }

    suspend fun setSyncCursor(version: Long) {
        context.dataStore.edit { it[SYNC_CURSOR_KEY] = version }
    }
}
