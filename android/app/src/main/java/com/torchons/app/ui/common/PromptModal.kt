package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.UserPrompt

/**
 * Dispatcher de modal pour la queue unifiee user_prompts. Affiche le bon
 * composable selon le type du prompt.
 *
 * Types :
 * - BUMP_EFFORT : modal dedie avec liste completions ; Revoter enchaine les
 *   votes de frequence et d'effort (HomePromptActions.startBumpRevote).
 * - RESOLVE_PRESENCE : delegue au caller via onOpenPresenceCalendar qui
 *   ouvre StreakCalendarModal (one modal to rule them all). Le modal
 *   edite la presence directement via PUT /presence (taps); a la
 *   fermeture, le caller resout le prompt en ANSWERED (les changements
 *   sont deja persistes).
 * - VOTE_FREQUENCY (v0.28) : delegate au caller via onOpenFrequencyVote
 *   qui doit fetch /frequency-votes et ouvrir FrequencyVoteDialog avec
 *   le bandeau "X t'a demande de revoter" (requesterName du context).
 * - VOTE_EFFORT (v0.28) : pareil avec EffortVoteDialog.
 * - DISCOVER_MODULE (P4) : propose d'activer un module. Seul type
 *   qui ne demande pas une info mais propose d'elargir le perimetre,
 *   et seul a etre entierement self-contained (aucun callback vers un
 *   autre ecran : accepter suffit, le backend active).
 */
@Composable
fun PromptModal(
    prompt: UserPrompt,
    onDismiss: () -> Unit,
    onBumpRevote: () -> Unit,
    onBumpLater: () -> Unit,
    onBumpIgnore: () -> Unit,
    onOpenPresenceCalendar: () -> Unit = {},
    onOpenFrequencyVote: (taskId: String, requesterName: String) -> Unit = { _, _ -> },
    onOpenEffortVote: (taskId: String, requesterName: String) -> Unit = { _, _ -> },
    /** P4 — l'user accepte la proposition : le backend active le module. */
    onAcceptModule: () -> Unit = {},
    /** P4 — l'user refuse. Le module reste joignable dans Reglages. */
    onDeclineModule: () -> Unit = {},
) {
    when (prompt.type) {
        "BUMP_EFFORT" -> BumpEffortDialog(
            prompt = prompt,
            onRevote = onBumpRevote,
            onLater = onBumpLater,
            onIgnore = onBumpIgnore,
        )
        "RESOLVE_PRESENCE" -> {
            // P4b.2 (one modal to rule them all) : delegue au caller pour
            // ouvrir StreakCalendarModal au lieu d'un dialog dedie. Les
            // edits de presence se font via PUT /presence (taps dans le
            // modal); a la fermeture, le caller resout le prompt en
            // ANSWERED sans payload (les jours sont deja persistes).
            onOpenPresenceCalendar()
        }
        "VOTE_FREQUENCY" -> {
            val taskId = prompt.context["task_id"]?.toString().orEmpty()
            val requester = prompt.context["requester_name"]?.toString() ?: "?"
            onOpenFrequencyVote(taskId, requester)
        }
        "VOTE_EFFORT" -> {
            val taskId = prompt.context["task_id"]?.toString().orEmpty()
            val requester = prompt.context["requester_name"]?.toString() ?: "?"
            onOpenEffortVote(taskId, requester)
        }
        "DISCOVER_MODULE" -> DiscoverModuleDialog(
            prompt = prompt,
            onAccept = onAcceptModule,
            onDecline = onDeclineModule,
        )
        else -> onDismiss()  // type inconnu — fermer
    }
}

/**
 * Modal dedie pour BUMP_EFFORT : cette tache est systematiquement en retard.
 *
 * Contenu :
 * - Titre + nom de la tache
 * - Liste des N dernieres completions (qui, quand, en retard ?)
 * - Stats : "X% des completions sont en zone bonus sur Y jours"
 * - Revoter : votes de frequence puis d'effort, enchaines (grill du 2026-11-19 ;
 *   « +1 effort » et « Modifier les notes » retires, le second n'ouvrait rien
 *   et laissait la question suivante s'afficher a sa place).
 * - Plus tard : reposee au prochain passage en retard. Aussi le retour et le
 *   tap hors de la boite, le geste le plus doux.
 * - Ignorer : close jusqu'a la fin du delai de grace.
 */
@Composable
private fun BumpEffortDialog(
    prompt: UserPrompt,
    onRevote: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
) {
    val ctx = prompt.context
    val taskTitle = ctx["task_title"]?.toString() ?: "?"
    val windowDays = (ctx["window_days"] as? Number)?.toInt() ?: 0
    val bonusRatio = (ctx["bonus_ratio"] as? Number)?.toFloat() ?: 0f
    @Suppress("UNCHECKED_CAST")
    val completions = (ctx["completions"] as? List<Map<String, Any?>>) ?: emptyList()

    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.bump_effort_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        R.string.bump_effort_stats_format,
                        (bonusRatio * 100).toInt(),
                        windowDays,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bump_effort_last_completions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))

                completions.take(6).forEach { comp ->
                    val reason = comp["reason"]?.toString() ?: "NEUTRAL"
                    val isBonus = reason == "BONUS_RETARD"
                    val at = comp["completed_at"]?.toString()?.take(10) ?: "?"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isBonus) "🔥" else "✅",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = at,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (isBonus) {
                            Text(
                                text = stringResource(R.string.bump_effort_late_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bump_effort_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onRevote,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bump_effort_button_revote)) }

                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bump_effort_button_later)) }

                TextButton(
                    onClick = onIgnore,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.bump_effort_button_ignore)) }
            }
        },
        dismissButton = {},
    )
}

/**
 * P4 — proposition d'activer un module, declenchee par un signal reel.
 *
 * Tout le texte vient du `context` du prompt, fige a la creation cote serveur
 * depuis modules.yaml. Rien n'est recalcule ici : c'est la convention de la
 * queue, et c'est ce qui garantit que la proposition dit exactement ce que
 * disait le module au moment ou le signal a eu lieu.
 *
 * Deux issues seulement, et pas de "plus tard" : le budget s'en charge deja
 * (une proposition par semaine, silence de 30 jours apres un refus, abandon au
 * second). Ajouter un troisieme bouton reporterait une decision que le
 * mecanisme reporte deja tout seul.
 */
@Composable
private fun DiscoverModuleDialog(
    prompt: UserPrompt,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val moduleKey = prompt.context["module_key"]?.toString().orEmpty()
    val guide = guideFromPromptContext(moduleKey, prompt.context)
    val title = guide.title
    val pitch = guide.pitch
    val details = guide.details
    val guideVm: ModuleGuideViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    // The guide sections (why / how) are on screen: record it as seen, once
    // per prompt. Prompts created before the server sent them have no guide
    // text, so nothing was shown and nothing is recorded.
    LaunchedEffect(prompt.id) {
        if (moduleKey.isNotBlank() && (guide.why.isNotBlank() || guide.how.isNotBlank())) {
            guideVm.markSeen(moduleKey)
        }
    }

    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.discover_module_overline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (pitch.isNotBlank()) {
                    Text(text = pitch, style = MaterialTheme.typography.bodyMedium)
                }
                if (details.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModuleGuideSections(guide = guide, includePurpose = false)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.discover_module_reassurance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.discover_module_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.discover_module_decline))
            }
        },
    )
}
