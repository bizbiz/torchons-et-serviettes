package com.torchons.app.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.torchons.app.data.api.ApiService
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
                error = "Erreur ${resp.code()}"
            }
        } catch (e: Exception) {
            error = e.message ?: "Erreur reseau"
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
            TextButton(onClick = onDismiss) { Text("Fermer") }
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
                        text = request.userName.firstOrNull()?.uppercase() ?: "?",
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
                    StatPill(label = "Actions", value = history.size.toString())
                    StatPill(label = "Completions", value = completionsCount.toString())
                    StatPill(label = "Points", value = "+$totalPoints")
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Historique recent",
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
                            text = "Aucun historique pour l'instant",
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
 * Composable helper : affiche un nom d'utilisateur cliquable qui ouvre le
 * modal [UserProfileDialog] au tap. Usage :
 * ```
 * ClickableUserName(userId = "...", userName = "Quentin", onTaskClick = ...)
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
