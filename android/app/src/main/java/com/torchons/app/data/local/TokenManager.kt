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
    @ApplicationContext private val context: Context,
    private val deviceIdentity: DeviceIdentityStore,
) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("jwt_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
        // Foyer actif (multi-tenant v0.7.0)
        private val FOYER_ID_KEY = stringPreferencesKey("foyer_id")
        // Reglages notifications. Note v0.22+ : `notif_completed` retire au
        // profit de trois flags backend (notif_solo / notif_collective_with_me /
        // notif_collective_without_me). Le filtrage se fait cote serveur, donc
        // ces flags ne sont pas dupliques en local — quand un push arrive ici,
        // c'est qu'il a deja passe le filtre.
        private val NOTIF_DAILY_KEY = booleanPreferencesKey("notif_daily")
        private val NOTIF_URGENT_KEY = booleanPreferencesKey("notif_urgent")
        private val NOTIF_HOUR_KEY = intPreferencesKey("notif_hour")
        // Reglages affinite
        private val AFFINITY_POPUP_KEY = booleanPreferencesKey("affinity_popup_enabled")
        // SSIDs WiFi maison (separes par |)
        private val HOME_SSIDS_KEY = stringPreferencesKey("home_ssids")
        // Duree de persistance affinites/efforts en mois
        private val AFFINITY_EXPIRY_KEY = intPreferencesKey("affinity_expiry_months")
        // Sync offline
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val SYNC_CURSOR_KEY = longPreferencesKey("last_server_version")
        // When /sync last accepted ops from this phone (sync dialog details).
        private val LAST_SYNC_PUSH_AT_KEY = longPreferencesKey("last_sync_push_at")
        // versionCode vu au dernier lancement de l'app — sert a detecter une
        // mise a jour et invalider le cache view_snapshots (v0.10.2+).
        private val LAST_SEEN_VERSION_CODE_KEY = intPreferencesKey("last_seen_version_code")
        // Tolerance 2h : timestamp millis du dernier prompt affiche
        private val LAST_PROMPT_SHOWN_AT_KEY = longPreferencesKey("last_prompt_shown_at")
        // v0.32 : toggle gamification user-level (global cross-foyers).
        private val GAMIF_USER_KEY = booleanPreferencesKey("gamification_enabled_user")
        // v0.32 : toggle gamification du foyer courant. Resynchro via
        // `setCurrentFoyerGamification` apres chaque load /api/foyers.
        private val GAMIF_FOYER_KEY = booleanPreferencesKey("gamification_enabled_current_foyer")
        // Masquer les taches maison de la liste hors WiFi maison (user-level).
        // Defaut true = comportement historique.
        private val HIDE_HOME_OFF_WIFI_KEY = booleanPreferencesKey("hide_home_tasks_off_wifi")
        // Cache offline du nom du foyer courant : evite le flash du
        // placeholder "Mon foyer" a l'ouverture de l'ecran Foyer avant que
        // /api/foyers reponde (offline-first, cf docs/cache-conventions.md).
        private val CURRENT_FOYER_NAME_KEY = stringPreferencesKey("current_foyer_name")
        // P2 : etat des modules du foyer courant, serialise "cle=0|1" separe
        // par des virgules. Absent tant que GET /api/modules n'a pas repondu
        // au moins une fois — c'est ce vide qui declenche le repli hérité.
        private val MODULE_STATES_KEY = stringPreferencesKey("module_states")
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

    /** Flow du foyer actif (multi-tenant). Null si pas encore associe. */
    val foyerIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[FOYER_ID_KEY]
    }

    // --- Reglages ---

    val notifDailyFlow: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_DAILY_KEY] ?: true }
    val notifUrgentFlow: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_URGENT_KEY] ?: true }
    val notifHourFlow: Flow<Int> = context.dataStore.data.map { it[NOTIF_HOUR_KEY] ?: 9 }
    val affinityPopupFlow: Flow<Boolean> = context.dataStore.data.map { it[AFFINITY_POPUP_KEY] ?: true }

    suspend fun setNotifDaily(enabled: Boolean) { context.dataStore.edit { it[NOTIF_DAILY_KEY] = enabled } }
    suspend fun setNotifUrgent(enabled: Boolean) { context.dataStore.edit { it[NOTIF_URGENT_KEY] = enabled } }
    suspend fun setNotifHour(hour: Int) { context.dataStore.edit { it[NOTIF_HOUR_KEY] = hour } }
    suspend fun setAffinityPopup(enabled: Boolean) { context.dataStore.edit { it[AFFINITY_POPUP_KEY] = enabled } }

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

    /** Timestamp millis du dernier prompt affiche (tolerance 2h). */
    val lastPromptShownAtFlow: Flow<Long> = context.dataStore.data.map { it[LAST_PROMPT_SHOWN_AT_KEY] ?: 0L }

    suspend fun setLastPromptShownAt(millis: Long) {
        context.dataStore.edit { it[LAST_PROMPT_SHOWN_AT_KEY] = millis }
    }
    suspend fun setAffinityExpiry(months: Int) { context.dataStore.edit { it[AFFINITY_EXPIRY_KEY] = months } }

    // v0.32 : flags gamification (cf. SKILL design v0.32.0).
    val userGamificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[GAMIF_USER_KEY] ?: true }
    val currentFoyerGamificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[GAMIF_FOYER_KEY] ?: true }

    // --- Modules (P2, cf. docs/module-registry.md) ---

    /**
     * Etat des modules du foyer courant, rafraichi apres chaque GET
     * /api/modules. Map vide = jamais charge.
     */
    val moduleStatesFlow: Flow<Map<String, Boolean>> = context.dataStore.data.map { prefs ->
        parseModuleStates(prefs[MODULE_STATES_KEY])
    }

    /**
     * Vrai si ce module est actif. `fallback` est la reponse tant que les
     * modules n'ont jamais ete charges — pas un defaut metier : le defaut
     * metier vit dans modules.yaml cote serveur, ici on ne fait que combler
     * la fenetre avant la premiere reponse reseau.
     */
    fun moduleEnabledFlow(key: String, fallback: Boolean = true): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            parseModuleStates(prefs[MODULE_STATES_KEY])[key] ?: fallback
        }

    suspend fun setModuleStates(states: Map<String, Boolean>) {
        val encoded = states.entries.joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" }
        context.dataStore.edit { it[MODULE_STATES_KEY] = encoded }
    }

    private fun parseModuleStates(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) parts[0] to (parts[1] == "1") else null
        }.toMap()
    }

    /** Vrai si l'UI gamif doit etre visible. Source de verite branchee sur tous
     *  les composants gamif (Home, completion, navigation tabs
     *  stats/leaderboard/streaks/badges/fair-share, celebration, notifs, dialog
     *  affinity/effort).
     *
     *  P2 : l'etat collectif ne vient plus de `foyers.gamification_enabled` mais
     *  du module `motivation`. Le flag user, lui, reste — requalifie en
     *  preference d'AFFICHAGE personnelle (`motivation.hide_for_me`) : le module
     *  est une decision du foyer, ne pas se les montrer est une decision perso.
     *  C'est ce qui corrige le defaut de modele du AND d'origine, ou deux
     *  drapeaux homonymes laissaient un membre masquer une decision collective
     *  sans que rien ne le dise.
     *
     *  Tant que /api/modules n'a jamais repondu, on retombe sur
     *  `GAMIF_FOYER_KEY` (deja alimente par /api/foyers, que le serveur tient a
     *  jour en double). Sans ce repli, la premiere ouverture apres mise a jour
     *  devinerait — et devinerait faux dans un sens ou dans l'autre. */
    val gamificationVisibleFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val legacyFoyer = prefs[GAMIF_FOYER_KEY] ?: true
        val collective = parseModuleStates(prefs[MODULE_STATES_KEY])["motivation"] ?: legacyFoyer
        (prefs[GAMIF_USER_KEY] ?: true) && collective
    }

    suspend fun setUserGamificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[GAMIF_USER_KEY] = enabled }
    }

    suspend fun setCurrentFoyerGamificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[GAMIF_FOYER_KEY] = enabled }
    }

    /** Nom du foyer courant mis en cache local : sert a seeder l'UI au
     *  demarrage du ViewModel sans attendre /api/foyers. */
    val currentFoyerNameFlow: Flow<String?> =
        context.dataStore.data.map { it[CURRENT_FOYER_NAME_KEY] }

    suspend fun setCurrentFoyerName(name: String) {
        context.dataStore.edit { it[CURRENT_FOYER_NAME_KEY] = name }
    }

    /** Lectures one-shot du foyer courant en cache (id + nom) pour le seed
     *  d'ouverture de l'ecran Foyer (avant la reponse reseau). */
    suspend fun getCachedFoyerId(): String? =
        context.dataStore.data.first()[FOYER_ID_KEY]

    suspend fun getCachedFoyerName(): String? =
        context.dataStore.data.first()[CURRENT_FOYER_NAME_KEY]

    suspend fun setHomeSsids(ssids: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[HOME_SSIDS_KEY] = ssids.filter { it.isNotBlank() }.joinToString("|")
        }
    }

    /** Masquer les taches maison hors WiFi maison (user-level). Defaut true
     *  = comportement historique. Lu par TaskListViewModel.applyFilters. */
    val hideHomeTasksOffWifiFlow: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_HOME_OFF_WIFI_KEY] ?: true }

    suspend fun setHideHomeTasksOffWifi(enabled: Boolean) {
        context.dataStore.edit { it[HIDE_HOME_OFF_WIFI_KEY] = enabled }
    }

    suspend fun removeHomeSsid(ssid: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[HOME_SSIDS_KEY]?.split("|")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            current.remove(ssid)
            prefs[HOME_SSIDS_KEY] = current.joinToString("|")
        }
    }

    /** Sauvegarde le token et les informations utilisateur apres connexion. */
    suspend fun saveAuthData(
        token: String,
        userId: String,
        username: String,
        displayName: String,
        foyerId: String? = null,
    ) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
            preferences[USER_ID_KEY] = userId
            preferences[USERNAME_KEY] = username
            preferences[DISPLAY_NAME_KEY] = displayName
            if (foyerId != null) {
                preferences[FOYER_ID_KEY] = foyerId
            }
        }
    }

    /**
     * Remplace le seul jeton, sans toucher au foyer.
     *
     * Sert a la transition silencieuse : apres une restauration sur un nouveau
     * telephone, le serveur rebascule la session sur l'identite neuve et rend
     * un jeton propre a CET appareil. Voir docs/appareils.fr.md.
     */
    suspend fun updateToken(token: String) {
        context.dataStore.edit { preferences -> preferences[TOKEN_KEY] = token }
    }

    /** Met a jour le token et le foyer_id apres un switch de foyer. */
    suspend fun updateTokenAndFoyer(token: String, foyerId: String) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
            preferences[FOYER_ID_KEY] = foyerId
        }
    }

    /** Supprime toutes les donnees d'authentification (deconnexion). */
    suspend fun clearAuthData() {
        context.dataStore.edit { preferences ->
            preferences.remove(TOKEN_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(USERNAME_KEY)
            preferences.remove(DISPLAY_NAME_KEY)
            preferences.remove(FOYER_ID_KEY)
            preferences.remove(CURRENT_FOYER_NAME_KEY)
        }
    }

    // --- Sync offline ---

    /**
     * UUID stable par installation, mais PAS par sauvegarde.
     *
     * L'identite a quitte `torchons_prefs` pour [DeviceIdentityStore], dont le
     * fichier est exclu d'Auto Backup : c'est ce qui permet a un telephone
     * restaure d'avoir sa propre identite, donc d'etre revocable sans couper
     * l'ancien. Le commentaire precedent disait « stable par installation » —
     * c'etait faux depuis qu'Auto Backup est actif, la sauvegarde recopiait la
     * valeur d'un appareil a l'autre (mesure le 2026-09-02).
     *
     * L'ancienne valeur est passee en `legacyId` : sans elle, chaque telephone
     * deja installe apparaitrait comme DEUX appareils apres la mise a jour.
     *
     * Detail : docs/appareils.fr.md
     */
    suspend fun getOrCreateDeviceId(): String {
        val legacy = context.dataStore.data.first()[DEVICE_ID_KEY]
        return deviceIdentity.getOrCreate(legacy)
    }

    suspend fun getSyncCursor(): Long {
        return context.dataStore.data.first()[SYNC_CURSOR_KEY] ?: 0L
    }

    suspend fun setSyncCursor(version: Long) {
        context.dataStore.edit { it[SYNC_CURSOR_KEY] = version }
    }

    /** Epoch ms of the last /sync that applied at least one op of ours; null = never. */
    val lastSyncPushAtFlow: Flow<Long?> = context.dataStore.data.map { it[LAST_SYNC_PUSH_AT_KEY] }

    suspend fun setLastSyncPushAt(millis: Long) {
        context.dataStore.edit { it[LAST_SYNC_PUSH_AT_KEY] = millis }
    }

    // --- Cache invalidation ---

    /** Retourne le versionCode vu au dernier lancement, ou 0 si jamais stocke. */
    suspend fun getLastSeenVersionCode(): Int {
        return context.dataStore.data.first()[LAST_SEEN_VERSION_CODE_KEY] ?: 0
    }

    suspend fun setLastSeenVersionCode(code: Int) {
        context.dataStore.edit { it[LAST_SEEN_VERSION_CODE_KEY] = code }
    }
}
