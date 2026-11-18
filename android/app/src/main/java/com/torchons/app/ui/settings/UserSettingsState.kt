package com.torchons.app.ui.settings

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
    val affinityExpiry: Int = 6,
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
