package com.torchons.app.ui.tasklist

import android.content.res.Resources
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import java.time.LocalDate

/**
 * Filtre la liste et calcule la fraicheur. Fonction pure, partagee par l'onglet Taches et le
 * widget (avec les filtres par defaut).
 *
 * Since 2026-11-18 the six tri-state chips are gone (À faire, Aimées, Long, Récurrentes, Maison,
 * Solo: nobody read them); what is to do versus up to date is now the blocks of
 * TaskListBlocks.kt. Left here: the context chips, the off-home-WiFi hiding, and the search,
 * which ignores both and reaches every task.
 */
internal fun applyFilters(
    tasks: List<Task>,
    completions: List<Completion>,
    filters: TaskListFilters,
    isAtHome: Boolean,
    hideHomeTasksOffWifi: Boolean,
    resources: Resources,
): FilteredResult {
    // « A la maison » se lit desormais sur la tache elle-meme : un de ses
    // contextes exige-t-il d'etre sur place ?
    fun Task.exigePresence(): Boolean = contexts.any { it.requiresPresence }

    // Calcule sur `tasks` (non filtre) et non sur le resultat : sinon la
    // barre se retrecit a chaque clic et la selection ne peut que se
    // reduire. LinkedHashMap pour dedupliquer en gardant la premiere
    // occurrence, puis tri alphabetique.
    val availableContexts = LinkedHashMap<String, TaskContextRef>().apply {
        tasks.forEach { t -> t.contexts.forEach { putIfAbsent(it.id, it) } }
    }.values.sortedBy { it.name }

    // Tokens de recherche normalises (NFD + strip diacritiques + lowercase).
    // Multi-mots = AND sur tokens. The search reaches every task: no context filter, no
    // off-home hiding (the user's choice on 2026-11-18, which removed the « keep filters » toggle).
    val trimmedQuery = filters.searchQuery.trim()
    val searchTokens = if (trimmedQuery.isNotEmpty()) {
        normalize(trimmedQuery).split(Regex("\\s+")).filter { it.isNotEmpty() }
    } else {
        emptyList()
    }
    val isSearching = searchTokens.isNotEmpty()

    // Filtre par CONTEXTE, en OU : une tache passe des qu'un seul de ses
    // rattachements est coche.
    var filteredTasks = if (!isSearching && filters.selectedContextIds.isNotEmpty()) {
        tasks.filter { t -> t.contexts.any { it.id in filters.selectedContextIds } }
    } else {
        tasks
    }

    // Masquage taches maison hors WiFi : actif uniquement si le reglage
    // user-level hideHomeTasksOffWifi est ON (defaut true = comportement
    // historique). Si OFF, on ne masque rien -> homeTasksHidden reste 0
    // -> banniere non affichee (correct).
    var homeTasksHidden = 0
    if (!isSearching && !isAtHome && !filters.showAllTasks && hideHomeTasksOffWifi) {
        val before = filteredTasks.size
        filteredTasks = filteredTasks.filter { !it.exigePresence() }
        homeTasksHidden = before - filteredTasks.size
    }

    var tasksWithUrgency = computeTasksWithUrgency(filteredTasks, completions, LocalDate.now(), resources)

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
        blocks = taskListBlocks(tasksWithUrgency),
        searching = isSearching,
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
