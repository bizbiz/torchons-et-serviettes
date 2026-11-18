package com.torchons.app.widget

import android.content.Intent
import androidx.glance.action.ActionParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A widget button that needs a dialog cannot show it: a widget draws no dialog. It opens the
 * app instead, which lands on the Tasks tab and opens that dialog for that task: « Date + Qui »
 * ([Kind.DATE_WHO]), or the « ! » dialogs ([Kind.TODAY]: « À qui ? », or « confiée par A à C »).
 * MainActivity posts the request read from the launch intent, MainScreen switches tab,
 * TaskListScreen opens and consumes it.
 */
object WidgetLaunch {

    enum class Kind { DATE_WHO, TODAY }

    data class Request(val taskId: String, val kind: Kind)

    /** Extras of the launch intent: Glance puts action parameters there by key name. */
    val taskIdKey = ActionParameters.Key<String>("widget_task_id")
    val kindKey = ActionParameters.Key<String>("widget_kind")

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    /**
     * Reads a widget request from a launch intent; ignores any other intent. A request without a
     * kind (a widget drawn before 2026-11-18, « Déjà fait » or « Ensemble ») opens « Date + Qui ».
     */
    fun postFrom(intent: Intent?) {
        val taskId = intent?.getStringExtra(taskIdKey.name) ?: return
        val kind = intent.getStringExtra(kindKey.name)
            ?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: Kind.DATE_WHO
        _request.value = Request(taskId, kind)
        // Consumed once: a configuration change re-delivers the same intent.
        intent.removeExtra(taskIdKey.name)
    }

    fun consume() {
        _request.value = null
    }
}
