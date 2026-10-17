package com.torchons.app.widget

import android.content.Intent
import androidx.glance.action.ActionParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A widget button that needs a dialog (« Déjà fait » asks a date, « Fait
 * ensemble » a date or the participants) cannot show it: a widget draws no
 * dialog. It opens the app instead, which lands on the Tasks tab and opens
 * that dialog for that task. MainActivity posts the request read from the
 * launch intent, MainScreen switches tab, TaskListScreen opens and consumes it.
 */
object WidgetLaunch {

    data class Request(val taskId: String, val together: Boolean)

    /** Extras of the launch intent: Glance puts action parameters there by key name. */
    val taskIdKey = ActionParameters.Key<String>("widget_task_id")
    val togetherKey = ActionParameters.Key<Boolean>("widget_together")

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    /** Reads a widget request from a launch intent; ignores any other intent. */
    fun postFrom(intent: Intent?) {
        val taskId = intent?.getStringExtra(taskIdKey.name) ?: return
        _request.value = Request(taskId, intent.getBooleanExtra(togetherKey.name, false))
        // Consumed once: a configuration change re-delivers the same intent.
        intent.removeExtra(taskIdKey.name)
    }

    fun consume() {
        _request.value = null
    }
}
