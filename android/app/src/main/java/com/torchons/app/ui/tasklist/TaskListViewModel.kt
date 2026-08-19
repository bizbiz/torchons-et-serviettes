package com.torchons.app.ui.tasklist

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.TaskUrgencyCalculator
import com.torchons.app.util.WifiHelper
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val categories: List<Category> = emptyList(),
    val selectedCategoryIds: Set<String> = emptySet(),
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
    val selectedRoomId: String? = null,
    val availableRooms: List<Room> = emptyList(),
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
private data class TaskListFilters(
    val filterTodo: FilterState = FilterState.HIGH,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val filterAssignment: FilterState = FilterState.OFF,
    val selectedRoomId: String? = null,
    val selectedCategoryIds: Set<String> = emptySet(),
    val selectedContextIds: Set<String> = emptySet(),
    val showAllTasks: Boolean = false,
    val myAffinities: Map<String, Int> = emptyMap(),
    val searchQuery: String = "",
    val searchRespectFilters: Boolean = false,
)

/** Conteneur interne pour le combine des 5 flows. */
private data class CachedTaskList(
    val tasks: List<Task>,
    val categories: List<Category>,
    val rooms: List<Room>,
    val completions: List<Completion>,
    val filters: TaskListFilters,
)

/** Resultat post-traitement (filtrage + freshness) pousse vers l'UI thread. */
private data class ProcessedTaskList(
    val filtered: FilteredResult,
    val hasCache: Boolean,
    val categories: List<Category>,
    val rooms: List<Room>,
    val filters: TaskListFilters,
)

/** Resultat du filtrage pur. */
private data class FilteredResult(
    val tasks: List<TaskWithUrgency>,
    val rooms: List<Room>,
    val contexts: List<TaskContextRef>,
    val isAtHome: Boolean,
    val homeTasksHidden: Int,
)

/**
 * ViewModel de la liste des taches — pattern stale-while-revalidate.
 *
 * Observe les donnees depuis Room en continu via combine() et lance un
 * refresh() API en background. L'ecran affiche le cache instantanement
 * meme si le backend est injoignable.
 */
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskListUiState(isLoading = true))
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allUsers: StateFlow<List<User>> = taskRepository.observeUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** v0.32 : visibilite UI gamif (user-level AND foyer-level). */
    val gamificationVisible: StateFlow<Boolean> = tokenManager.gamificationVisibleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val filtersFlow = MutableStateFlow(TaskListFilters())

    init {
        observeCache()
        refresh()
    }

    /**
     * Observe les donnees Room + filtres en continu. Le combine emet a chaque
     * fois qu'un des flux change (nouveau cache apres refresh, ou changement
     * de filtre). Garantit affichage instantane au demarrage.
     */
    private fun observeCache() {
        viewModelScope.launch {
            // Tout le pipeline (combine + applyFilters + WifiHelper) tourne sur
            // Dispatchers.Default pour ne pas bloquer le main thread. Sinon
            // chaque changement de filtre / refresh fait un freeze visible
            // pendant le scroll.
            combine(
                taskRepository.observeActiveTasks(),
                taskRepository.observeCategories(),
                taskRepository.observeRooms(),
                taskRepository.observeRecentCompletions(2000),
                filtersFlow,
            ) { tasks, categories, rooms, completions, filters ->
                val homeSsids = tokenManager.homeSsidsFlow.first()
                val isAtHome = WifiHelper.isAtHome(context, homeSsids)
                val currentUserId = tokenManager.userIdFlow.first()
                val hideHomeTasksOffWifi = tokenManager.hideHomeTasksOffWifiFlow.first()
                val filtered = applyFilters(
                    tasks, categories, rooms, completions, filters,
                    isAtHome, currentUserId, hideHomeTasksOffWifi,
                )
                ProcessedTaskList(
                    filtered = filtered,
                    hasCache = tasks.isNotEmpty() || categories.isNotEmpty(),
                    categories = categories,
                    rooms = rooms,
                    filters = filters,
                )
            }.flowOn(Dispatchers.Default).collect { processed ->
                // Attention : on ne recopie PAS searchQuery / searchRespectFilters
                // ici. Ces champs sont mis a jour synchroniquement dans
                // updateSearchQuery / toggleSearchRespectFilters pour eviter que
                // le pipeline async (qui fait applyFilters sur Dispatchers.Default)
                // ne remette une version en retard dans le TextField. Sans ca,
                // le curseur "revient en arriere" quand l'utilisateur tape vite.
                _uiState.value = _uiState.value.copy(
                    tasks = processed.filtered.tasks,
                    categories = processed.categories,
                    availableRooms = processed.filtered.rooms,
                    availableContexts = processed.filtered.contexts,
                    isAtHome = processed.filtered.isAtHome,
                    homeTasksHidden = processed.filtered.homeTasksHidden,
                    selectedCategoryIds = processed.filters.selectedCategoryIds,
                    showAllTasks = processed.filters.showAllTasks,
                    filterTodo = processed.filters.filterTodo,
                    filterAffinity = processed.filters.filterAffinity,
                    filterEffort = processed.filters.filterEffort,
                    filterRecurrence = processed.filters.filterRecurrence,
                    filterHome = processed.filters.filterHome,
                    filterAssignment = processed.filters.filterAssignment,
                    selectedRoomId = processed.filters.selectedRoomId,
                    selectedContextIds = processed.filters.selectedContextIds,
                    hasCachedData = processed.hasCache,
                    isLoading = if (processed.hasCache) false else _uiState.value.isLoading,
                )
            }
        }
    }

    /**
     * Refresh les donnees depuis l'API en parallele. Echec silencieux :
     * si le backend ne repond pas, le cache Room reste affiche et
     * lastRefreshFailed passe a true.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)

            val results = listOf(
                async { taskRepository.refreshTasks() },
                async { taskRepository.refreshCategories() },
                async { taskRepository.refreshRooms() },
                // limit=2000 : sans ca, le backend defaulte a 50 et le repo
                // fait replaceAll, ce qui wipe toute completion plus ancienne
                // que le 50e rang global. Conseequence : tasks recurrentes a
                // long intervalle (ex: 45j) perdaient leur derniere completion
                // du cache, retombaient sur createdAt dans computeDueDate et
                // s'affichaient faussement en retard. Doit couvrir plus
                // largement que observeRecentCompletions(2000) pour rester
                // aligne.
                async { taskRepository.refreshCompletions(limit = 2000) },
                async { taskRepository.refreshUsers() },
            ).awaitAll()

            try {
                val affResp = apiService.getMyAffinities()
                if (affResp.isSuccessful) {
                    val affinities = (affResp.body() ?: emptyList()).associate {
                        (it["task_id"] as? String ?: "") to ((it["score"] as? Double)?.toInt() ?: 5)
                    }
                    filtersFlow.value = filtersFlow.value.copy(myAffinities = affinities)
                }
            } catch (_: Exception) {}

            val allOk = results.all { it }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                lastRefreshFailed = !allOk,
            )
        }
    }

    fun loadData() = refresh()

    /**
     * Completion immediate par l'utilisateur courant, datee maintenant (bouton
     * "fait" ✓). On recupere le preview des points (J-0, solo) pour que la
     * celebration affiche "+N pts" attribues a "Moi" — parite avec l'Accueil.
     */
    fun completeTask(taskId: String) {
        celebrate(
            taskId = taskId,
            resolve = {
                val preview = fetchCompletionPreview(
                    taskId, CompletionDateLabels.toIsoNoonUtc(LocalDate.now()), 1,
                )
                CelebrationContent(
                    points = preview?.finalPoints,
                    reason = preview?.reason,
                    earnedBy = earnedByFor(null, preview?.finalPoints),
                )
            },
            write = {
                taskRepository.completeTask(taskId).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            error = error.message ?: "Erreur lors de la completion",
                        )
                    },
                )
            },
        )
    }

    /**
     * Completion datee (dialog "Deja fait"), potentiellement au nom d'un autre
     * membre. Le preview vient du dialog (deja calcule) ; l'attribution suit
     * [completedBy] pour montrer "pour qui vont les points" si ce n'est pas moi.
     */
    fun completeTaskAtDate(
        taskId: String,
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) {
        celebrate(
            taskId = taskId,
            resolve = {
                CelebrationContent(previewPoints, previewReason, earnedByFor(completedBy, previewPoints))
            },
            write = {
                taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(error = error.message)
                    },
                )
            },
        )
    }

    /** Completion collective ("fait ensemble"). Online-first, split ceil. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) {
        celebrate(
            taskId = taskId,
            resolve = {
                // Points par personne : soit le preview passe par le dialog,
                // soit un fetch (chemin rapide 2-pers sans dialog). Le preview
                // divise deja par le nombre de participants (final_per_user).
                val preview = if (previewPoints != null) null
                    else fetchCompletionPreview(
                        taskId,
                        isoDate ?: CompletionDateLabels.toIsoNoonUtc(LocalDate.now()),
                        participantUserIds.size,
                    )
                val points = previewPoints ?: preview?.finalPoints
                CelebrationContent(
                    points = points,
                    reason = previewReason ?: preview?.reason,
                    earnedBy = earnedByForMany(participantUserIds, points),
                )
            },
            write = {
                taskRepository.completeTaskCollective(
                    taskId = taskId,
                    participantUserIds = participantUserIds,
                    completedAt = isoDate,
                ).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            completionMessage = "Tache completee a ${participantUserIds.size} !",
                        )
                    },
                    onFailure = { err ->
                        val msg = when (err) {
                            is TaskRepository.CollectiveCompletionException -> when (err.httpCode) {
                                409 -> "Impossible : tache deja completee recemment par un participant"
                                else -> "Erreur ${err.httpCode}"
                            }
                            else -> err.message ?: "Erreur reseau"
                        }
                        _uiState.value = _uiState.value.copy(error = msg)
                    },
                )
            },
        )
    }

    /** Contenu enrichi d'une celebration : points + raison + attribution. */
    private data class CelebrationContent(
        val points: Int?,
        val reason: String?,
        val earnedBy: List<Pair<String, Int>>?,
    )

    /**
     * Attribution single (displayName, points) pour la celebration.
     * [completedBy] null = utilisateur courant. Null si les points ou le nom
     * sont inconnus (preview offline) — la celebration retombe sur sa variante
     * sans points.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val name = if (completedBy == null) {
            displayName.value ?: return null
        } else {
            allUsers.value.firstOrNull { it.id == completedBy }?.displayName ?: return null
        }
        return listOf(name to points)
    }

    /**
     * Attribution collective : une entree (displayName, pointsParPersonne) par
     * participant. [points] est deja le split par personne (final_per_user du
     * preview). Null si les points sont inconnus.
     */
    private fun earnedByForMany(participantUserIds: List<String>, points: Int?): List<Pair<String, Int>>? {
        if (points == null || participantUserIds.isEmpty()) return null
        val names = allUsers.value.associate { it.id to it.displayName }
        return participantUserIds.mapNotNull { id -> names[id]?.let { it to points } }.ifEmpty { null }
    }

    /**
     * Joue la celebration post-completion (parite Accueil) : emoji + confetti
     * immediatement, puis points + attribution des que [resolve] les renvoie
     * (typiquement un preview). L'ecriture Room reelle ([write]) est faite
     * APRES l'animation (~3s) pour que la tache completee reste visible pendant
     * la celebration — l'ecrire plus tot la ferait passer en GREEN et le filtre
     * "a faire" retirerait la carte en pleine animation.
     */
    private fun celebrate(
        taskId: String,
        resolve: suspend () -> CelebrationContent,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            // Resolution concurrente : le confetti s'affiche instantanement meme
            // si le preview est lent / timeout offline.
            val filler = launch {
                val content = resolve()
                if (_uiState.value.celebratingTaskId == taskId) {
                    _uiState.value = _uiState.value.copy(
                        celebrationPoints = content.points,
                        celebrationReason = content.reason,
                        celebrationEarnedBy = content.earnedBy,
                    )
                }
            }
            kotlinx.coroutines.delay(3000L)
            filler.cancel()
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = null,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            write()
        }
    }

    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(
        taskId: String,
        isoDate: String,
        numParticipants: Int,
    ): com.torchons.app.data.model.CompletionPreview? {
        return try {
            val resp = apiService.getCompletionPreview(taskId, isoDate, numParticipants)
            if (resp.isSuccessful) resp.body() else null
        } catch (_: Exception) {
            null
        }
    }

    fun toggleFilterTodo() {
        filtersFlow.value = filtersFlow.value.copy(
            filterTodo = nextFilterState(filtersFlow.value.filterTodo)
        )
    }

    fun toggleFilterAffinity() {
        filtersFlow.value = filtersFlow.value.copy(filterAffinity = nextFilterState(filtersFlow.value.filterAffinity))
    }

    fun toggleFilterEffort() {
        filtersFlow.value = filtersFlow.value.copy(filterEffort = nextFilterState(filtersFlow.value.filterEffort))
    }

    fun toggleFilterRecurrence() {
        filtersFlow.value = filtersFlow.value.copy(filterRecurrence = nextFilterState(filtersFlow.value.filterRecurrence))
    }

    fun toggleFilterHome() {
        filtersFlow.value = filtersFlow.value.copy(filterHome = nextFilterState(filtersFlow.value.filterHome))
    }

    fun toggleFilterAssignment() {
        filtersFlow.value = filtersFlow.value.copy(filterAssignment = nextFilterState(filtersFlow.value.filterAssignment))
    }

    fun selectRoom(roomId: String?) {
        filtersFlow.value = filtersFlow.value.copy(selectedRoomId = roomId)
    }

    /**
     * Coche ou decoche un contexte dans la barre de filtres.
     *
     * Selection multiple en OU : cocher Chat et Jardin montre les taches de
     * l'un OU de l'autre. Les deux anciennes barres se combinaient en ET sans
     * jamais le dire, ce qui produisait des listes vides inexplicables des
     * qu'on touchait aux deux.
     */
    fun toggleContextFilter(id: String) {
        val actuels = filtersFlow.value.selectedContextIds.toMutableSet()
        if (!actuels.add(id)) actuels.remove(id)
        filtersFlow.value = filtersFlow.value.copy(selectedContextIds = actuels)
    }

    fun clearContextFilter() {
        filtersFlow.value = filtersFlow.value.copy(selectedContextIds = emptySet())
    }

    fun toggleCategory(categoryId: String) {
        val current = filtersFlow.value.selectedCategoryIds.toMutableSet()
        if (categoryId in current) current.remove(categoryId) else current.add(categoryId)
        filtersFlow.value = filtersFlow.value.copy(selectedCategoryIds = current)
    }

    fun clearCategoryFilter() {
        filtersFlow.value = filtersFlow.value.copy(selectedCategoryIds = emptySet())
    }

    fun toggleShowAll() {
        filtersFlow.value = filtersFlow.value.copy(showAllTasks = !filtersFlow.value.showAllTasks)
    }

    /**
     * Ouvre la barre de recherche. N'affecte pas filtersFlow : l'expansion est
     * purement UI et n'impacte pas le pipeline de filtrage.
     */
    fun openSearchBar() {
        _uiState.value = _uiState.value.copy(isSearchBarExpanded = true)
    }

    /**
     * Ferme la barre de recherche : vide la query et reset le toggle.
     * La prochaine ouverture repart donc sur les valeurs par defaut
     * (query vide, filtres ignores).
     */
    fun closeSearchBar() {
        _uiState.value = _uiState.value.copy(
            isSearchBarExpanded = false,
            searchQuery = "",
            searchRespectFilters = false,
        )
        filtersFlow.value = filtersFlow.value.copy(
            searchQuery = "",
            searchRespectFilters = false,
        )
    }

    /**
     * Double-ecriture synchrone : _uiState (source de verite pour le TextField,
     * mise a jour immediate pour que le curseur ne regresse pas) + filtersFlow
     * (declenche le pipeline de filtrage async via observeCache).
     */
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filtersFlow.value = filtersFlow.value.copy(searchQuery = query)
    }

    fun toggleSearchRespectFilters() {
        val next = !filtersFlow.value.searchRespectFilters
        _uiState.value = _uiState.value.copy(searchRespectFilters = next)
        filtersFlow.value = filtersFlow.value.copy(searchRespectFilters = next)
    }

    suspend fun logout() {
        authRepository.logout()
    }

    private fun nextFilterState(current: FilterState): FilterState = when (current) {
        FilterState.OFF -> FilterState.HIGH
        FilterState.HIGH -> FilterState.LOW
        FilterState.LOW -> FilterState.OFF
    }

    /**
     * Applique les filtres et calcule la fraicheur. Fonction pure.
     *
     * Depuis v0.6.0 : la piece est lue depuis task.roomId (joint avec
     * la table rooms) au lieu d'etre parsee du titre comme avant.
     */
    private fun applyFilters(
        tasks: List<Task>,
        categories: List<Category>,
        rooms: List<Room>,
        completions: List<Completion>,
        filters: TaskListFilters,
        isAtHome: Boolean,
        currentUserId: String?,
        hideHomeTasksOffWifi: Boolean,
    ): FilteredResult {
        val homeCategoryIds = categories.filter { it.requiresHome }.map { it.id }.toSet()
        val roomsById = rooms.associateBy { it.id }

        // availableRooms = pieces qui ont au moins une tache associee, triees
        // alphabetiquement (mirror de la regle backend ORDER BY name).
        val roomIdsWithTasks = tasks.mapNotNullTo(mutableSetOf()) { it.roomId }
        val availableRooms = rooms
            .filter { it.id in roomIdsWithTasks }
            .sortedBy { it.name }

        // Calcule sur `tasks` (non filtre) et non sur le resultat : sinon la
        // barre se retrecit a chaque clic et la selection ne peut que se
        // reduire. LinkedHashMap pour dedupliquer en gardant la premiere
        // occurrence, puis tri alphabetique comme les pieces.
        val availableContexts = LinkedHashMap<String, TaskContextRef>().apply {
            tasks.forEach { t -> t.contexts.forEach { putIfAbsent(it.id, it) } }
        }.values.sortedBy { it.name }

        // Tokens de recherche normalises (NFD + strip diacritiques + lowercase).
        // Multi-mots = AND sur tokens. Quand isSearching, on bypass tous les
        // filtres UI sauf si searchRespectFilters est active.
        val trimmedQuery = filters.searchQuery.trim()
        val searchTokens = if (trimmedQuery.isNotEmpty()) {
            normalize(trimmedQuery).split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val isSearching = searchTokens.isNotEmpty()
        val applyUiFilters = !isSearching || filters.searchRespectFilters

        // Filtre par CONTEXTE, en OU : une tache passe des qu'un seul de ses
        // rattachements est coche. Le filtre par categorie reste applique
        // apres, pour les taches d'avant le chantier qui n'ont pas encore de
        // rattachement — sans ca elles disparaitraient de toute liste filtree.
        var filteredTasks = if (applyUiFilters && filters.selectedContextIds.isNotEmpty()) {
            tasks.filter { t -> t.contexts.any { it.id in filters.selectedContextIds } }
        } else {
            tasks
        }
        // `filteredTasks` et non `tasks` dans la branche else : repartir de la
        // liste d'origine annulerait le filtre par contexte applique juste
        // au-dessus. Defaut mesure : le chip se cochait, la liste ne bougeait pas.
        if (applyUiFilters && filters.selectedCategoryIds.isNotEmpty()) {
            filteredTasks = filteredTasks.filter { it.categoryId in filters.selectedCategoryIds }
        }

        if (applyUiFilters && filters.selectedRoomId != null) {
            filteredTasks = filteredTasks.filter { it.roomId == filters.selectedRoomId }
        }

        if (applyUiFilters) {
            when (filters.filterHome) {
                FilterState.HIGH -> filteredTasks = filteredTasks.filter { it.categoryId in homeCategoryIds }
                FilterState.LOW -> filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
                FilterState.OFF -> {}
            }
        }

        // Filtre affectation : Solo = je suis le seul assigne. Groupe = 2+
        // assignes ou tache non assignee (par defaut "on la fait a deux").
        // Les taches assignees exclusivement a l'autre sont filtrees dans les
        // deux modes (ni "pour moi seul" ni "ensemble").
        if (applyUiFilters && currentUserId != null) {
            when (filters.filterAssignment) {
                FilterState.HIGH -> filteredTasks = filteredTasks.filter {
                    it.assignedToIds.size == 1 && it.assignedToIds[0] == currentUserId
                }
                FilterState.LOW -> filteredTasks = filteredTasks.filter {
                    it.assignedToIds.size >= 2 || it.assignedToIds.isEmpty()
                }
                FilterState.OFF -> {}
            }
        }

        // Masquage taches maison hors WiFi : actif uniquement si le reglage
        // user-level hideHomeTasksOffWifi est ON (defaut true = comportement
        // historique). Si OFF, on ne masque rien -> homeTasksHidden reste 0
        // -> banniere non affichee (correct).
        var homeTasksHidden = 0
        if (applyUiFilters && !isAtHome && !filters.showAllTasks &&
            homeCategoryIds.isNotEmpty() && hideHomeTasksOffWifi
        ) {
            val before = filteredTasks.size
            filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            homeTasksHidden = before - filteredTasks.size
        }

        // Pre-calcul O(M) : pour chaque tache, on connait sa derniere completion
        // en une seule passe. Avant, on faisait completions.filter().maxBy() PAR
        // tache => O(N×M). Sur ~50 taches × 500 completions = 25 000 ops par
        // recompute (changement de filtre, refresh, scroll), suffisant pour
        // faire ramer le scroll de la liste.
        val lastCompletionByTaskId = HashMap<String, Completion>(completions.size)
        for (c in completions) {
            val existing = lastCompletionByTaskId[c.taskId]
            if (existing == null || c.completedAt > existing.completedAt) {
                lastCompletionByTaskId[c.taskId] = c
            }
        }
        val categoriesById = categories.associateBy { it.id }
        val today = LocalDate.now()

        var tasksWithUrgency = filteredTasks.map { task ->
            val lastCompletion = lastCompletionByTaskId[task.id]
            // Online = serveur autoritaire (task.lastCompletedAt calcule
            // serveur) ; offline = completion optimiste locale plus recente.
            // max() des deux -> evite le fallback errone sur created_at quand
            // le cache local de completions est vide pour la tache.
            val effectiveLastCompleted = TaskUrgencyCalculator.latestCompletedAt(
                serverLastCompletedAt = task.lastCompletedAt,
                localCacheLatest = lastCompletion?.completedAt,
            )
            val nominal = TaskUrgencyCalculator.nominalIntervalDays(task.recurrenceRule)
            val dueDate = TaskUrgencyCalculator.computeDueDate(
                lastCompletedAt = effectiveLastCompleted,
                rule = task.recurrenceRule,
                createdAt = task.createdAt,
            )
            val absentDays = task.absentDaysSinceLastCompletion
            val state = TaskUrgencyCalculator.computeState(dueDate, today, nominal, absentDays)
            val daysUntilDue = TaskUrgencyCalculator.daysUntilDue(dueDate, today, absentDays)
            val countdownLabel = TaskUrgencyCalculator.countdownLabel(
                context.resources, dueDate, today, absentDays,
            )
            val category = task.categoryId?.let { categoriesById[it] }
            val room = task.roomId?.let { roomsById[it] }

            // Classification v0.20. Une 'uncertain' est recurrente de nom mais
            // sans cadence effective : on la range dans SANS_CADENCE pour
            // qu'elle soit filtrable via le chip 🔁 LOW aux cotes des ponctuelles.
            val isUncertain = task.recurrenceRule?.type == "uncertain"
            val group = if (task.recurrenceRule == null || isUncertain) {
                RecurrenceGroup.SANS_CADENCE
            } else {
                RecurrenceGroup.CADENCE
            }

            TaskWithUrgency(
                task = task,
                state = state,
                daysUntilDue = daysUntilDue,
                countdownLabel = countdownLabel,
                // Le contexte PRINCIPAL remplace la piece et la categorie. Le
                // repli sur les deux anciennes valeurs n'est pas defensif : une
                // tache creee avant le chantier et jamais rouverte n'a aucun
                // rattachement, et l'afficher sans lieu serait une regression
                // visible pour l'utilisateur.
                categoryName = task.contexts.getOrNull(1)?.name ?: category?.name,
                categoryColor = task.contexts.getOrNull(1)?.color ?: category?.color,
                roomName = task.contexts.firstOrNull()?.name ?: room?.name,
                roomEmoji = task.contexts.firstOrNull()?.emoji ?: room?.emoji,
                group = group,
                lastCompletedAt = lastCompletion?.completedAt,
                isUncertain = isUncertain,
            )
        }

        // Filtre "a faire" (v0.20, tri-state v0.26) : semantique adaptative
        // selon le groupe.
        // HIGH = A faire :
        //   - Groupe CADENCE : etat != GREEN
        //   - SANS_CADENCE non-uncertain : "jamais completee"
        //   - SANS_CADENCE uncertain : toujours visible (bypass) car le type
        //     existe justement pour tracker des choses a refaire sans cadence
        //     connue — les masquer apres une completion reviendrait a les oublier.
        // LOW = Pas a faire (inverse strict du HIGH, uncertain exclu) :
        //   - Groupe CADENCE : etat == GREEN
        //   - SANS_CADENCE non-uncertain : "deja completee"
        //   - SANS_CADENCE uncertain : exclues (elles ne sont jamais "faites")
        // OFF = pas de filtre.
        if (applyUiFilters) {
            when (filters.filterTodo) {
                FilterState.HIGH -> {
                    tasksWithUrgency = tasksWithUrgency.filter { t ->
                        when {
                            t.isUncertain -> true
                            t.group == RecurrenceGroup.CADENCE ->
                                t.state != TaskUrgencyCalculator.State.GREEN
                            else -> t.lastCompletedAt == null
                        }
                    }
                }
                FilterState.LOW -> {
                    tasksWithUrgency = tasksWithUrgency.filter { t ->
                        when {
                            t.isUncertain -> false
                            t.group == RecurrenceGroup.CADENCE ->
                                t.state == TaskUrgencyCalculator.State.GREEN
                            else -> t.lastCompletedAt != null
                        }
                    }
                }
                FilterState.OFF -> {}
            }
        }

        // Chip 🔁 (v0.20) — filtre tri-etat par groupe :
        // - HIGH = Groupe CADENCE seul (taches a rythme regulier)
        // - LOW  = Groupe SANS_CADENCE seul (ponctuelles + uncertain)
        // - OFF  = tout (le tri final empile CADENCE puis SANS_CADENCE)
        if (applyUiFilters) {
            when (filters.filterRecurrence) {
                FilterState.HIGH -> tasksWithUrgency = tasksWithUrgency.filter { it.group == RecurrenceGroup.CADENCE }
                FilterState.LOW -> tasksWithUrgency = tasksWithUrgency.filter { it.group == RecurrenceGroup.SANS_CADENCE }
                FilterState.OFF -> {}
            }
        }

        if (applyUiFilters) {
            when (filters.filterEffort) {
                FilterState.HIGH -> tasksWithUrgency = tasksWithUrgency.filter { it.task.weight >= 7 }
                FilterState.LOW -> tasksWithUrgency = tasksWithUrgency.filter { it.task.weight <= 3 }
                FilterState.OFF -> {}
            }
        }

        if (applyUiFilters && filters.filterAffinity != FilterState.OFF) {
            tasksWithUrgency = tasksWithUrgency.map {
                it.copy(myAffinity = filters.myAffinities[it.task.id])
            }
            tasksWithUrgency = when (filters.filterAffinity) {
                FilterState.HIGH -> tasksWithUrgency.filter { (it.myAffinity ?: 5) >= 7 }
                FilterState.LOW -> tasksWithUrgency.filter { (it.myAffinity ?: 5) <= 4 }
                else -> tasksWithUrgency
            }
        }

        // Matching de recherche : titre + description + nom categorie + nom piece,
        // tous concatenes et normalises (case/accents insensibles). AND sur tokens.
        if (isSearching) {
            tasksWithUrgency = tasksWithUrgency.filter { t ->
                val haystack = normalize(buildString {
                    append(t.task.title); append(' ')
                    append(t.task.description ?: ""); append(' ')
                    append(t.categoryName ?: ""); append(' ')
                    append(t.roomName ?: "")
                })
                searchTokens.all { it in haystack }
            }
        }

        // Tri v0.20 — empilement en trois blocs :
        // 1. Groupe CADENCE trie par urgence (RED > ORANGE > GREEN puis
        //    days_until_due asc : les plus en retard d'abord).
        // 2. Groupe SANS_CADENCE jamais completees, tri createdAt DESC
        //    (plus recemment creees d'abord).
        // 3. Groupe SANS_CADENCE deja completees, tri completedAt DESC
        //    (plus recemment faites d'abord).
        // En mode chip HIGH seul le bloc 1 survit, en LOW les blocs 2+3.
        // En OFF les trois s'empilent sans separateur visuel (decision UI).
        val cadence = tasksWithUrgency
            .filter { it.group == RecurrenceGroup.CADENCE }
            .sortedWith(compareBy(
                { TaskUrgencyCalculator.urgencyOrder(it.state) },
                { it.daysUntilDue },
            ))
        val sansCadenceNeverDone = tasksWithUrgency
            .filter { it.group == RecurrenceGroup.SANS_CADENCE && it.lastCompletedAt == null }
            .sortedByDescending { it.task.createdAt }
        val sansCadenceDone = tasksWithUrgency
            .filter { it.group == RecurrenceGroup.SANS_CADENCE && it.lastCompletedAt != null }
            .sortedByDescending { it.lastCompletedAt }
        tasksWithUrgency = cadence + sansCadenceNeverDone + sansCadenceDone

        return FilteredResult(
            tasksWithUrgency, availableRooms, availableContexts, isAtHome, homeTasksHidden,
        )
    }
}

/**
 * Normalise une chaine pour la recherche : NFD + strip diacritiques + lowercase.
 * Permet a "ecole" de matcher "École" et "cafe" de matcher "café".
 */
private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
