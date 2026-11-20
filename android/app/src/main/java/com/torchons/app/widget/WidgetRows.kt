package com.torchons.app.widget

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.torchons.app.MainActivity
import com.torchons.app.R
import com.torchons.app.core.today.TodayTap
import com.torchons.app.core.today.handOffRecipients
import com.torchons.app.core.today.todayTap
import com.torchons.app.data.model.Delegation
import com.torchons.app.ui.tasklist.RecurrenceGroup
import com.torchons.app.ui.tasklist.TaskWithUrgency
import com.torchons.app.ui.today.todayCardState
import com.torchons.app.util.TaskUrgencyCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * One line of the widget: a copy of a Tasks-tab card (ui/tasklist/TaskCard.kt) -- state stripe,
 * title, place, countdown, « Confiée à X » -- and its three buttons: Date + Qui, « ! », ✓.
 */

/** What a tap on the widget's « ! » does: write at once, or open the app on its dialog. */
internal enum class WidgetTodayTap { HAND, CANCEL, OPEN_APP }

/** One task line of the widget. */
internal data class WidgetTask(
    val taskId: String,
    val title: String,
    val state: TaskUrgencyCalculator.State,
    val countdownLabel: String,
    val placeLabel: String?,
    /** The « ! »: null hides it (feature off, or nobody else sees the task). */
    val todayLit: Boolean?,
    val todayLine: String?,
    val todayTap: WidgetTodayTap?,
    /** One-off or « uncertain »: grey instead of green. */
    val withoutCadence: Boolean = false,
    /** First task without cadence: the italic « Tâches sans fréquence » header goes above it. */
    val headerBefore: Boolean = false,
)

/** A Tasks-tab row as the widget draws it; [names] = the household's members by id. */
internal fun widgetTaskOf(
    t: TaskWithUrgency,
    delegation: Delegation?,
    names: Map<String, String>,
    me: String,
    res: Resources,
): WidgetTask {
    val today = todayCardState(res, t.task.assignedToIds, delegation, names, me)
    val tap = today?.let {
        when (todayTap(delegation, handOffRecipients(t.task.assignedToIds, names.keys.toList(), me), me)) {
            is TodayTap.HandTo -> WidgetTodayTap.HAND
            is TodayTap.Cancel -> WidgetTodayTap.CANCEL
            is TodayTap.Pick, is TodayTap.Info -> WidgetTodayTap.OPEN_APP
            null -> null
        }
    }
    val place = t.roomName?.let { name -> if (!t.roomEmoji.isNullOrBlank()) "${t.roomEmoji} $name" else name }
    return WidgetTask(
        t.task.id, t.task.title, t.state, t.countdownLabel, place, today?.lit, today?.line, tap,
        withoutCadence = t.group == RecurrenceGroup.SANS_CADENCE,
    )
}

/** The widget's background is always white: a fixed grey stands for the theme's onSurfaceVariant. */
private fun stateColor(state: TaskUrgencyCalculator.State, withoutCadence: Boolean): Color = when (state) {
    TaskUrgencyCalculator.State.RED -> Color(0xFFEF4444)
    TaskUrgencyCalculator.State.ORANGE -> Color(0xFFF97316)
    TaskUrgencyCalculator.State.GREEN -> if (withoutCadence) Color(0xFF6B7280) else Color(0xFF10B981)
}

private val muted = ColorProvider(Color(0xFF6B7280))
private val titleStyle =
    TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, color = ColorProvider(Color(0xFF1A1A1A)))

private fun smallBold(color: Color) =
    TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = ColorProvider(color))

@Composable
internal fun WidgetTaskRow(task: WidgetTask) {
    if (task.headerBefore) {
        Text(
            text = LocalContext.current.getString(R.string.task_list_header_without_cadence),
            style = TextStyle(fontSize = 10.sp, fontStyle = FontStyle.Italic, color = muted),
            modifier = GlanceModifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
    val stateColor = stateColor(task.state, task.withoutCadence)
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Glance has no IntrinsicSize: a fixed-height stripe, as tall as a two-line row.
        Box(modifier = GlanceModifier.width(4.dp).height(36.dp).cornerRadius(2.dp).background(stateColor)) {}
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
            Text(text = task.title, style = titleStyle, maxLines = 1)
            task.placeLabel?.let { Text(text = it, style = TextStyle(fontSize = 10.sp, color = muted), maxLines = 1) }
            if (task.countdownLabel.isNotEmpty()) {
                Text(text = task.countdownLabel, style = smallBold(stateColor), maxLines = 1)
            }
            task.todayLine?.let { Text(text = it, style = smallBold(Color(0xFFEF4444)), maxLines = 1) }
        }
        WidgetButton(
            icon = R.drawable.ic_widget_already_done,
            description = context.getString(R.string.suggestion_action_already_done),
            background = Color(0xFFEEF0FF),
            action = openApp(task.taskId, WidgetLaunch.Kind.DATE_WHO),
        )
        task.todayLit?.let { lit -> WidgetTodayButton(task, lit) }
        WidgetButton(
            icon = R.drawable.ic_widget_done,
            description = context.getString(R.string.suggestion_action_done),
            background = Color(0xFF10B981),
            action = actionRunCallback<CompleteTaskAction>(actionParametersOf(taskIdParam to task.taskId)),
        )
    }
}

private fun openApp(taskId: String, kind: WidgetLaunch.Kind) = actionStartActivity<MainActivity>(
    actionParametersOf(WidgetLaunch.taskIdKey to taskId, WidgetLaunch.kindKey to kind.name),
)

/** The « ! »: a red or grey disc with the sign drawn as text (no icon resource needed). */
@Composable
private fun WidgetTodayButton(task: WidgetTask, lit: Boolean) {
    val context = LocalContext.current
    val description = context.getString(if (lit) R.string.today_button_cancel_cd else R.string.today_button_cd)
    val action = when (task.todayTap) {
        WidgetTodayTap.OPEN_APP, null -> openApp(task.taskId, WidgetLaunch.Kind.TODAY)
        WidgetTodayTap.HAND, WidgetTodayTap.CANCEL ->
            actionRunCallback<TodayTapAction>(actionParametersOf(taskIdParam to task.taskId))
    }
    Spacer(modifier = GlanceModifier.width(4.dp))
    Box(
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(if (lit) Color(0xFFEF4444) else Color(0xFFEEF0FF))
            .clickable(action)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = ColorProvider(if (lit) Color.White else Color(0xFF4B5563)),
            ),
        )
    }
}

@Composable
private fun WidgetButton(icon: Int, description: String, background: Color, action: androidx.glance.action.Action) {
    Spacer(modifier = GlanceModifier.width(4.dp))
    Box(
        modifier = GlanceModifier.size(30.dp).cornerRadius(15.dp).background(background).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(provider = ImageProvider(icon), contentDescription = description, modifier = GlanceModifier.size(18.dp))
    }
}

val taskIdParam = ActionParameters.Key<String>("task_id")

/**
 * « Fait » from the widget goes through the offline queue, like the app's: the
 * completion is written locally at once and sent by SyncWorker. It used to call
 * the server directly, and failed silently offline.
 */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdParam] ?: return
        withContext(Dispatchers.IO) {
            // A second tap before the row leaves would be dropped by the server anyway: the
            // repository refuses it inside the write's own transaction (duplicate window), so two
            // quick taps cannot both pass a check made before the write. The failure is ignored:
            // the widget has nothing to say, and the redraw below shows what the cache holds.
            widgetDeps(context).taskRepository().completeTask(taskId)
        }
        TaskWidget().update(context, glanceId)
    }
}

/**
 * The widget's « ! » when it needs no dialog: hand the task to its only possible recipient, or
 * take it back (author). Same rule, same repository and same offline queue as the app's
 * (DelegationRepository): re-read from the cache at tap time, so a stale drawing never writes
 * something the rule would not. When the rule now wants a dialog, nothing is written.
 */
class TodayTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdParam] ?: return
        withContext(Dispatchers.IO) {
            val deps = widgetDeps(context)
            val me = deps.tokenManager().userIdFlow.first() ?: return@withContext
            val task = deps.taskRepository().getCachedTaskById(taskId) ?: return@withContext
            val members = deps.taskRepository().observeUsers().first().map { it.id }
            val delegations = deps.delegationRepository()
            val delegation = delegations.observeActive().first().firstOrNull { it.taskId == taskId }
            when (val tap = todayTap(delegation, handOffRecipients(task.assignedToIds, members, me), me)) {
                is TodayTap.HandTo -> delegations.handTask(taskId, tap.recipientIds)
                is TodayTap.Cancel -> delegations.cancel(tap.delegation)
                else -> Unit
            }
        }
        TaskWidget().update(context, glanceId)
    }
}
