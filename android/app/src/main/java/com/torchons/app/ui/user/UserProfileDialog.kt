package com.torchons.app.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.CoupleTotalsResponse
import com.torchons.app.data.model.LeaderboardEntry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EntryPoint Hilt pour acceder a ApiService depuis un composable sans ViewModel.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UserProfileApiEntryPoint {
    fun apiService(): ApiService
}

/**
 * Etat d'une ouverture de profil utilisateur. Permet a n'importe quel ecran
 * d'afficher un modal en un seul remember + appel.
 */
data class UserProfileRequest(val userId: String, val userName: String)

/**
 * Modal qui affiche le profil d'un utilisateur : nom, compteurs et historique
 * personnel recent. Fait son propre fetch au moment de l'ouverture.
 */
@Composable
fun UserProfileDialog(
    request: UserProfileRequest,
    onDismiss: () -> Unit,
    onTaskClick: ((String) -> Unit)? = null,
    /**
     * P3 (design decision 10) : si fourni, affiche un lien "Voir le calendrier
     * des series" qui ouvre StreakCalendarModal pour cet user. Null = lien
     * masque (callers hors Stats qui ne cablent pas le modal).
     */
    onOpenStreakCalendar: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apiService = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            UserProfileApiEntryPoint::class.java
        ).apiService()
    }

    var loading by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    // Leaderboards foyer (semaine + total) pour la section "Stats du couple".
    // Pas bloquant : la dialog s'affiche meme si ces fetch echouent.
    var leaderboardWeek by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var leaderboardAll by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    // v0.31 — totaux foyer + couple_streak (item roadmap "Stats globales du couple").
    var coupleTotals by remember { mutableStateOf<CoupleTotalsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request.userId) {
        loading = true
        error = null
        try {
            val resp = withContext(Dispatchers.IO) {
                apiService.getUserHistory(request.userId, limit = 30)
            }
            if (resp.isSuccessful) {
                history = resp.body() ?: emptyList()
            } else {
                error = context.getString(R.string.common_error_code_format, resp.code())
            }
            // Fetchs paralleles pour les leaderboards (best-effort).
            try {
                val w = withContext(Dispatchers.IO) { apiService.getLeaderboard("week") }
                if (w.isSuccessful) leaderboardWeek = w.body() ?: emptyList()
            } catch (_: Exception) { /* silencieux */ }
            try {
                val a = withContext(Dispatchers.IO) { apiService.getLeaderboard("all") }
                if (a.isSuccessful) leaderboardAll = a.body() ?: emptyList()
            } catch (_: Exception) { /* silencieux */ }
            // v0.31 — totaux foyer + couple_streak.
            try {
                val ct = withContext(Dispatchers.IO) { apiService.getCoupleTotals() }
                if (ct.isSuccessful) coupleTotals = ct.body()
            } catch (_: Exception) { /* silencieux */ }
        } catch (e: Exception) {
            error = e.message ?: context.getString(R.string.common_error_network)
        } finally {
            loading = false
        }
    }

    // Stats derivees : compte des completions + total de points.
    val completionEvents = history.filter { (it["type"] as? String) == "completion" }
    val completionsCount = completionEvents.size
    val totalPoints = completionEvents.sumOf {
        val meta = it["meta"] as? Map<*, *>
        (meta?.get("points") as? Number)?.toInt() ?: 0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar pastille avec l'initiale
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = request.userName.firstOrNull()?.uppercase()
                            ?: stringResource(R.string.user_profile_unknown_initial),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = request.userName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                // Bandeau compteurs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatPill(label = stringResource(R.string.user_profile_stat_actions), value = history.size.toString())
                    StatPill(label = stringResource(R.string.user_profile_stat_completions), value = completionsCount.toString())
                    StatPill(label = stringResource(R.string.user_profile_stat_points), value = stringResource(R.string.user_profile_stat_points_value_format, totalPoints))
                }

                // P3 (decision 10) : lien vers le calendrier des series de ce
                // membre (modal lecture seule). Affiche seulement si l'hote
                // a cable le callback (Stats).
                if (onOpenStreakCalendar != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onOpenStreakCalendar,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.user_profile_streak_calendar_link))
                    }
                }

                // v0.31 — Section "Total du foyer" : numbers absolus +
                // streak couple. Item roadmap "Stats globales du couple".
                coupleTotals?.let { totals ->
                    Spacer(Modifier.height(12.dp))
                    CoupleTotalsCard(totals = totals)
                }

                // Section "Stats du couple" : repartition entre les 2 membres
                // sur la semaine et sur tout le temps. Affichee uniquement si
                // au moins un des leaderboards a ete charge avec >= 2 entrees.
                if (leaderboardWeek.size >= 2 || leaderboardAll.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.user_profile_section_split),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (leaderboardWeek.size >= 2) {
                        FoyerSplitBar(
                            label = stringResource(R.string.user_profile_split_week),
                            entries = leaderboardWeek,
                            highlightedUserId = request.userId,
                        )
                    }
                    if (leaderboardAll.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        FoyerSplitBar(
                            label = stringResource(R.string.user_profile_split_total),
                            entries = leaderboardAll,
                            highlightedUserId = request.userId,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.user_profile_section_history),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))

                when {
                    loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    history.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.user_profile_empty_history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(history.size) { idx ->
                                HistoryRow(
                                    event = history[idx],
                                    onTaskClick = { taskId ->
                                        onTaskClick?.invoke(taskId)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/** Palette stable couleurs user — alignee avec les charts de Stats. */
private val FoyerPalette = listOf(
    Color(0xFF6366F1),  // Indigo
    Color(0xFFEC4899),  // Rose
    Color(0xFF10B981),  // Vert
    Color(0xFFF59E0B),  // Jaune
)

/**
 * Barre horizontale segmentee : 1 segment par user, largeur ∝ points. Sous
 * la barre, une legende compacte "Nom XX% (N pts)". Le user du profil
 * courant est mis en gras pour le reperage immediat.
 */
@Composable
private fun FoyerSplitBar(
    label: String,
    entries: List<LeaderboardEntry>,
    highlightedUserId: String,
) {
    // On ordonne stable (par display_name) plutot que par rang pour eviter
    // que la barre change de couleurs entre semaine/total.
    val ordered = entries.sortedBy { it.displayName }
    val total = ordered.sumOf { it.points }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (total <= 0) {
            Text(
                text = stringResource(R.string.user_profile_split_zero_points),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        // Barre segmentee : un Row qui distribue les segments par poids.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ordered.forEachIndexed { i, e ->
                if (e.points > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(e.points.toFloat())
                            .background(FoyerPalette[i % FoyerPalette.size]),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Legende : noms + % + pts bruts. Mise en gras du user courant.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ordered.forEachIndexed { i, e ->
                val pct = (e.points * 100.0 / total).toInt()
                val isMe = e.userId == highlightedUserId
                Text(
                    text = stringResource(R.string.user_profile_split_legend_format, e.displayName, pct, e.points),
                    style = MaterialTheme.typography.labelSmall,
                    color = FoyerPalette[i % FoyerPalette.size],
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(
    event: Map<String, Any>,
    onTaskClick: (String) -> Unit,
) {
    val type = event["type"] as? String ?: ""
    val desc = event["description"] as? String ?: ""
    val date = event["date"] as? String ?: ""
    val task = event["task"] as? Map<*, *>
    val taskId = task?.get("id") as? String

    val icon = when (type) {
        "completion" -> "✅"
        "affinity" -> "❤️"
        "effort" -> "⏱️"
        "task_created" -> "➕"
        "task_updated" -> "✏️"
        "task_deleted" -> "🗑️"
        "category_changed" -> "🏷️"
        else -> "•"
    }
    val formattedDate = try {
        val instant = Instant.parse(date)
        val local = instant.atZone(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { date.take(16) }

    val clickMod = if (taskId != null) Modifier.clickable { onTaskClick(taskId) } else Modifier

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickMod),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card "Total du foyer" affichee dans UserProfileDialog (v0.31).
 *
 * Item roadmap "Stats globales du couple" : numbers absolus du foyer
 * (sum de tous les members) + streak couple (jours consecutifs avec >=1
 * completion par n'importe qui).
 */
@Composable
private fun CoupleTotalsCard(totals: com.torchons.app.data.model.CoupleTotalsResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.user_profile_couple_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (totals.coupleStreakDays > 0) {
                    Text(
                        text = stringResource(R.string.user_profile_couple_streak_format, totals.coupleStreakDays),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CoupleStatColumn(
                    title = stringResource(R.string.user_profile_couple_week),
                    completions = totals.foyerWeekCompletions,
                    points = totals.foyerWeekPoints,
                )
                CoupleStatColumn(
                    title = stringResource(R.string.user_profile_couple_month),
                    completions = totals.foyerMonthCompletions,
                    points = totals.foyerMonthPoints,
                )
                CoupleStatColumn(
                    title = stringResource(R.string.user_profile_couple_total),
                    completions = totals.foyerTotalCompletions,
                    points = totals.foyerTotalPoints,
                )
            }
        }
    }
}

@Composable
private fun CoupleStatColumn(title: String, completions: Int, points: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = completions.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.user_profile_couple_points_format, points),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Composable helper : affiche un nom d'utilisateur cliquable qui ouvre le
 * modal [UserProfileDialog] au tap. Usage :
 * ```
 * ClickableUserName(userId = "...", userName = "Alice", onTaskClick = ...)
 * ```
 */
@Composable
fun ClickableUserName(
    userId: String,
    userName: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.primary,
    onTaskClick: ((String) -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    Text(
        text = userName,
        style = style,
        color = color,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        UserProfileDialog(
            request = UserProfileRequest(userId = userId, userName = userName),
            onDismiss = { showDialog = false },
            onTaskClick = onTaskClick,
        )
    }
}
