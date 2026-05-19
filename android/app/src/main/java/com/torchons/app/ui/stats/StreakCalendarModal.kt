package com.torchons.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.PresenceCellState
import com.torchons.app.data.model.PresenceDayPut
import com.torchons.app.data.model.PresenceSource
import com.torchons.app.data.repository.TaskRepository
import kotlinx.coroutines.launch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.torchons.app.ui.common.PresenceCalendarGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * EntryPoint Hilt pour acceder a ApiService depuis ce modal sans ViewModel
 * (meme pattern que UserProfileDialog — pas de VM cable dans le NavGraph).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StreakCalendarApiEntryPoint {
    fun apiService(): ApiService
    // P4b : le modal edite la presence -> il doit aussi recalculer l'etat
    // derive (home-freeze/urgence + serie/record) cf cache-conventions.
    fun taskRepository(): TaskRepository
}

/** Un membre du foyer affichable dans le selecteur d'user du modal. */
data class StreakMember(val userId: String, val displayName: String)

/**
 * Modal UNIQUE "Agenda — presence & series" (P4b, design decision 5 v3 :
 * "one modal to rule them all"). Remplace l'ancien modal lecture seule ET
 * le calendrier inline de l'ecran Foyer.
 *
 * - La presence (fond vert/rouge/gris + icone source) est FOYER-GLOBALE :
 *   un seul GET /api/presence pour le foyer. **Editable au clic** :
 *   tap -> PUT /presence + recalcul derive (home-freeze/urgence + serie).
 * - La couche etoile/serie depend du membre selectionne : GET
 *   /api/activity-days?user_id=. Le selecteur d'user ne recharge QUE cette
 *   couche (la presence ne bouge pas).
 * - Auto-suffisant (Hilt EntryPoint : ApiService + TaskRepository), pas de
 *   VM hote -> ouvrable des 4 entrees (ecran Foyer / Stats / profil /
 *   prompt) sans threader de callbacks.
 */
@Composable
fun StreakCalendarModal(
    members: List<StreakMember>,
    initialUserId: String,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            StreakCalendarApiEntryPoint::class.java,
        )
    }
    val apiService = remember { entryPoint.apiService() }
    val taskRepository = remember { entryPoint.taskRepository() }
    // Incremente a chaque edition reussie -> re-trigger le GET presence
    // (la serie se recalcule sur la nouvelle presence).
    var refreshTrigger by remember { mutableStateOf(0) }

    var selectedUserId by remember {
        mutableStateOf(
            if (members.any { it.userId == initialUserId }) initialUserId
            else members.firstOrNull()?.userId.orEmpty()
        )
    }
    var visibleMonth by remember {
        mutableStateOf(YearMonth.from(LocalDate.now()))
    }
    // Presence foyer-globale (independante du user selectionne).
    var presence by remember {
        mutableStateOf<Map<String, Pair<PresenceCellState, PresenceSource>>>(emptyMap())
    }
    // Jours d'activite du membre selectionne (couche etoile/serie).
    var activityDays by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    // P4b sweep 0.34.1 — runs serie SOURCE SERVEUR (/streaks). Plus de
    // recalcul client fenetré : record + en-cours sur tout l'historique.
    var recordRunDays by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var currentRunDays by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    // Plage = mois visible elargi d'une semaine de chaque cote, pour que la
    // serie ne soit pas tronquee au saut de mois (la grille affiche aussi des
    // jours hors-mois en bord de semaine).
    val rangeFrom = visibleMonth.atDay(1).minusDays(7)
    val rangeTo = visibleMonth.atEndOfMonth().plusDays(7)

    // Refetch presence (foyer) au changement de mois OU apres une edition
    // (refreshTrigger) -> la serie se recalcule sur la presence a jour.
    LaunchedEffect(visibleMonth, refreshTrigger) {
        try {
            val resp = withContext(Dispatchers.IO) {
                apiService.getPresence(rangeFrom.toString(), rangeTo.toString())
            }
            if (resp.isSuccessful) {
                val rows = resp.body() ?: emptyList()
                presence = rows.associate { row ->
                    val state = when (row.status) {
                        "confirmed_present" -> PresenceCellState.PRESENT
                        "confirmed_absent" -> PresenceCellState.ABSENT
                        else -> PresenceCellState.UNKNOWN
                    }
                    val source = when (row.source) {
                        "auto_wifi" -> PresenceSource.AUTO_WIFI
                        "manual" -> PresenceSource.MANUAL
                        else -> PresenceSource.MANUAL // foyer_init -> traite comme pose
                    }
                    row.date to (state to source)
                }
            }
        } catch (_: Exception) {
            // best-effort : on garde la presence precedente.
        }
    }

    // Refetch activite au changement de mois OU d'user (couche etoile/serie).
    LaunchedEffect(visibleMonth, selectedUserId) {
        if (selectedUserId.isBlank()) {
            activityDays = emptySet()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val resp = withContext(Dispatchers.IO) {
                apiService.getActivityDays(
                    userId = selectedUserId,
                    from = rangeFrom.toString(),
                    to = rangeTo.toString(),
                )
            }
            if (resp.isSuccessful) {
                activityDays = (resp.body()?.days ?: emptyList())
                    .mapNotNull {
                        try {
                            LocalDate.parse(it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .toSet()
            } else {
                activityDays = emptySet()
            }
        } catch (_: Exception) {
            activityDays = emptySet()
        } finally {
            loading = false
        }
    }

    // P4b sweep 0.34.1 — runs serie depuis /streaks (calcul serveur sur
    // TOUT l'historique, regle v0.17). Refetch au changement d'user OU
    // apres une edition de presence (refreshTrigger : les absences
    // pontees changent les runs).
    LaunchedEffect(selectedUserId, refreshTrigger) {
        if (selectedUserId.isBlank()) {
            recordRunDays = emptySet()
            currentRunDays = emptySet()
            return@LaunchedEffect
        }
        try {
            val resp = withContext(Dispatchers.IO) { apiService.getStreaks() }
            if (resp.isSuccessful) {
                val s = resp.body()?.firstOrNull { it.userId == selectedUserId }
                fun parse(list: List<String>): Set<LocalDate> = list.mapNotNull {
                    try {
                        LocalDate.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
                recordRunDays = s?.let { parse(it.bestRun) } ?: emptySet()
                currentRunDays = s?.let { parse(it.currentRun) } ?: emptySet()
            }
        } catch (_: Exception) {
            // best-effort : pas de liseré si le fetch echoue.
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Présence & séries",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Selecteur d'user (chips). Ne recharge que la couche
                // etoile/serie (la presence reste foyer-globale).
                if (members.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(members) { m ->
                            FilterChip(
                                selected = m.userId == selectedUserId,
                                onClick = { selectedUserId = m.userId },
                                label = { Text(m.displayName) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Box {
                    PresenceCalendarGrid(
                        visibleMonth = visibleMonth,
                        statesByDate = presence,
                        onMonthChange = { visibleMonth = it },
                        // P4b : modal UNIQUE editable (one modal to rule them
                        // all). Tap -> PUT /presence + recalcul derive
                        // (home-freeze/urgence + serie/record) cf
                        // docs/cache-conventions.md regle 1.
                        onCellTap = { day, next ->
                            val status = when (next) {
                                PresenceCellState.PRESENT -> "present"
                                PresenceCellState.ABSENT -> "absent"
                                PresenceCellState.UNKNOWN -> "clear"
                            }
                            scope.launch {
                                try {
                                    val r = withContext(Dispatchers.IO) {
                                        apiService.putPresence(
                                            listOf(
                                                PresenceDayPut(
                                                    date = day.toString(),
                                                    status = status,
                                                ),
                                            ),
                                        )
                                    }
                                    if (r.isSuccessful) {
                                        refreshTrigger++
                                        withContext(Dispatchers.IO) {
                                            taskRepository.refreshTasks()
                                            taskRepository.refreshCompletions(
                                                limit = 2000,
                                            )
                                        }
                                    }
                                } catch (_: Exception) {
                                    // best-effort : online autoritaire, le
                                    // prochain GET re-aligne si besoin.
                                }
                            }
                        },
                        editable = true,
                        showLegend = true,
                        activityDays = activityDays,
                        showStreak = true,
                        recordRunDays = recordRunDays,
                        currentRunDays = currentRunDays,
                    )
                    if (loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                // P4b : plus de lien "Modifier la presence →" — CE modal EST
                // la surface d'edition (one modal to rule them all).
            }
        }
    }
}
