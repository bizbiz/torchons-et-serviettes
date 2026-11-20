package com.torchons.app.ui.settings

import com.torchons.app.data.local.TokenManager

data class UserSettingsState(
    val notifDaily: Boolean = true,
    val notifUrgent: Boolean = true,
    // Trois flags pour les completions, par defaut opt-in (false).
    val notifSolo: Boolean = false,
    val notifCollectiveWithMe: Boolean = false,
    val notifCollectiveWithoutMe: Boolean = false,
    val notifHour: Int = 9,
    // Hour of the overdue notification; a server without the key means 8.
    val notifOverdueHour: Int = 8,
    val affinityPopup: Boolean = true,
    val affinityExpiry: Int = TokenManager.DEFAULT_AFFINITY_EXPIRY_MONTHS,
    val fairShareTolerance: Int = 30,
    val homeSsids: List<String> = emptyList(),
    // Nb de membres du foyer actif : utilise pour cacher le toggle
    // "without_me" quand <= 2.
    val foyerMemberCount: Int = 2,
    // v0.32 : toggle gamification user-level (global, s'applique a tous
    // les foyers de l'user). Combine en OR avec foyer-level cote UI.
    val gamificationEnabled: Boolean = true,
    // Masquer les taches maison de la liste quand pas sur WiFi maison.
    // Defaut true = comportement historique (avant ce reglage etait
    // implicite et toujours actif).
    val hideHomeTasksOffWifi: Boolean = true,
    // « À faire aujourd'hui » reminders at 12/16/20 h (personal, on by default).
    val notifTodayReminders: Boolean = true,
    val isLoaded: Boolean = false
)

/**
 * The settings state from a `GET /api/settings` answer, with the member's queued changes already
 * applied on top ([com.torchons.app.data.repository.overlayPendingSettings]). Numbers are read as
 * [Number]: the server answer holds Doubles, a queued change holds the whole number it was made of.
 */
fun userSettingsFromServer(data: Map<String, Any?>) = UserSettingsState(
    notifDaily = data["notif_daily"] as? Boolean ?: true,
    notifUrgent = data["notif_urgent"] as? Boolean ?: true,
    notifSolo = data["notif_solo"] as? Boolean ?: false,
    notifCollectiveWithMe = data["notif_collective_with_me"] as? Boolean ?: false,
    notifCollectiveWithoutMe = data["notif_collective_without_me"] as? Boolean ?: false,
    notifHour = (data["notif_hour"] as? Number)?.toInt() ?: 9,
    notifOverdueHour = (data["notif_overdue_hour"] as? Number)?.toInt() ?: 8,
    affinityPopup = data["affinity_popup_enabled"] as? Boolean ?: true,
    affinityExpiry = (data["affinity_expiry_months"] as? Number)?.toInt()
        ?: TokenManager.DEFAULT_AFFINITY_EXPIRY_MONTHS,
    fairShareTolerance = (data["fair_share_tolerance_percent"] as? Number)?.toInt() ?: 30,
    homeSsids = (data["home_ssids"] as? List<*>)?.map { it.toString() } ?: emptyList(),
    foyerMemberCount = (data["foyer_member_count"] as? Number)?.toInt() ?: 2,
    gamificationEnabled = data["gamification_enabled"] as? Boolean ?: true,
    hideHomeTasksOffWifi = data["hide_home_tasks_off_wifi"] as? Boolean ?: true,
    notifTodayReminders = data["notif_today_reminders"] as? Boolean ?: true,
    isLoaded = true,
)
