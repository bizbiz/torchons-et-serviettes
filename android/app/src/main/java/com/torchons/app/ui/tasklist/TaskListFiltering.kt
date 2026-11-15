package com.torchons.app.ui.tasklist

import android.content.res.Resources
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.LocalDate

/**
 * Applique les filtres et calcule la fraicheur. Fonction pure.
 *
 * Depuis v0.6.0 : la piece est lue depuis task.roomId (joint avec
 * la table rooms) au lieu d'etre parsee du titre comme avant.
 */
internal fun applyFilters(
    tasks: List<Task>,
    completions: List<Completion>,
    filters: TaskListFilters,
    isAtHome: Boolean,
    currentUserId: String?,
    hideHomeTasksOffWifi: Boolean,
    resources: Resources,
): FilteredResult {
    // « A la maison » se lit desormais sur la tache elle-meme : un de ses
    // contextes exige-t-il d'etre sur place ? Les drapeaux voyagent avec
    // elle, il n'y a plus de table a recouper.
    fun Task.exigePresence(): Boolean = contexts.any { it.requiresPresence }

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
    if (applyUiFilters) {
        when (filters.filterHome) {
            FilterState.HIGH -> filteredTasks = filteredTasks.filter { it.exigePresence() }
            FilterState.LOW -> filteredTasks = filteredTasks.filter { !it.exigePresence() }
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
        hideHomeTasksOffWifi
    ) {
        val before = filteredTasks.size
        filteredTasks = filteredTasks.filter { !it.exigePresence() }
        homeTasksHidden = before - filteredTasks.size
    }

    var tasksWithUrgency = computeTasksWithUrgency(
        filteredTasks, completions, LocalDate.now(), resources,
    )

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
                tasksWithUrgency = tasksWithUrgency.filter(::isToDo) // TaskUrgencyList.kt
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

    // Tri v0.20, trois blocs : sortForTaskList (TaskUrgencyList.kt).
    tasksWithUrgency = sortForTaskList(tasksWithUrgency)

    return FilteredResult(
        tasksWithUrgency, availableContexts, isAtHome, homeTasksHidden,
    )
}

/**
 * Normalise une chaine pour la recherche : NFD + strip diacritiques + lowercase.
 * Permet a "ecole" de matcher "École" et "cafe" de matcher "café".
 */
private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
