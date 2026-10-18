package com.torchons.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.torchons.app.MainActivity
import com.torchons.app.R
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.WifiHelper
import com.torchons.app.ui.tasklist.computeTasksWithUrgency
import com.torchons.app.ui.tasklist.sortForTaskList
import com.torchons.app.util.TaskUrgencyCalculator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget reads the same Room cache as the Tasks tab, through the same
 * repository, so it shows the same tasks in the same order, offline included.
 * Until 2026-10-18 it showed the Home suggestions (red and orange only, a few
 * per tier) fetched online, and its « Fait » called the server directly.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskWidgetEntryPoint {
    fun taskRepository(): TaskRepository
    fun pendingOperationDao(): PendingOperationDao
    fun tokenManager(): TokenManager
}

/**
 * What the widget may show, before ranking. Not a setting, both rules always apply:
 * - a task assigned to others only is never shown, whatever the cache holds (the
 *   server already sends only the user's tasks, `GET /tasks`; this is the second lock,
 *   see the todo item on solo tasks) ;
 * - off the home WiFi, tasks that need presence are hidden, as in the Tasks tab
 *   (user setting `hideHomeTasksOffWifi`).
 */
internal fun visibleOnWidget(tasks: List<Task>, userId: String?, hidePresence: Boolean): List<Task> =
    tasks.filter { task ->
        val mine = task.assignedToIds.isEmpty() || (userId != null && userId in task.assignedToIds)
        mine && !(hidePresence && task.contexts.any { it.requiresPresence })
    }

private fun widgetDeps(context: Context): TaskWidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, TaskWidgetEntryPoint::class.java)

/** One task line of the widget. */
private data class WidgetTask(
    val taskId: String,
    val title: String,
    val state: TaskUrgencyCalculator.State,
    val countdownLabel: String,
)

/**
 * What the widget could read. A failure is NOT an empty list: until 2026-10-17 any
 * error became `emptyList()` and the widget said « Tout est frais » over overdue
 * tasks, with nothing to tell the two apart.
 */
private sealed interface WidgetLoad {
    data class Loaded(val tasks: List<WidgetTask>) : WidgetLoad
    data class Failed(val cause: String) : WidgetLoad
}

/** Bound on the server refresh before drawing from the cache. */
private const val REFRESH_TIMEOUT_MS = 8_000L

class TaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = widgetDeps(context)
        val refreshed = refreshIfIdle(deps)
        val loads = observeTasks(context, deps, refreshed)
        val initial = loads.first()
        if (initial is WidgetLoad.Failed) Log.w(TAG, "tasks not loaded: ${initial.cause}")

        // Collected INSIDE the composition: a Glance session outlives provideGlance
        // (~45 s after a tap), and update() on a live session only recomposes. A
        // list computed once before provideContent stayed frozen, so a task done
        // from the widget kept its red line (2026-10-18, v0.41.2).
        provideContent {
            val load by loads.collectAsState(initial)
            WidgetContent(load)
        }
    }

    /**
     * Online, the server is authoritative. Not while ops are queued: the refresh
     * REPLACEs the cache and would drop their optimistic rows (a task just done
     * would turn red again); SyncWorker redraws the widget once they are sent.
     */
    private suspend fun refreshIfIdle(deps: TaskWidgetEntryPoint): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (deps.pendingOperationDao().getAll().isNotEmpty()) return@withContext false
                val repo = deps.taskRepository()
                withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    repo.refreshTasks() && repo.refreshCompletions(limit = 2000)
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /** The ranked list, recomputed on every change of the Room cache. */
    private fun observeTasks(context: Context, deps: TaskWidgetEntryPoint, refreshed: Boolean): Flow<WidgetLoad> {
        val repo = deps.taskRepository()
        val tokens = deps.tokenManager()
        return combine(repo.observeActiveTasks(), repo.observeRecentCompletions(2000)) { tasks, completions ->
            if (tasks.isEmpty() && !refreshed) {
                WidgetLoad.Failed("no cache, refresh failed")
            } else {
                // Read at each emission: the phone may have left the home WiFi.
                val hidePresence = tokens.hideHomeTasksOffWifiFlow.first() &&
                    !WifiHelper.isAtHome(context, tokens.homeSsidsFlow.first())
                val visible = visibleOnWidget(tasks, tokens.userIdFlow.first(), hidePresence)
                val ranked = sortForTaskList(
                    computeTasksWithUrgency(visible, completions, LocalDate.now(), context.resources),
                )
                WidgetLoad.Loaded(ranked.map { WidgetTask(it.task.id, it.task.title, it.state, it.countdownLabel) })
            }
        }
            .catch { e -> emit(WidgetLoad.Failed(e.javaClass.simpleName)) }
            .flowOn(Dispatchers.IO)
    }

    companion object {
        private const val TAG = "TaskWidget"
    }
}

@Composable
private fun WidgetContent(load: WidgetLoad) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.White)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Text(
            text = "Torchons & Serviettes",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = ColorProvider(Color(0xFF6366F1)),
            ),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))

        when {
            load is WidgetLoad.Failed -> Text(
                text = context.getString(R.string.widget_load_failed),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(Color(0xFFEF4444))),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
            load is WidgetLoad.Loaded && load.tasks.isEmpty() -> Text(
                text = context.getString(R.string.widget_all_fresh),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(Color(0xFF6B7280))),
            )
            // Scrolls: as many tasks as the widget holds, the rest below.
            load is WidgetLoad.Loaded -> LazyColumn {
                items(load.tasks, itemId = { it.taskId.hashCode().toLong() }) { task ->
                    WidgetTaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun WidgetTaskRow(task: WidgetTask) {
    val stateColor = when (task.state) {
        TaskUrgencyCalculator.State.RED -> Color(0xFFEF4444)
        TaskUrgencyCalculator.State.ORANGE -> Color(0xFFF97316)
        TaskUrgencyCalculator.State.GREEN -> Color(0xFF10B981)
    }
    val context = LocalContext.current

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indicateur couleur etat d'urgence
        Box(
            modifier = GlanceModifier.width(4.dp).height(32.dp).cornerRadius(2.dp).background(stateColor),
        ) {}
        Spacer(modifier = GlanceModifier.width(8.dp))

        Column(
            modifier = GlanceModifier.defaultWeight()
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = task.title,
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = ColorProvider(Color(0xFF1A1A1A)),
                ),
                maxLines = 1,
            )
            Text(
                text = task.countdownLabel,
                style = TextStyle(fontSize = 10.sp, color = ColorProvider(Color(0xFF6B7280))),
                maxLines = 1,
            )
        }

        // Same three actions as a card of the Tasks tab. The two that need a
        // dialog open the app on it (WidgetLaunch).
        WidgetButton(
            icon = R.drawable.ic_widget_already_done,
            description = context.getString(R.string.suggestion_action_already_done),
            background = Color(0xFFEEF0FF),
            action = actionStartActivity<MainActivity>(
                actionParametersOf(WidgetLaunch.taskIdKey to task.taskId, WidgetLaunch.togetherKey to false),
            ),
        )
        WidgetButton(
            icon = R.drawable.ic_widget_together,
            description = context.getString(R.string.suggestion_action_together),
            background = Color(0xFFEEF0FF),
            action = actionStartActivity<MainActivity>(
                actionParametersOf(WidgetLaunch.taskIdKey to task.taskId, WidgetLaunch.togetherKey to true),
            ),
        )
        WidgetButton(
            icon = R.drawable.ic_widget_done,
            description = context.getString(R.string.suggestion_action_done),
            background = Color(0xFF10B981),
            action = actionRunCallback<CompleteTaskAction>(actionParametersOf(taskIdParam to task.taskId)),
        )
    }
}

@Composable
private fun WidgetButton(
    icon: Int,
    description: String,
    background: Color,
    action: androidx.glance.action.Action,
) {
    Spacer(modifier = GlanceModifier.width(4.dp))
    Box(
        modifier = GlanceModifier
            .size(30.dp)
            .cornerRadius(15.dp)
            .background(background)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(18.dp),
        )
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}

val taskIdParam = ActionParameters.Key<String>("task_id")

/**
 * « Fait » from the widget goes through the offline queue, like the app's: the
 * completion is written locally at once and sent by SyncWorker. It used to call
 * the server directly, and failed silently offline.
 */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[taskIdParam] ?: return
        withContext(Dispatchers.IO) {
            widgetDeps(context).taskRepository().completeTask(taskId)
        }
        TaskWidget().update(context, glanceId)
    }
}
