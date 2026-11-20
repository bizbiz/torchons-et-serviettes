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
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
import com.torchons.app.ui.tasklist.TaskListFilters
import com.torchons.app.ui.tasklist.applyFilters
import com.torchons.app.data.repository.DelegationRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
 * The widget is a copy of the Tasks tab filtered on « À faire » (its default chip): same Room
 * cache, same repositories, same filter function (applyFilters with the default filters), same
 * rows and buttons, offline included. Until 2026-10-18 it showed the Home suggestions fetched
 * online; until 2026-11-18 its own « À faire » rule (onWidget) and a « together » button.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskWidgetEntryPoint {
    fun taskRepository(): TaskRepository
    fun pendingOperationDao(): PendingOperationDao
    fun tokenManager(): TokenManager
    fun delegationRepository(): DelegationRepository
}

/**
 * Second lock before the list's own filters: a task assigned to others only is never shown,
 * whatever the cache holds (the server already sends only the user's tasks, `GET /tasks`).
 * [hidePresence] hides tasks that need presence; the widget now leaves that to applyFilters,
 * as the Tasks tab does, and passes false.
 */
internal fun visibleOnWidget(tasks: List<Task>, userId: String?, hidePresence: Boolean): List<Task> =
    tasks.filter { task ->
        val mine = task.assignedToIds.isEmpty() || (userId != null && userId in task.assignedToIds)
        mine && !(hidePresence && task.contexts.any { it.requiresPresence })
    }

internal fun widgetDeps(context: Context): TaskWidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, TaskWidgetEntryPoint::class.java)

/**
 * What the widget could read. A failure is NOT an empty list: until 2026-10-17 any
 * error became `emptyList()` and the widget said « Tout est frais » over overdue
 * tasks, with nothing to tell the two apart.
 */
private sealed interface WidgetLoad {
    /** [householdEmpty]: the household has no active task at all, as opposed to none due. */
    data class Loaded(val tasks: List<WidgetTask>, val householdEmpty: Boolean = false) : WidgetLoad
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
                    val delegations = deps.delegationRepository().refresh()
                    repo.refreshTasks() && repo.refreshCompletions(limit = 2000) && delegations
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /** The Tasks tab's « À faire » list, recomputed on every change of the Room cache. */
    private fun observeTasks(context: Context, deps: TaskWidgetEntryPoint, refreshed: Boolean): Flow<WidgetLoad> {
        val repo = deps.taskRepository()
        val tokens = deps.tokenManager()
        return combine(
            repo.observeActiveTasks(),
            repo.observeRecentCompletions(2000),
            deps.delegationRepository().observeActive(),
            repo.observeUsers(),
        ) { tasks, completions, delegations, users ->
            if (tasks.isEmpty() && !refreshed) {
                WidgetLoad.Failed("no cache, refresh failed")
            } else {
                // Read at each emission: the phone may have left the home WiFi.
                val me = tokens.userIdFlow.first()
                val isAtHome = WifiHelper.isAtHome(context, tokens.homeSsidsFlow.first())
                // The tab's own filter function, with its default filters: « À faire » on.
                // Only the two « to do » blocks: no « show the tasks up to date » button here.
                val blocks = applyFilters(
                    visibleOnWidget(tasks, me, hidePresence = false), completions, TaskListFilters(),
                    isAtHome, tokens.hideHomeTasksOffWifiFlow.first(), context.resources,
                ).blocks
                val firstWithoutCadence = blocks.withoutCadence.firstOrNull()
                val names = users.associate { it.id to it.displayName }
                val byTask = delegations.associateBy { it.taskId }
                WidgetLoad.Loaded(
                    (blocks.toDo + blocks.withoutCadence).map {
                        widgetTaskOf(it, byTask[it.task.id], names, me.orEmpty(), context.resources)
                            .copy(headerBefore = it === firstWithoutCadence)
                    },
                    householdEmpty = tasks.isEmpty(),
                )
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
            text = context.getString(R.string.app_name),
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
            load is WidgetLoad.Loaded && load.householdEmpty -> Text(
                text = context.getString(R.string.widget_no_tasks),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(Color(0xFF6B7280))),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
            load is WidgetLoad.Loaded && load.tasks.isEmpty() -> Text(
                text = context.getString(R.string.widget_all_fresh),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(Color(0xFF6B7280))),
            )
            // Scrolls: as many tasks as the widget holds, the rest below.
            load is WidgetLoad.Loaded -> LazyColumn {
                items(load.tasks, itemId = { it.taskId.hashCode().toLong() }) { task ->
                    // A Column: an item may stack its « Tâches sans fréquence » header above its row.
                    Column(modifier = GlanceModifier.fillMaxWidth()) { WidgetTaskRow(task) }
                }
            }
        }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
