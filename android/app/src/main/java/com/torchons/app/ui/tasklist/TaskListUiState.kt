package com.torchons.app.ui.tasklist

import androidx.compose.runtime.Immutable
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.ui.common.UserError
import com.torchons.app.util.TaskUrgencyCalculator

/**
 * Donnees d'une tache avec son etat d'urgence calcule (modele due_date) +
 * infos d'affichage pre-resolues (categorie, piece). Pre-calcule dans
 * applyFilters pour eviter au TaskCard de faire des lookups dans des maps
 * a chaque recompose.
 *
 * @Immutable : permet a Compose de smart-skip les items LazyColumn dont la
 * valeur n'a pas change (meme contenu = pas de recompose). Sans cette
 * annotation, le `task: Task` (qui contient List<String>) rend la classe
 * instable et chaque emission de la liste recompose tous les items visibles.
 */
/**
 * Classification v0.20 d'une tache selon qu'elle porte une cadence :
 * - CADENCE : recurrence definie (interval/weekdays/monthly/custom/monthly_variable)
 * - SANS_CADENCE : non-recurrente OU type "uncertain" (cadence libre/inconnue)
 *
 * Pilote la regle « a faire » (isToDo), les blocs de l'onglet (TaskListBlocks.kt) et le
 * liseré gris des taches sans frequence.
 */
enum class RecurrenceGroup { CADENCE, SANS_CADENCE }

@Immutable
data class TaskWithUrgency(
    val task: Task,
    val state: TaskUrgencyCalculator.State,
    val daysUntilDue: Int,
    val countdownLabel: String,
    val categoryName: String?,
    val categoryColor: String?,
    val roomName: String?,
    val roomEmoji: String?,
    val group: RecurrenceGroup = RecurrenceGroup.CADENCE,
    /** Date ISO de la derniere completion, null si jamais completee. Sert au tri
     *  du Groupe B (buckets jamais-faite / deja-faite) et a la semantique
     *  "a faire" pour les non-recurrentes. */
    val lastCompletedAt: String? = null,
    /** true uniquement pour les taches de type "uncertain" (v0.14) : par
     *  convention elles sont toujours « a faire ». */
    val isUncertain: Boolean = false,
)

data class TaskListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    /** Every task the list may show (context and search applied), in sortForTaskList order. */
    val tasks: List<TaskWithUrgency> = emptyList(),
    /** The same tasks, split into the tab's blocks (TaskListBlocks.kt). */
    val blocks: TaskListBlocks = TaskListBlocks(),
    /** « Afficher les tâches à jour » pressed. Kept while the app runs, folded at a cold start. */
    val showUpToDate: Boolean = false,
    /** A search is typed: every match shows, up to date included. */
    val searching: Boolean = false,
    /** Contextes coches dans la barre de filtres. Vide = tout. */
    val selectedContextIds: Set<String> = emptySet(),
    val isAtHome: Boolean = true,
    val showAllTasks: Boolean = false,
    val homeTasksHidden: Int = 0,
    /** Contextes proposes en filtre : ceux presents sur AU MOINS une tache
     *  du foyer, calcules avant filtrage pour rester stables. */
    val availableContexts: List<TaskContextRef> = emptyList(),
    val error: UserError? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    /** Id de la tache en cours de "celebration" (animation post-completion). */
    val celebratingTaskId: String? = null,
    /** Compteur incremente a chaque celebration pour relancer konfetti. */
    val celebrationTrigger: Int = 0,
    /** Points affiches dans la celebration (null = inconnu / offline). */
    val celebrationPoints: Int? = null,
    /** Raison bonus/malus de la celebration (BONUS_RETARD, MALUS_AVANCE, ...). */
    val celebrationReason: String? = null,
    /** Attribution par participant (displayName, points) dans la celebration. */
    val celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /** Barre de recherche ouverte : remplace la barre des contextes par un champ texte. */
    val isSearchBarExpanded: Boolean = false,
    /** Texte saisi dans la barre de recherche. */
    val searchQuery: String = "",
)

/**
 * Etat des filtres en memoire. Stocke dans un Flow pour reactiver le
 * combine automatiquement a chaque changement de filtre.
 */
internal data class TaskListFilters(
    val selectedContextIds: Set<String> = emptySet(),
    val showAllTasks: Boolean = false,
    val searchQuery: String = "",
)

/** Conteneur interne pour le combine des flows. */
internal data class CachedTaskList(
    val tasks: List<Task>,
    val completions: List<Completion>,
    val filters: TaskListFilters,
)

/** Resultat post-traitement (filtrage + freshness) pousse vers l'UI thread. */
internal data class ProcessedTaskList(
    val filtered: FilteredResult,
    val hasCache: Boolean,
    val filters: TaskListFilters,
)

/** Resultat du filtrage pur. */
internal data class FilteredResult(
    val tasks: List<TaskWithUrgency>,
    val contexts: List<TaskContextRef>,
    val isAtHome: Boolean,
    val homeTasksHidden: Int,
    val blocks: TaskListBlocks = taskListBlocks(tasks),
    val searching: Boolean = false,
)
