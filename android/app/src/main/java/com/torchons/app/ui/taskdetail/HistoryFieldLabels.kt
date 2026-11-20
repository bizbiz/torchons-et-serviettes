package com.torchons.app.ui.taskdetail

import androidx.annotation.StringRes
import com.torchons.app.R

/**
 * The words that name a task field in the history card ("a modifié le titre"), by the key the
 * server logs in `change_log.new_data`. `context_ids` is what the app sends when the contexts of a
 * task change (TaskRepository.updateTask); it was missing, so an edit of the contexts was left out
 * of the card, and counted nowhere ("X a modifié 4 champs" for 5). Null = a key this version does
 * not know: not named, never shown raw.
 */
@StringRes
internal fun taskFieldLabelRes(key: String): Int? = when (key) {
    "title" -> R.string.task_field_title
    "description" -> R.string.task_field_description
    "category_id" -> R.string.task_field_category
    "tolerance" -> R.string.task_field_tolerance
    "assigned_to_ids" -> R.string.task_field_assigned
    "recurrence_rule" -> R.string.task_field_recurrence
    "weight" -> R.string.task_field_weight
    "is_active" -> R.string.task_field_active
    "sort_order" -> R.string.task_field_order
    "context_ids" -> R.string.task_field_contexts
    "room_id" -> R.string.task_field_room
    else -> null
}
