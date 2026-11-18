package com.torchons.app.ui.home

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torchons.app.R
import com.torchons.app.core.today.excludedFromSuggestions
import com.torchons.app.core.today.todayStackFor
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Delegation
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.Task
import com.torchons.app.ui.tasklist.computeTasksWithUrgency
import com.torchons.app.ui.today.TodayDelegations
import com.torchons.app.ui.today.handedByLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/*
 * The red « À faire aujourd'hui » stack at the top of the home screen (docs/plans/a-faire-aujourdhui.md):
 * the tasks someone else handed to me, one card at a time, « 1 sur 3 » in its header. The cross
 * passes to the next one; once every card was passed, they all come back (the same recycling as
 * the skipped suggestions). Computed from the local cache: right without network.
 */

/** One card of the stack: [position] of [total]. */
internal data class TodayStackCard(
    val delegation: Delegation,
    val suggestion: Suggestion,
    val position: Int,
    val total: Int,
)

internal class HomeTodayStack(
    scope: CoroutineScope,
    val today: TodayDelegations,
    activeTasks: Flow<List<Task>>,
    completions: Flow<List<Completion>>,
    me: Flow<String?>,
    resources: () -> Resources,
) {
    private val passed = MutableStateFlow<Set<String>>(emptySet())

    /** Every task handed to me and still to do, oldest first. */
    val stack: StateFlow<List<TodayStackCard>> =
        combine(today.all, activeTasks, completions, me) { delegations, tasks, comps, myId ->
            if (myId == null) return@combine emptyList()
            val byId = tasks.associateBy { it.id }
            val mine = todayStackFor(delegations, myId).filter { it.taskId in byId }
            val urgency = computeTasksWithUrgency(
                mine.map { byId.getValue(it.taskId) }, comps, LocalDate.now(), resources(),
            ).associateBy { it.task.id }
            mine.mapIndexed { i, d ->
                val t = urgency.getValue(d.taskId)
                val suggestion = Suggestion(
                    taskId = t.task.id, title = t.task.title, weight = t.task.weight,
                    state = t.state.name, daysUntilDue = t.daysUntilDue, reason = TODAY_REASON,
                )
                TodayStackCard(d, suggestion, i + 1, mine.size)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The card on top: the first one not passed with the cross. */
    val current: StateFlow<TodayStackCard?> =
        combine(stack, passed) { cards, gone -> cards.firstOrNull { it.delegation.taskId !in gone } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Out of my suggestions: the tasks I handed, and those handed to me (they are in this stack). */
    val excluded: StateFlow<Set<String>> =
        combine(today.all, me) { delegations, myId ->
            if (myId == null) emptySet() else excludedFromSuggestions(delegations, myId)
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The cross: the next card shows. True when that was the last one and they all came back. */
    fun pass(taskId: String): Boolean {
        val gone = passed.value + taskId
        if (stack.value.all { it.delegation.taskId in gone }) {
            passed.value = emptySet()
            return true
        }
        passed.value = gone
        return false
    }
}

/** The stack's top card: the suggestion card, with its own header and the cross passing on. */
@Composable
internal fun HomeTodayCard(
    viewModel: HomeViewModel,
    card: TodayStackCard,
    members: List<Pair<String, String>>,
    memberNames: Map<String, String>,
    gamificationVisible: Boolean,
    onTaskClick: (String) -> Unit,
    onEditTask: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val userId by viewModel.userId.collectAsState()
    val res = LocalContext.current.resources
    val taskId = card.suggestion.taskId
    val celebrating = uiState.celebratingTaskId == taskId
    val header = stringResource(R.string.today_title) + " · " +
        stringResource(R.string.today_stack_count_format, card.position, card.total)
    SuggestionCard(
        suggestion = card.suggestion,
        room = null,
        category = null,
        isCelebrating = celebrating,
        celebrationPoints = if (celebrating && gamificationVisible) uiState.celebrationPoints else null,
        celebrationReason = if (celebrating) uiState.celebrationReason else null,
        // No points line: the stack has no server prediction for these tasks (they are out of
        // /suggestions); the celebration still shows the points earned.
        gamificationVisible = false,
        celebrationEarnedBy = if (celebrating && gamificationVisible) uiState.celebrationEarnedBy else null,
        members = members,
        currentUserId = userId.orEmpty(),
        previewProvider = { iso, n -> viewModel.fetchCompletionPreview(taskId, iso, n) },
        onClick = { onTaskClick(taskId) },
        onCompleteToday = { pts, reason -> viewModel.completeTask(taskId, pts, reason) },
        onCompleteAtDate = { iso, by, pts, reason ->
            viewModel.completeTaskAtDate(taskId, iso, completedBy = by, previewPoints = pts, previewReason = reason)
        },
        onCompleteCollective = { ids, iso -> viewModel.completeTaskCollective(taskId, ids, iso) },
        onEdit = { onEditTask(taskId) },
        onDismiss = { viewModel.passTodayCard(taskId) },
        todayHeader = header,
        todaySubline = handedByLine(res, card.delegation, memberNames),
    )
}
