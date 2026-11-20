package com.torchons.app.ui.components

import androidx.annotation.StringRes
import com.torchons.app.R
import com.torchons.app.core.sync.PendingOpKind

/**
 * The words that name a member setting in the sync dialog ("Réglage : heure du résumé quotidien"),
 * by the key the `user_settings` op carries (the keys `SettingsViewModel.updateSetting` sends).
 * Null for a key this version does not know (a newer one): the dialog then says "Réglage modifié".
 */
@StringRes
internal fun settingLabelRes(key: String?): Int? = when (key) {
    "notif_daily" -> R.string.sync_setting_notif_daily
    "notif_hour" -> R.string.sync_setting_notif_hour
    "notif_urgent" -> R.string.sync_setting_notif_urgent
    "notif_overdue_hour" -> R.string.sync_setting_notif_overdue_hour
    "notif_solo" -> R.string.sync_setting_notif_solo
    "notif_collective_with_me" -> R.string.sync_setting_notif_collective_with_me
    "notif_collective_without_me" -> R.string.sync_setting_notif_collective_without_me
    "notif_today_reminders" -> R.string.sync_setting_notif_today_reminders
    "affinity_popup_enabled" -> R.string.sync_setting_affinity_popup
    "affinity_expiry_months" -> R.string.sync_setting_affinity_expiry
    "fair_share_tolerance_percent" -> R.string.sync_setting_fair_share_tolerance
    "gamification_enabled" -> R.string.sync_setting_gamification
    "hide_home_tasks_off_wifi" -> R.string.sync_setting_hide_off_wifi
    else -> null
}

/** Ops that are about no task: their label is the kind alone (or the setting), never "tâche inconnue". */
internal val KINDS_WITHOUT_TASK = setOf(
    PendingOpKind.SETTING_CHANGE,
    PendingOpKind.PROMPT_ANSWER,
    PendingOpKind.OTHER,
)
