package com.torchons.app.ui.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.revote.RevoteAnswer
import com.torchons.app.core.revote.RevoteAnswerRegistry
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.RequestRevoteBody
import com.torchons.app.ui.common.RevoteNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Feedback of a re-vote request on the task detail screen: the status line under the button
 * and the answer. While a request is pending and the info dialog is open, it registers the
 * (task, kind) in [RevoteAnswerRegistry] so the push carrying the answer reaches the dialog
 * instead of the notification tray. No polling: the answer comes by push only.
 */
@HiltViewModel
class RevoteFeedbackViewModel @Inject constructor(
    private val apiService: ApiService,
    private val registry: RevoteAnswerRegistry,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val taskId: String = savedStateHandle.get<String>("taskId")
        ?: throw IllegalArgumentException("taskId manquant dans les arguments de navigation")

    private val _notice = MutableStateFlow<RevoteNotice?>(null)
    val notice: StateFlow<RevoteNotice?> = _notice.asStateFlow()

    /** The kind this screen currently waits an answer for, if any. */
    private var waitingKind: String? = null

    init {
        viewModelScope.launch { registry.events.collect { onAnswer(it) } }
    }

    fun request(targetUserId: String, targetName: String, kind: String, onSent: () -> Unit = {}) {
        viewModelScope.launch {
            _notice.value = try {
                val resp = apiService.requestRevote(
                    RequestRevoteBody(targetUserId = targetUserId, taskId = taskId, kind = kind)
                )
                when {
                    resp.isSuccessful -> {
                        watch(kind)
                        onSent()
                        val notified = resp.body()?.get("notified") as? Boolean ?: false
                        RevoteNotice.Waiting(targetUserId, targetName, kind, notified)
                    }
                    // 409: already pending, idempotent. Whether a push left is unknown.
                    resp.code() == 409 -> {
                        watch(kind)
                        RevoteNotice.Waiting(targetUserId, targetName, kind, notified = true)
                    }
                    else -> RevoteNotice.Failed(targetUserId, targetName)
                }
            } catch (_: Exception) {
                RevoteNotice.Failed(targetUserId, targetName)
            }
        }
    }

    /** The dialog opened on a request made earlier and still pending. */
    fun onPendingSeen(targetUserId: String, targetName: String, kind: String) {
        if (_notice.value != null) return
        watch(kind)
        _notice.value = RevoteNotice.Waiting(targetUserId, targetName, kind, notified = true)
    }

    /** The info dialog closed: nobody waits for the answer here any more. */
    fun onDialogClosed() {
        unwatch()
        _notice.value = null
    }

    private fun onAnswer(answer: RevoteAnswer) {
        val waiting = _notice.value as? RevoteNotice.Waiting ?: return
        if (answer.taskId != taskId || answer.kind != waiting.kind) return
        unwatch()
        _notice.value = RevoteNotice.Answered(waiting.userId, waiting.userName, answer)
    }

    private fun watch(kind: String) {
        unwatch()
        waitingKind = kind
        registry.register(taskId, kind)
    }

    private fun unwatch() {
        waitingKind?.let { registry.unregister(taskId, it) }
        waitingKind = null
    }

    override fun onCleared() {
        unwatch()
        super.onCleared()
    }
}
