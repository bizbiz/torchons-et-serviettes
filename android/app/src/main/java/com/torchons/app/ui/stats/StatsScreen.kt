package com.torchons.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.filled.Favorite
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.UserStats
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
import com.torchons.app.util.TaskUrgencyCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onTaskClick: (String) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tabs = listOf("Resume", "Classement", "Badges", "Historique")

    // Etat pour l'ouverture du modal profil utilisateur.
    var userProfileRequest by remember { mutableStateOf<UserProfileRequest?>(null) }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.loadData(isRefresh = true) },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Text(
                                text = title,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            val onUserClick: (UserProfileRequest) -> Unit = { req -> userProfileRequest = req }
            val currentUserId by viewModel.userId.collectAsState()

            when (uiState.selectedTab) {
                0 -> ResumeTab(
                    uiState.stats,
                    uiState.streaks,
                    uiState.fairShare,
                    currentUserId,
                    onUserClick,
                )
                1 -> LeaderboardTab(
                    week = uiState.leaderboardWeek,
                    month = uiState.leaderboardMonth,
                    onUserClick = onUserClick
                )
                2 -> BadgesTab(uiState.badges)
                3 -> HistoryTab(
                    history = uiState.history,
                    onTaskClick = onTaskClick,
                    onUserClick = onUserClick
                )
            }
        }
    }

    // Modal profil utilisateur (affiche quand un nom est clique).
    userProfileRequest?.let { req ->
        UserProfileDialog(
            request = req,
            onDismiss = { userProfileRequest = null },
            onTaskClick = { taskId ->
                userProfileRequest = null
                onTaskClick(taskId)
            }
        )
    }
}

@Composable
private fun ResumeTab(
    stats: List<UserStats>,
    streaks: List<StreakInfo>,
    fairShare: List<FairShareStatus>,
    currentUserId: String?,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    val myFairShare = fairShare.find { it.userId == currentUserId }
    val otherFairShare = fairShare.find { it.userId != currentUserId }
    val showOverPerformingCard = myFairShare != null && myFairShare.aboveTarget && otherFairShare != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Message fair-share sur-performance (deplace depuis HomeScreen v0.8.0)
        if (showOverPerformingCard) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = TaskUrgencyCalculator.VERT.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = TaskUrgencyCalculator.VERT,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Tu as fait plus que ta part ces 30 derniers jours ! C'est a ${otherFairShare!!.displayName} de faire des taches. Mais tu peux quand meme continuer !",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TaskUrgencyCalculator.VERT
                        )
                    }
                }
            }
        }

        // Stats par user
        items(stats) { userStat ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = userStat.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            onUserClick(UserProfileRequest(userStat.userId, userStat.displayName))
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Barres de stats
                    StatRow("Semaine", userStat.pointsWeek, userStat.tasksWeek)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Mois", userStat.pointsMonth, userStat.tasksMonth)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Total", userStat.pointsTotal, userStat.tasksTotal)
                }
            }
        }

        // Streaks
        if (streaks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Streaks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(streaks) { streak ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (streak.currentStreak > 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = if (streak.currentStreak > 0)
                                TaskUrgencyCalculator.ORANGE else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = streak.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable {
                                    onUserClick(
                                        UserProfileRequest(streak.userId, streak.displayName)
                                    )
                                }
                            )
                            Text(
                                text = "Record : ${streak.bestStreak} jours",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${streak.currentStreak}j",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (streak.currentStreak > 0)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatRow(label: String, points: Int, tasks: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp)
        )
        Text(
            text = "${points}pts",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(60.dp)
        )
        Text(
            text = "$tasks taches",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LeaderboardTab(
    week: List<LeaderboardEntry>,
    month: List<LeaderboardEntry>,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Cette semaine",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        items(week) { entry -> LeaderboardRow(entry, onUserClick) }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Ce mois-ci",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        items(month) { entry -> LeaderboardRow(entry, onUserClick) }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.rank == 1) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rang
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.rank == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (entry.rank == 1) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "#${entry.rank}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        onUserClick(UserProfileRequest(entry.userId, entry.displayName))
                    }
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Score ${entry.normalizedScore.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${entry.points} pts bruts · ${entry.tasksCount} taches",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BadgesTab(badges: List<BadgeInfo>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val earned = badges.filter { it.earned }
        val locked = badges.filter { !it.earned }

        if (earned.isNotEmpty()) {
            item {
                Text(
                    text = "Gagnes (${earned.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(earned) { badge -> BadgeRow(badge) }
        }

        if (locked.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A debloquer (${locked.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(locked) { badge -> BadgeRow(badge) }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun BadgeRow(badge: BadgeInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (badge.earned) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (badge.earned) Icons.Default.Star else Icons.Default.Lock,
                contentDescription = null,
                tint = if (badge.earned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = badge.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (badge.earned) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<Map<String, Any>>,
    onTaskClick: (String) -> Unit,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Aucun historique", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            Card(
                modifier = cardModifier,
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(icon, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // Ligne 1 : nom cliquable + reste de la description.
                        // Si on a un actor avec id/name, on rend son nom cliquable ;
                        // sinon on affiche la description brute.
                        if (actorId != null && actorName != null && desc.startsWith(actorName)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = actorName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        onUserClick(UserProfileRequest(actorId, actorName))
                                    }
                                )
                                Text(
                                    text = desc.removePrefix(actorName),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(desc, style = MaterialTheme.typography.bodyMedium)
                        }
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
