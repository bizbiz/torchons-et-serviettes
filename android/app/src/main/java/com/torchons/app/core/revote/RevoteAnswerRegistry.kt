package com.torchons.app.core.revote

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The answer to a re-vote request, as the server's `revote_answered` push carries it. */
data class RevoteAnswer(
    val taskId: String,
    /** "effort" or "frequency". */
    val kind: String,
    /** "answered" or "dismissed". */
    val resolution: String,
    val responderName: String,
    /** Raw votes: effort points, or 12 comma-separated day counts; empty when there is none. */
    val oldValue: String,
    val newValue: String,
)

/**
 * Who is waiting for the answer to a re-vote request, right now.
 *
 * The re-vote dialog registers (taskId, kind) while a request is pending and unregisters when
 * it closes. [TorchonsMessagingService] asks [consume] for every `revote_answered` push: when
 * a dialog waits for it, the event goes to the dialog and the system notification is skipped;
 * otherwise the notification shows as for any push. Model: PropositionStateHolder.
 */
@Singleton
class RevoteAnswerRegistry @Inject constructor() {
    private val waiting = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private val _events = MutableSharedFlow<RevoteAnswer>(extraBufferCapacity = 8)
    val events: SharedFlow<RevoteAnswer> = _events.asSharedFlow()

    fun register(taskId: String, kind: String) {
        waiting.add(taskId to kind)
    }

    fun unregister(taskId: String, kind: String) {
        waiting.remove(taskId to kind)
    }

    fun isWaiting(taskId: String, kind: String): Boolean = (taskId to kind) in waiting

    /**
     * Handles the data of a `revote_answered` push. True when a registration matches: the event
     * is emitted and the caller must not show the notification.
     */
    fun consume(data: Map<String, String>): Boolean {
        val answer = parse(data) ?: return false
        if (!isWaiting(answer.taskId, answer.kind)) return false
        return _events.tryEmit(answer)
    }

    companion object {
        fun parse(data: Map<String, String>): RevoteAnswer? {
            val taskId = data["task_id"] ?: return null
            val kind = data["kind"] ?: return null
            val resolution = data["resolution"] ?: return null
            return RevoteAnswer(
                taskId = taskId,
                kind = kind,
                resolution = resolution,
                responderName = data["responder_name"].orEmpty(),
                oldValue = data["old_value"].orEmpty(),
                newValue = data["new_value"].orEmpty(),
            )
        }
    }
}
