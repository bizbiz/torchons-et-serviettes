package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.torchons.app.data.model.TaskHistoryDto
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType

/**
 * Entite Room pour le cache d'historique d'une tache.
 * L'id du serveur (change_log.id) sert de cle primaire — stable et monotone.
 */
@Entity(tableName = "task_history")
data class TaskHistoryEntity(
    @PrimaryKey val id: Long,
    val taskId: String,
    val eventType: String,
    val actorUserId: String?,
    val actorName: String,
    val at: String?,
    val voteValue: Int?,
    val consensusBefore: Int?,
    val consensusAfter: Int?,
    val fieldsChangedJson: String?,
    val oldJson: String?,
    val newJson: String?,
)

private val gson = Gson()

fun TaskHistoryDto.toEntity(taskId: String): TaskHistoryEntity {
    val value = details["value"] as? Number
    val cb = details["consensus_before"] as? Number
    val ca = details["consensus_after"] as? Number
    val fields = (details["fields_changed"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val old = details["old"]
    val new = details["new"]
    return TaskHistoryEntity(
        id = id,
        taskId = taskId,
        eventType = eventType,
        actorUserId = actorUserId,
        actorName = actorName,
        at = at,
        voteValue = value?.toInt(),
        consensusBefore = cb?.toInt(),
        consensusAfter = ca?.toInt(),
        fieldsChangedJson = if (fields.isEmpty()) null else gson.toJson(fields),
        oldJson = old?.let { gson.toJson(it) },
        newJson = new?.let { gson.toJson(it) },
    )
}

fun TaskHistoryEntity.toDomain(): TaskHistoryEvent {
    val type = when (eventType) {
        "vote_affinity" -> TaskHistoryEventType.VOTE_AFFINITY
        "vote_effort" -> TaskHistoryEventType.VOTE_EFFORT
        "vote_frequency" -> TaskHistoryEventType.VOTE_FREQUENCY
        "task_update" -> TaskHistoryEventType.TASK_UPDATE
        else -> TaskHistoryEventType.UNKNOWN
    }
    val fields = fieldsChangedJson?.let {
        runCatching {
            gson.fromJson(it, Array<String>::class.java).toList()
        }.getOrElse { emptyList() }
    } ?: emptyList()
    return TaskHistoryEvent(
        id = id,
        type = type,
        actorUserId = actorUserId,
        actorName = actorName,
        at = at,
        voteValue = voteValue,
        consensusBefore = consensusBefore,
        consensusAfter = consensusAfter,
        fieldsChanged = fields,
        oldJson = oldJson,
        newJson = newJson,
    )
}
