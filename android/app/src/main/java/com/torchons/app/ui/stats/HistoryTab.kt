package com.torchons.app.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.shouldShowEmptyState
import com.torchons.app.ui.common.submitKeyboardActions
import com.torchons.app.ui.user.UserProfileRequest

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryTab(
    history: List<Map<String, Any>>,
    filters: HistoryFilters,
    isHistoryLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    users: List<Pair<String, String>>,        // (userId, displayName)
    onQueryChange: (String) -> Unit,
    onToggleFamily: (String) -> Unit,
    onSetActor: (String?) -> Unit,
    onSetPeriod: (HistoryPeriod) -> Unit,
    onClearFilters: () -> Unit,
    onTaskClick: (String) -> Unit,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    val activeFilterCount = listOf(
        filters.families.isNotEmpty(),
        filters.actorId != null,
        filters.period != HistoryPeriod.ALL,
        filters.dayFilter != null,
    ).count { it }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Chip dedie "Activites du JJ/MM" — visible uniquement quand on
        // arrive ici via tap-on-point d'un graphique. Cliquer sur la croix
        // efface ce filtre (et tous les autres, geste "retour a la normale").
        if (filters.dayFilter != null) {
            val isoDate = filters.dayFilter
            val pretty = remember(isoDate) {
                try {
                    val d = java.time.LocalDate.parse(isoDate)
                    d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                } catch (_: Exception) { isoDate }
            }
            FilterChip(
                selected = true,
                onClick = { onClearFilters() },
                label = { Text(stringResource(R.string.stats_history_day_filter_format, pretty)) },
                trailingIcon = {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.stats_history_clear_day_filter), modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // Barre de recherche persistante en haut.
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.stats_history_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.stats_history_clear_search))
                    }
                }
            },
            singleLine = true,
            keyboardActions = submitKeyboardActions(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Bouton "Filtres" (avec badge si filtres actifs) + lien "Effacer".
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge { Text(activeFilterCount.toString()) }
                    }
                }
            ) {
                OutlinedButton(onClick = { showFilters = !showFilters }) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (showFilters) stringResource(R.string.stats_history_filters_hide)
                        else stringResource(R.string.stats_history_filters_show)
                    )
                }
            }
            if (filters.hasActiveFilters || filters.query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    onClearFilters()
                    if (filters.query.isNotEmpty()) onQueryChange("")
                }) { Text(stringResource(R.string.stats_history_clear_filters)) }
            }
        }

        // Panneau pliable des filtres.
        AnimatedVisibility(visible = showFilters) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                // --- Type (familles) ---
                Text(
                    stringResource(R.string.stats_history_filter_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "completions" to stringResource(R.string.stats_history_filter_type_completions),
                        "votes" to stringResource(R.string.stats_history_filter_type_votes),
                        "modifications" to stringResource(R.string.stats_history_filter_type_modifications),
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = filters.families.contains(key),
                            onClick = { onToggleFamily(key) },
                            label = { Text(label) },
                        )
                    }
                }

                // --- Acteur ---
                Text(
                    stringResource(R.string.stats_history_filter_actor),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = filters.actorId == null,
                        onClick = { onSetActor(null) },
                        label = { Text(stringResource(R.string.stats_history_filter_actor_all)) },
                    )
                    users.forEach { (uid, name) ->
                        FilterChip(
                            selected = filters.actorId == uid,
                            onClick = { onSetActor(uid) },
                            label = { Text(name) },
                        )
                    }
                }

                // --- Periode ---
                Text(
                    stringResource(R.string.stats_history_filter_period),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        HistoryPeriod.WEEK to stringResource(R.string.stats_history_filter_period_week),
                        HistoryPeriod.MONTH to stringResource(R.string.stats_history_filter_period_month),
                        HistoryPeriod.THREE_MONTHS to stringResource(R.string.stats_history_filter_period_three_months),
                        HistoryPeriod.ALL to stringResource(R.string.stats_history_filter_period_all),
                    ).forEach { (period, label) ->
                        FilterChip(
                            selected = filters.period == period,
                            onClick = { onSetPeriod(period) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // Indicateur de chargement subtil pendant les refetch (filtres).
        if (isHistoryLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (history.isEmpty() && loadFailed) {
                item { HistoryLoadFailed(onRetry) }
            } else if (shouldShowEmptyState(isHistoryLoading, loadFailed, history.isEmpty())) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (filters.hasActiveFilters || filters.query.isNotEmpty())
                                stringResource(R.string.stats_history_empty_filtered)
                            else stringResource(R.string.stats_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(history.size) { index ->
            val event = history[index]
            val type = event["type"] as? String ?: ""
            val desc = event["description"] as? String ?: ""
            val date = event["date"] as? String ?: ""
            val actor = event["actor"] as? Map<*, *>
            val actorId = actor?.get("id") as? String
            val actorName = actor?.get("name") as? String
            val task = event["task"] as? Map<*, *>
            val taskId = task?.get("id") as? String
            val taskTitle = task?.get("title") as? String
            val meta = event["meta"] as? Map<*, *>
            // Pour un collectif, meta porte la liste complete (ids + noms)
            // pour rendre chaque participant cliquable. Sinon on retombe
            // sur le seul actor (cas solo et autres types d'evenements).
            val participantIds = (meta?.get("participant_ids") as? List<*>)?.mapNotNull { it as? String }
            val participantNames = (meta?.get("participant_names") as? List<*>)?.mapNotNull { it as? String }
            val clickables: List<Pair<String, String>> = when {
                participantIds != null && participantNames != null
                    && participantIds.size == participantNames.size ->
                    participantIds.zip(participantNames)
                actorId != null && actorName != null -> listOf(actorId to actorName)
                else -> emptyList()
            }

            val icon = when (type) {
                "completion" -> "✅"
                "affinity" -> "❤️"
                "effort" -> "⏱️"
                "task_created" -> "➕"
                "task_updated" -> "✏️"
                "task_deleted" -> "🗑️"
                "category_changed" -> "🏷️"
                "completion_edited" -> "✏️"
                "completion_deleted" -> "🗑️"
                "modification" -> "✏️" // retro-compat ancien backend
                else -> "•"
            }
            val formattedDate = try {
                val instant = java.time.Instant.parse(date)
                val local = instant.atZone(java.time.ZoneId.systemDefault())
                local.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            } catch (_: Exception) { date.take(16) }

            // La Card est cliquable vers la tache si on a un taskId.
            val cardModifier = if (taskId != null) {
                Modifier
                    .fillMaxWidth()
                    .clickable { onTaskClick(taskId) }
            } else {
                Modifier.fillMaxWidth()
            }

            // Construction d'un AnnotatedString unique pour la description :
            // chaque participant est rendu cliquable (souligne + couleur primaire)
            // dans un seul Text → baseline coherente, plus de decalage vertical.
            val primaryColor = MaterialTheme.colorScheme.primary
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface
            val descAnnotated = remember(desc, clickables, primaryColor, onSurfaceColor) {
                buildAnnotatedString {
                    append(desc)
                    addStyle(SpanStyle(color = onSurfaceColor), 0, desc.length)
                    // Reperer chaque nom dans l'ordre, sans chevauchement.
                    var cursor = 0
                    for ((uid, name) in clickables) {
                        val idx = desc.indexOf(name, startIndex = cursor)
                        if (idx >= 0) {
                            addStyle(
                                SpanStyle(
                                    color = primaryColor,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                idx, idx + name.length,
                            )
                            addStringAnnotation(
                                tag = "user",
                                annotation = uid,
                                start = idx, end = idx + name.length,
                            )
                            cursor = idx + name.length
                        }
                    }
                }
            }

            Card(
                modifier = cardModifier,
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(icon, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        ClickableText(
                            text = descAnnotated,
                            style = MaterialTheme.typography.bodyMedium.copy(color = onSurfaceColor),
                            onClick = { offset ->
                                val ann = descAnnotated.getStringAnnotations(
                                    tag = "user", start = offset, end = offset
                                ).firstOrNull()
                                if (ann != null) {
                                    val name = descAnnotated.text
                                        .substring(ann.start, ann.end)
                                    onUserClick(UserProfileRequest(ann.item, name))
                                } else if (taskId != null) {
                                    // Fallback : si on tape ailleurs sur la ligne,
                                    // on ouvre la tache (comme la card cliquable).
                                    onTaskClick(taskId)
                                }
                            },
                        )
                        Text(
                            formattedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** The history could not be loaded: says so (never "Aucun historique") and offers to try again. */
@Composable
private fun HistoryLoadFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stats_history_load_failed),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.stats_history_retry)) }
    }
}
