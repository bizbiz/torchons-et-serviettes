package com.torchons.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.torchons.app.ui.common.ActionErrorDialog
import com.torchons.app.ui.common.LocalIsOnline
import com.torchons.app.ui.common.NetworkNeededSlot
import com.torchons.app.ui.common.PrimaryTopBar
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.home.ProjectionSimulationDialog
import com.torchons.app.ui.stats.StreakCalendarModal
import com.torchons.app.ui.stats.StreakMember
import com.torchons.app.util.WifiHelper

/**
 * Ecran "Foyer" (P1 refonte).
 *
 * Reunit toutes les options foyer-scopees ET les options compte-scopees qui
 * etaient eparpillees dans Reglages. Deux groupes etiquetes, ordre
 * simple -> complexe FIGE :
 *  - "Ce foyer" : nom, inviter, changer de foyer, pieces, categories,
 *    presence & absences, gamification du foyer, simuler le rattrapage,
 *    bonus/malus, quitter le foyer.
 *  - "Mon compte · vaut pour tous mes foyers" : mes appareils, code de
 *    recuperation, renouvellement affinite, tolerance fair-share.
 *
 * Reutilise SettingsViewModel (aucun VM parallele) — tout l'etat/mutations
 * existent deja (etendus en P1 pour rename foyer, home freeze, switch foyer,
 * donnees de simulation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoyerSettingsScreen(
    onBack: () -> Unit,
    onManageContexts: () -> Unit,
    onInviteSomeone: () -> Unit,
    onAddDevice: () -> Unit,
    onLeftFoyer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isOnline = LocalIsOnline.current
    val s by viewModel.settings.collectAsState()
    val currentFoyer by viewModel.currentFoyer.collectAsState()
    val allFoyers by viewModel.allFoyers.collectAsState()
    val users by viewModel.users.collectAsState()
    val userId by viewModel.userId.collectAsState()

    // P4a.1 Bug A — l'instance SettingsViewModel de CET ecran est distincte
    // de celle de SettingsScreen (hiltViewModel par backstack) : re-fetch
    // les foyers a l'apparition pour que "Changer de foyer" reflete un
    // foyer cree/quitte ailleurs SANS refresh manuel.
    LaunchedEffect(Unit) { viewModel.refreshFoyers() }

    val foyerName = currentFoyer?.name ?: stringResource(R.string.settings_foyer_default_name)
    val homeSsids = s.homeSsids
    val affinityExpiry = s.affinityExpiry
    val fairShareTolerance = s.fairShareTolerance
    val homeFreezeEnabled = currentFoyer?.homeFreezeEnabled ?: false

    var showWifiExplanation by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSimulationDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // P4b — le calendrier n'est plus inline : un bouton ouvre LE modal
    // unique "Présence & séries" (design decision 5 v3).
    var showPresenceModal by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val ssid = WifiHelper.getCurrentSsid(context)
            if (ssid != null) viewModel.addHomeSsid(ssid)
        }
    }

    Scaffold(
        topBar = {
            PrimaryTopBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(foyerName, fontWeight = FontWeight.Bold)
                    }
                },
                onBack = onBack,
                actions = { HelpButton(HelpScreen.FOYER_SETTINGS, tint = MaterialTheme.colorScheme.onPrimary) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ================= GROUPE "Ce foyer" =================
            GroupHeader(title = stringResource(R.string.foyer_group_this_foyer))

            // (1) Nom du foyer (edition inline)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.foyer_name_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = foyerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // Renaming is a server write: disabled offline, with the reason on screen.
                        NetworkNeededSlot(visible = !isOnline)
                    }
                    IconButton(
                        onClick = { showRenameDialog = true },
                        enabled = isOnline,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.foyer_name_rename_action),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // (2) Inviter quelqu'un (QR)
            ActionCard(
                icon = Icons.Default.PersonAdd,
                title = stringResource(R.string.foyer_invite_title),
                subtitle = stringResource(R.string.foyer_invite_subtitle),
                onClick = onInviteSomeone,
            )

            // (3) Changer de foyer — uniquement si >= 2 foyers
            if (allFoyers.size >= 2) {
                ActionCard(
                    icon = Icons.Default.SwapHoriz,
                    title = stringResource(R.string.foyer_switch_title),
                    subtitle = stringResource(R.string.foyer_switch_subtitle),
                    onClick = { showSwitchSheet = true },
                )
            }

            // (4) Contextes — une seule entree la ou il y en avait deux.
            // Pieces et Categories demandaient de tenir deux listes presque
            // identiques et de choisir UN foyer par tache ; un contexte est la
            // reponse unique, et une tache peut en porter plusieurs.
            ActionCard(
                icon = Icons.Default.Category,
                title = stringResource(R.string.contexts_title),
                subtitle = stringResource(R.string.foyer_contexts_subtitle),
                onClick = onManageContexts,
            )

            // (5) Household features: the collective modules, one switch for everyone.
            ModulesSection(viewModel = viewModel, collective = true)
            ModuleToggleError(viewModel = viewModel)

            // (6) Presence & absences (bloc : home freeze + WiFi maison)
            // P3 — tout le bloc appartient au module `presence`. Le gel pendant les
            // absences, le calendrier et le WiFi maison sont ses sous-options : les
            // laisser visibles alors que le module est coupe promettrait un
            // comportement qui n'a plus lieu.
            if (moduleEnabled(Modules.PRESENCE)) {
                FoyerPresenceCard(
                    viewModel = viewModel,
                    homeFreezeEnabled = homeFreezeEnabled,
                    homeSsids = homeSsids,
                    settingsLoaded = s.isLoaded,
                    onRequestWifiExplanation = { showWifiExplanation = true },
                    onOpenPresenceModal = { showPresenceModal = true },
                )
            }

            // (8) Simuler le rattrapage du foyer (-> ProjectionSimulationDialog)
            // Sub-option `simulation` of the `objectives` module: hidden with it.
            if (moduleEnabled(Modules.OBJECTIVES)) {
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Timeline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.foyer_simulation_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.foyer_simulation_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.loadSimulationData()
                        showSimulationDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.foyer_simulation_button))
                }
            }
            }

            // P3 — les 13 seuils appartiennent au module `bonus_malus`. Il exige
            // `motivation`, mais l'inverse n'est pas vrai : un foyer peut vouloir des
            // points sans moduler leur valeur. Se reposer sur la garde gamif
            // laisserait donc les seuils visibles dans ce cas.
            if (moduleEnabled(Modules.BONUS_MALUS)) {
                FoyerBonusMalusCard(viewModel)
            }

            // (10) Quitter le foyer — destructif, rouge, en bas du groupe
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.foyer_leave_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.foyer_leave_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { showLeaveConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.foyer_leave_button), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ====== GROUPE "Mon compte · vaut pour tous mes foyers" ======
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupHeader(
                    title = stringResource(R.string.foyer_group_account_scope),
                    modifier = Modifier.weight(1f),
                )
                InfoTooltip(
                    title = stringResource(R.string.foyer_group_account_scope_tooltip_title),
                    description = stringResource(R.string.foyer_group_account_scope_tooltip_description),
                )
            }

            // (11) Mes appareils (ajouter)
            ActionCard(
                icon = Icons.Default.Devices,
                title = stringResource(R.string.foyer_devices_title),
                subtitle = stringResource(R.string.foyer_devices_subtitle),
                onClick = onAddDevice,
            )

            // (12) Regenerer mon code de recuperation (+ (i) irreversible)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.foyer_recovery_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(4.dp))
                            InfoTooltip(
                                title = stringResource(R.string.foyer_recovery_tooltip_title),
                                description = stringResource(R.string.foyer_recovery_tooltip_description),
                            )
                        }
                        Text(
                            text = stringResource(R.string.foyer_recovery_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { showRegenerateConfirm = true }) {
                        Text(
                            stringResource(R.string.foyer_recovery_button),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // (13) Renouvellement affinite/effort (slider + (i))
            // Affinity and effort ratings are core: no module condition.
            AffinityRenewalCard(affinityExpiry, viewModel::setAffinityExpiry)

            // (14) Tolerance fair-share (slider + (i))
            // Sub-option `fair_share` of `repartition`: hidden with it.
            if (moduleEnabled(Modules.REPARTITION)) {
                FairShareToleranceCard(fairShareTolerance, viewModel::setFairShareTolerance)
            }

            if (moduleEnabled(Modules.REPARTITION)) FairShareTargetSection() // (15) part visee

            Spacer(Modifier.height(32.dp))
        }
    }

    // --- Dialog explication WiFi avant permission (deplace de SettingsScreen) ---
    if (showWifiExplanation) {
        AlertDialog(
            onDismissRequest = { showWifiExplanation = false },
            title = { Text(stringResource(R.string.foyer_wifi_explanation_title)) },
            text = {
                Text(stringResource(R.string.foyer_wifi_explanation_message))
            },
            confirmButton = {
                Button(onClick = {
                    showWifiExplanation = false
                    locationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                }) { Text(stringResource(R.string.foyer_wifi_explanation_continue)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWifiExplanation = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // --- Last refused online-only write (rename, freeze, Wi-Fi, bonus, objective): rolled back, told ---
    val actionError by viewModel.actionError.collectAsState()
    ActionErrorDialog(actionError, "SettingsFoyer", viewModel::dismissActionError)

    // --- Dialog renommer le foyer ---
    if (showRenameDialog) {
        FoyerRenameDialog(
            foyerName = foyerName,
            onDismiss = { showRenameDialog = false },
            onSave = {
                viewModel.renameCurrentFoyer(it)
                showRenameDialog = false
            },
        )
    }

    // --- Feuille de selection "Changer de foyer" (>= 2 foyers) ---
    if (showSwitchSheet) {
        // The sheet stays open while the switch runs and shows its failure; it closes on success.
        val switchState by viewModel.switchState.collectAsState()
        LaunchedEffect(switchState) {
            if (switchState is FoyerSwitchState.Done) {
                showSwitchSheet = false
                viewModel.dismissSwitch()
            }
        }
        FoyerSwitchDialog(
            allFoyers = allFoyers,
            state = switchState,
            onDismiss = {
                showSwitchSheet = false
                viewModel.dismissSwitch()
            },
            onSwitchTo = { viewModel.switchToFoyer(it) },
        )
    }

    // --- Dialog de simulation (reutilise ProjectionSimulationDialog) ---
    if (showSimulationDialog) {
        val simPace by viewModel.simPace.collectAsState()
        val simRetard by viewModel.simRetard.collectAsState()
        val simMaintenance by viewModel.simMaintenance.collectAsState()
        val simUserId by viewModel.currentUserId.collectAsState()
        ProjectionSimulationDialog(
            pace = simPace,
            retard = simRetard,
            maintenance = simMaintenance,
            currentUserId = simUserId,
            onDismiss = { showSimulationDialog = false },
            onSaveSelf = { minutes, unit ->
                viewModel.saveObjective(minutes, unit)
                showSimulationDialog = false
            },
            // Same live flow as the home screen; back to the shell, which follows
            // the proposition to the home tab (waiting and answer dialogs live there).
            onProposeToFoyer = { values, unit ->
                viewModel.proposeToFoyer(values, unit)
                showSimulationDialog = false
                onBack()
            },
        )
    }

    // --- Flows SecuritySection (regenerer code / quitter foyer) ---
    // On reutilise les memes dialogs que SecuritySection sans dupliquer la
    // card : ces handlers consomment regenerateState / leaveState du VM.
    // P4b — modal UNIQUE "Présence & séries" (éditable), ouvert depuis le
    // bouton du bloc Présence & absences (design decision 5 v3).
    if (showPresenceModal) {
        val presenceMembers = remember(users) {
            users.map { StreakMember(it.id, it.displayName) }
        }
        StreakCalendarModal(
            members = presenceMembers,
            initialUserId = userId ?: presenceMembers.firstOrNull()?.userId.orEmpty(),
            onDismiss = { showPresenceModal = false },
        )
    }
    RecoveryAndLeaveDialogs(
        viewModel = viewModel,
        onLeftFoyer = onLeftFoyer,
        showLeaveConfirm = showLeaveConfirm,
        onDismissLeaveConfirm = { showLeaveConfirm = false },
        showRegenerateConfirm = showRegenerateConfirm,
        onDismissRegenerateConfirm = { showRegenerateConfirm = false },
    )
}

@Composable
private fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** Card conteneur standard (meme pattern visuel que SettingsScreen). */
@Composable
internal fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Card cliquable simple icone + titre + sous-titre (pieces, inviter...). */
@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
