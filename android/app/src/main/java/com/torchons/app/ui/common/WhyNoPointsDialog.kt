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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * Dialog dynamique "Pourquoi je n'ai pas de points pour certaines taches ?"
 * (v0.32). Surface uniquement quand l'user est dans un etat opt-out (lui
 * meme OU au moins un de ses foyers).
 *
 * Quatre cas couverts (cf design review Q8) :
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
        title = { Text(stringResource(R.string.why_no_points_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    // Cas 4 : userOff + au moins un foyer off
                    userOff && foyersOff.isNotEmpty() -> {
                        Text(
                            text = stringResource(R.string.why_no_points_two_causes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Bullet(stringResource(R.string.why_no_points_bullet_user_off))
                        Bullet(
                            if (foyersOff.size == 1)
                                stringResource(R.string.why_no_points_bullet_one_foyer_off_format, foyersOff[0].name)
                            else
                                stringResource(
                                    R.string.why_no_points_bullet_many_foyers_off_format,
                                    foyersOff.joinToString(", ") { it.name },
                                )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.why_no_points_two_causes_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onReactivateUser,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.why_no_points_button_reactivate_account)) }
                    }
                    // Cas 1 : userOff seul
                    userOff -> {
                        Text(
                            text = stringResource(R.string.why_no_points_user_off_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.why_no_points_user_off_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onReactivateUser,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.why_no_points_button_reactivate_now)) }
                    }
                    // Cas 3 : multi-foyer mixte (ton compte est ON, mais
                    // certains foyers sont OFF)
                    multiFoyer && foyersOff.isNotEmpty() -> {
                        Text(
                            text = stringResource(R.string.why_no_points_multi_mixed_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.why_no_points_your_foyers),
                            style = MaterialTheme.typography.labelMedium,
                        )
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
                            text = stringResource(R.string.why_no_points_single_foyer_off_format, f.name),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.why_no_points_single_foyer_off_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onReactivateFoyer(f.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.why_no_points_button_reactivate_foyer_format, f.name))
                        }
                    }
                    // Fallback (ne devrait pas etre atteint vu la condition
                    // d'affichage du lien, mais defensif)
                    else -> {
                        Text(
                            text = stringResource(R.string.why_no_points_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
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
                text = name + if (isCurrent) stringResource(R.string.why_no_points_foyer_current_suffix) else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (enabled) stringResource(R.string.why_no_points_foyer_enabled)
                else stringResource(R.string.why_no_points_foyer_disabled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!enabled) {
            OutlinedButton(onClick = onAction) {
                Text(
                    stringResource(R.string.why_no_points_foyer_reactivate_compact),
                    style = MaterialTheme.typography.labelSmall,
                )
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
