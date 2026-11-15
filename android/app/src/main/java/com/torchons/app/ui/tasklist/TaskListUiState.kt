package com.torchons.app.ui.tasklist

import androidx.compose.runtime.Immutable
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
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
 * Pilote le chip 🔁 (HIGH = CADENCE seul, LOW = SANS_CADENCE seul) et la
 * semantique adaptative du filtre "a faire" (voir applyFilters).
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
    val myAffinity: Int? = null,
    val group: RecurrenceGroup = RecurrenceGroup.CADENCE,
    /** Date ISO de la derniere completion, null si jamais completee. Sert au tri
     *  du Groupe B (buckets jamais-faite / deja-faite) et a la semantique
     *  "a faire" pour les non-recurrentes. */
    val lastCompletedAt: String? = null,
    /** true uniquement pour les taches de type "uncertain" (v0.14) : par
     *  convention elles ne sont jamais cachees par le filtre "a faire". */
    val isUncertain: Boolean = false,
)

/** Etat tri-state pour les filtres : Off / High / Low */
enum class FilterState { OFF, HIGH, LOW }

data class TaskListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val tasks: List<TaskWithUrgency> = emptyList(),
    /** Contextes coches dans la barre de filtres. Vide = tout. */
    val selectedContextIds: Set<String> = emptySet(),
    val isAtHome: Boolean = true,
    val showAllTasks: Boolean = false,
    val homeTasksHidden: Int = 0,
    val filterTodo: FilterState = FilterState.HIGH,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val filterAssignment: FilterState = FilterState.OFF,
    /** Contextes proposes en filtre : ceux presents sur AU MOINS une tache
     *  du foyer, calcules avant filtrage pour rester stables. */
    val availableContexts: List<TaskContextRef> = emptyList(),
    val error: String? = null,
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
    /** Barre de recherche ouverte : masque les chips et affiche un champ texte. */
    val isSearchBarExpanded: Boolean = false,
    /** Texte saisi dans la barre de recherche. */
    val searchQuery: String = "",
    /** Toggle "garder filtres" : par defaut la recherche ignore tous les filtres UI. */
    val searchRespectFilters: Boolean = false,
)

/**
 * Etat des filtres en memoire. Stocke dans un Flow pour reactiver le
 * combine automatiquement a chaque changement de filtre.
 *
 * Note : filterTodo est tri-state, defaut HIGH ("A faire"). Au demarrage,
 * on n'affiche que les taches en etat ORANGE ou RED (du / en retard), ce
 * qui reduit drastiquement le nombre d'items rendu et donne un scroll
 * fluide d'emblee. LOW ("Pas a faire") est l'inverse pour explorer ce qui
 * est deja a jour. OFF affiche tout.
 */
internal data class TaskListFilters(
    val filterTodo: FilterState = FilterState.HIGH,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val filterAssignment: FilterState = FilterState.OFF,
    val selectedContextIds: Set<String> = emptySet(),
    val showAllTasks: Boolean = false,
    val myAffinities: Map<String, Int> = emptyMap(),
    val searchQuery: String = "",
    val searchRespectFilters: Boolean = false,
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
)
