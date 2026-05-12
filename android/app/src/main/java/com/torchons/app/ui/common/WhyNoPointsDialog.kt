package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dialog dynamique "Pourquoi je n'ai pas de points pour certaines taches ?"
 * (v0.32). Surface uniquement quand l'user est dans un etat opt-out (lui
 * meme OU au moins un de ses foyers).
 *
 * Quatre cas couverts (cf grill-me Q8) :
 *
 * 1. **userOff seul** : l'user a desactive sa gamification dans Reglages →
 *    message direct + bouton "Reactiver maintenant".
 *
 * 2. **un foyer off seul** : la gamif est desactivee dans un foyer (par
 *    lui ou par un autre membre) → on indique le foyer + bouton pour
 *    aller le reactiver.
 *
 * 3. **multi-foyer mixte** : l'user est dans plusieurs foyers, certains
 *    actives, d'autres non → liste verticale des etats avec actions.
 *
 * 4. **les deux cumules** : userOff + foyer off → on explique que les
 *    deux causes se cumulent et qu'il faut reactiver les deux.
 *
 * Wording user-friendly : on dit "tu as desactive" / "est desactivee",
 * jamais "opt-out" (terme trop technique).
 *
 * @param userOff true si UserSettings.gamificationEnabled = false.
 * @param foyers liste des foyers de l'user avec leur etat gamif. La carte
 *   `isCurrent` identifie le foyer courant — non utilise ici mais permet
 *   au caller de prioriser le foyer courant dans l'affichage.
 * @param onReactivateUser action quand l'user clique sur "Reactiver mon
 *   compte" — typiquement flip le toggle user-level dans SettingsViewModel.
 * @param onReactivateFoyer action quand l'user clique sur "Reactiver le
 *   foyer X" — typiquement flip le toggle foyer-level si c'est le foyer
 *   courant, ou ouvre la liste des foyers / switch si c'est un autre foyer.
 */
@Composable
fun WhyNoPointsDialog(
    userOff: Boolean,
    foyers: List<FoyerGamifState>,
    onReactivateUser: () -> Unit,
    onReactivateFoyer: (foyerId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val foyersOff = foyers.filter { !it.gamificationEnabled }
    val foyersOn = foyers.filter { it.gamificationEnabled }
    val multiFoyer = foyers.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pourquoi je n'ai pas de points ?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    // Cas 4 : userOff + au moins un foyer off
                    userOff && foyersOff.isNotEmpty() -> {
                        Text(
                            text = "Deux causes se cumulent :",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Bullet("Tu as desactive la gamification pour ton compte.")
                        Bullet(
                            if (foyersOff.size == 1)
                                "Le foyer \"${foyersOff[0].name}\" a aussi sa gamification desactivee."
                            else
                                "Plusieurs de tes foyers ont aussi leur gamification desactivee (${foyersOff.joinToString(", ") { it.name }})."
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Reactiver une seule cause ne suffira pas — il faut activer la gamification cote compte ET cote foyer pour voir les points.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onReactivateUser,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Reactiver mon compte") }
                    }
                    // Cas 1 : userOff seul
                    userOff -> {
                        Text(
                            text = "Tu as desactive la gamification pour ton compte dans tes Reglages.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tes foyers ont tous leur gamification activee, mais ton choix personnel prend le dessus — tu ne vois pas de points, de stats ni de bonus tant que tu n'as pas reactive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onReactivateUser,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Reactiver maintenant") }
                    }
                    // Cas 3 : multi-foyer mixte (ton compte est ON, mais
                    // certains foyers sont OFF)
                    multiFoyer && foyersOff.isNotEmpty() -> {
                        Text(
                            text = "Ton compte a la gamification activee, mais certains de tes foyers ne l'ont pas. Tu vois des points dans les foyers actives, et rien dans les autres.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Tes foyers :", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        foyersOff.forEach { f ->
                            FoyerStateRow(
                                name = f.name,
                                enabled = false,
                                isCurrent = f.isCurrent,
                                onAction = { onReactivateFoyer(f.id) },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        foyersOn.forEach { f ->
                            FoyerStateRow(
                                name = f.name,
                                enabled = true,
                                isCurrent = f.isCurrent,
                                onAction = {},
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    // Cas 2 : un seul foyer off (mono-foyer)
                    foyersOff.isNotEmpty() -> {
                        val f = foyersOff[0]
                        Text(
                            text = "La gamification est desactivee dans le foyer \"${f.name}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Toi ou un autre membre du foyer peut la reactiver dans Reglages > Options avancees > Gamification du foyer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onReactivateFoyer(f.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Reactiver le foyer \"${f.name}\"") }
                    }
                    // Fallback (ne devrait pas etre atteint vu la condition
                    // d'affichage du lien, mais defensif)
                    else -> {
                        Text(
                            text = "Aucune raison detectee. Si tu vois ce message alors que tu as des points en attente, tente un refresh ou contacte l'autre membre du foyer.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("• ", style = MaterialTheme.typography.bodyMedium)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FoyerStateRow(
    name: String,
    enabled: Boolean,
    isCurrent: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "✓" else "✗",
            color = if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name + if (isCurrent) " (actuel)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (enabled) "Activee" else "Desactivee",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!enabled) {
            OutlinedButton(onClick = onAction) {
                Text("Reactiver", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Etat compact d'un foyer pour l'affichage WhyNoPointsDialog. */
data class FoyerGamifState(
    val id: String,
    val name: String,
    val gamificationEnabled: Boolean,
    val isCurrent: Boolean,
)
