package com.torchons.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.torchons.app.ui.common.PresenceCalendarGrid
import com.torchons.app.ui.home.ProjectionSimulationDialog
import com.torchons.app.ui.stats.StreakCalendarModal
import com.torchons.app.ui.stats.StreakMember
import com.torchons.app.util.WifiHelper
import java.time.YearMonth

/**
 * Ecran "Foyer" (P1 refonte — design-foyer-settings-presence.md decision 9).
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
    onManageRooms: () -> Unit,
    onManageCategories: () -> Unit,
    onInviteSomeone: () -> Unit,
    onAddDevice: () -> Unit,
    onLeftFoyer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by viewModel.settings.collectAsState()
    val currentFoyer by viewModel.currentFoyer.collectAsState()
    val allFoyers by viewModel.allFoyers.collectAsState()
    val users by viewModel.users.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
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
    val gamifFoyer = currentFoyer?.gamificationEnabled ?: true
    val homeFreezeEnabled = currentFoyer?.homeFreezeEnabled ?: false

    var showWifiExplanation by remember { mutableStateOf(false) }
    var showFoyerGamifConfirmDialog by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSimulationDialog by remember { mutableStateOf(false) }
    var showBonusMalus by remember { mutableStateOf(false) }
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
            TopAppBar(
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
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
                    }
                    IconButton(onClick = { showRenameDialog = true }) {
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

            // (4) Pieces
            ActionCard(
                icon = Icons.Default.MeetingRoom,
                title = stringResource(R.string.foyer_rooms_title),
                subtitle = stringResource(R.string.foyer_rooms_subtitle),
                onClick = onManageRooms,
            )

            // (5) Categories (+ (i) gel "a la maison")
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onManageCategories() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Category,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.foyer_categories_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.foyer_categories_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoTooltip(
                        title = stringResource(R.string.foyer_categories_tooltip_title),
                        description = stringResource(R.string.foyer_categories_tooltip_description),
                    )
                }
            }

            // (6) Presence & absences (bloc : home freeze + WiFi maison)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.foyer_presence_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = stringResource(R.string.foyer_presence_section_tooltip_title),
                        description = stringResource(R.string.foyer_presence_section_tooltip_description),
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Toggle mise en pause si absent (home freeze)
                SettingSwitch(
                    title = stringResource(R.string.foyer_presence_freeze_title),
                    subtitle = stringResource(R.string.foyer_presence_freeze_subtitle),
                    checked = homeFreezeEnabled,
                    onCheckedChange = { viewModel.setCurrentFoyerHomeFreezeEnabled(it) },
                )

                // Toggle perso : masquer les taches maison de SA liste hors
                // WiFi maison. Per-user (l'autre membre a son propre reglage).
                // (i) precise la portee + le no-op si pas de WiFi configure.
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.foyer_presence_hide_off_wifi_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = stringResource(R.string.foyer_presence_hide_off_wifi_tooltip_title),
                        description = stringResource(R.string.foyer_presence_hide_off_wifi_tooltip_description),
                    )
                }
                SettingSwitch(
                    title = stringResource(R.string.foyer_presence_hide_off_wifi_switch_title),
                    subtitle = stringResource(R.string.foyer_presence_hide_off_wifi_switch_subtitle),
                    checked = s.hideHomeTasksOffWifi,
                    onCheckedChange = { viewModel.setHideHomeTasksOffWifi(it) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // WiFi maison (deplace tel quel depuis SettingsScreen)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.foyer_presence_wifi_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = stringResource(R.string.foyer_presence_wifi_tooltip_title),
                        description = stringResource(R.string.foyer_presence_wifi_tooltip_description),
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (homeSsids.isEmpty()) {
                    Text(
                        text = stringResource(R.string.foyer_presence_wifi_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    homeSsids.forEach { ssid ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ssid,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.removeHomeSsid(ssid) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    stringResource(R.string.foyer_presence_wifi_remove_cd),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val hasPermission = androidx.core.content.ContextCompat
                            .checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            val ssid = WifiHelper.getCurrentSsid(context)
                            if (ssid != null) viewModel.addHomeSsid(ssid)
                        } else {
                            showWifiExplanation = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.foyer_presence_wifi_add_button))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // P4b — le calendrier de présence n'est PLUS inline ici :
                // un seul modal "Présence & séries" (éditable), ouvrable de
                // plusieurs endroits (design decision 5 v3 — "one modal to
                // rule them all"). Ce bloc ne garde que les réglages.
                OutlinedButton(
                    onClick = { showPresenceModal = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Timeline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.foyer_presence_open_calendar_button))
                }
            }

            // (7) Gamification de ce foyer (+ (i))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.foyer_gamif_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = stringResource(R.string.foyer_gamif_tooltip_title),
                        description = stringResource(R.string.foyer_gamif_tooltip_description),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.foyer_gamif_switch_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (gamifFoyer)
                                stringResource(R.string.foyer_gamif_switch_subtitle_on)
                            else
                                stringResource(R.string.foyer_gamif_switch_subtitle_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = gamifFoyer,
                        onCheckedChange = { newValue ->
                            if (!newValue) {
                                showFoyerGamifConfirmDialog = true
                            } else {
                                viewModel.setCurrentFoyerGamificationEnabled(true)
                            }
                        },
                    )
                }
            }

            // (8) Simuler le rattrapage du foyer (-> ProjectionSimulationDialog)
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

            // (9) Bonus / Malus de points (replie par defaut, complexe)
            val bonusConfig by viewModel.bonusConfig.collectAsState()
            SettingCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBonusMalus = !showBonusMalus },
                ) {
                    Text(
                        text = stringResource(R.string.foyer_bonus_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = stringResource(R.string.foyer_bonus_section_tooltip_title),
                        description = stringResource(R.string.foyer_bonus_section_tooltip_description),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showBonusMalus)
                            Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }

                if (showBonusMalus && bonusConfig != null) {
                    val cfg = bonusConfig!!
                    Spacer(Modifier.height(8.dp))

                    Text(
                        stringResource(R.string.foyer_bonus_group_late),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        stringResource(R.string.foyer_bonus_pct_label),
                        stringResource(R.string.foyer_bonus_pct_value_format, (cfg.bonusPct * 100).toInt()),
                        cfg.bonusPct, 0f..2f, 19,
                        stringResource(R.string.foyer_bonus_pct_desc),
                    ) { viewModel.updateBonusConfig("bonus_pct", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_bonus_threshold_strict),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusStrict * 100).toInt()),
                        cfg.seuilBonusStrict, 0f..1f, 19,
                        stringResource(R.string.foyer_bonus_threshold_strict_desc),
                    ) { viewModel.updateBonusConfig("seuil_bonus_strict", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_bonus_threshold_normal),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusNormal * 100).toInt()),
                        cfg.seuilBonusNormal, 0f..1f, 19,
                        stringResource(R.string.foyer_bonus_threshold_normal_desc),
                    ) { viewModel.updateBonusConfig("seuil_bonus_normal", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_bonus_threshold_souple),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusSouple * 100).toInt()),
                        cfg.seuilBonusSouple, 0f..1f, 19,
                        stringResource(R.string.foyer_bonus_threshold_souple_desc),
                    ) { viewModel.updateBonusConfig("seuil_bonus_souple", it) }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        stringResource(R.string.foyer_malus_group),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        stringResource(R.string.foyer_malus_floor_label),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.plancherMalus * 100).toInt()),
                        cfg.plancherMalus, 0.1f..1f, 17,
                        stringResource(R.string.foyer_malus_floor_desc),
                    ) { viewModel.updateBonusConfig("plancher_malus", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_malus_threshold_strict),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusStrict * 100).toInt()),
                        cfg.seuilMalusStrict, 0f..1f, 19,
                        stringResource(R.string.foyer_malus_threshold_strict_desc),
                    ) { viewModel.updateBonusConfig("seuil_malus_strict", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_malus_threshold_normal),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusNormal * 100).toInt()),
                        cfg.seuilMalusNormal, 0f..1f, 19,
                        stringResource(R.string.foyer_malus_threshold_normal_desc),
                    ) { viewModel.updateBonusConfig("seuil_malus_normal", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_malus_threshold_souple),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusSouple * 100).toInt()),
                        cfg.seuilMalusSouple, 0f..1f, 19,
                        stringResource(R.string.foyer_malus_threshold_souple_desc),
                    ) { viewModel.updateBonusConfig("seuil_malus_souple", it) }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        stringResource(R.string.foyer_bump_group),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        stringResource(R.string.foyer_bump_window_min),
                        stringResource(R.string.foyer_bump_window_value_days_format, cfg.bumpYMinDays),
                        cfg.bumpYMinDays.toFloat(), 30f..365f, 10,
                        stringResource(R.string.foyer_bump_window_min_desc),
                    ) { viewModel.updateBonusConfig("bump_y_min_days", it.toInt()) }
                    BonusSlider(
                        stringResource(R.string.foyer_bump_window_max),
                        stringResource(R.string.foyer_bump_window_value_days_format, cfg.bumpYMaxDays),
                        cfg.bumpYMaxDays.toFloat(), 180f..3650f, 10,
                        stringResource(R.string.foyer_bump_window_max_desc),
                    ) { viewModel.updateBonusConfig("bump_y_max_days", it.toInt()) }
                    BonusSlider(
                        stringResource(R.string.foyer_bump_min_completions),
                        stringResource(R.string.foyer_bump_value_int_format, cfg.bumpZMin),
                        cfg.bumpZMin.toFloat(), 1f..10f, 8,
                        stringResource(R.string.foyer_bump_min_completions_desc),
                    ) { viewModel.updateBonusConfig("bump_z_min", it.toInt()) }
                    BonusSlider(
                        stringResource(R.string.foyer_bump_ratio),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.bumpXRatio * 100).toInt()),
                        cfg.bumpXRatio, 0.3f..1f, 13,
                        stringResource(R.string.foyer_bump_ratio_desc),
                    ) { viewModel.updateBonusConfig("bump_x_ratio", it) }
                    BonusSlider(
                        stringResource(R.string.foyer_bump_grace),
                        stringResource(R.string.foyer_bump_value_pct_format, (cfg.bumpGraceRatio * 100).toInt()),
                        cfg.bumpGraceRatio, 0.1f..2f, 18,
                        stringResource(R.string.foyer_bump_grace_desc),
                    ) { viewModel.updateBonusConfig("bump_grace_ratio", it) }
                }
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
                        Text(stringResource(R.string.foyer_recovery_button), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // (13) Renouvellement affinite/effort (slider + (i))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.foyer_affinity_title_format, affinityExpiry),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = stringResource(R.string.foyer_affinity_tooltip_title),
                        description = stringResource(R.string.foyer_affinity_tooltip_description),
                    )
                }
                Slider(
                    value = affinityExpiry.toFloat(),
                    onValueChange = { viewModel.setAffinityExpiry(it.toInt()) },
                    valueRange = 1f..12f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // (14) Tolerance fair-share (slider + (i))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Balance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.foyer_fair_share_title_format, fairShareTolerance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = stringResource(R.string.foyer_fair_share_tooltip_title),
                        description = stringResource(R.string.foyer_fair_share_tooltip_description),
                    )
                }
                Slider(
                    value = fairShareTolerance.toFloat(),
                    onValueChange = { viewModel.setFairShareTolerance(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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

    // --- Dialog confirmation desactivation gamif foyer ---
    if (showFoyerGamifConfirmDialog) {
        val fallbackOther = stringResource(R.string.foyer_gamif_confirm_other_member_fallback)
        val otherMemberName = users
            .firstOrNull { it.displayName != displayName }
            ?.displayName
            ?: fallbackOther
        AlertDialog(
            onDismissRequest = { showFoyerGamifConfirmDialog = false },
            title = { Text(stringResource(R.string.foyer_gamif_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.foyer_gamif_confirm_message_intro_format, foyerName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.foyer_gamif_confirm_message_detail_format, otherMemberName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setCurrentFoyerGamificationEnabled(false)
                    showFoyerGamifConfirmDialog = false
                }) { Text(stringResource(R.string.foyer_gamif_confirm_action)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFoyerGamifConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // --- Dialog renommer le foyer ---
    if (showRenameDialog) {
        var name by remember { mutableStateOf(foyerName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.foyer_rename_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameCurrentFoyer(name)
                        showRenameDialog = false
                    },
                    enabled = name.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // --- Feuille de selection "Changer de foyer" (>= 2 foyers) ---
    if (showSwitchSheet) {
        AlertDialog(
            onDismissRequest = { showSwitchSheet = false },
            title = { Text(stringResource(R.string.foyer_switch_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.foyer_switch_dialog_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    allFoyers.forEach { f ->
                        Card(
                            onClick = {
                                if (!f.isCurrent) {
                                    viewModel.switchToFoyer(f.id)
                                    showSwitchSheet = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (f.isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Home,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = f.role.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (f.isCurrent) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.foyer_switch_dialog_current_cd),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwitchSheet = false }) { Text(stringResource(R.string.foyer_switch_dialog_close)) }
            },
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
            // TODO P1 : le flow live "Proposer au foyer" (FCM, ecran d'attente
            // bloquant + modal accept/decline cote receveur) est pilote par
            // HomeViewModel/PropositionStateHolder. Le rebrancher hors du
            // HomeScreen depasse le scope P1 (refonte IA pure, zero backend) ;
            // ici on supporte uniquement "Juste pour moi". Bouton inerte.
            onProposeToFoyer = { _, _ -> showSimulationDialog = false },
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

/**
 * Dialogs des flows "Regenerer code de recuperation" et "Quitter le foyer".
 * Reprend exactement la sequence d'AlertDialog de SecuritySection (confirm
 * -> loading -> success/switched -> error), pilotee par regenerateState /
 * leaveState du SettingsViewModel. La card declenchant ces flows vit
 * directement dans FoyerSettingsScreen (groupes 12 et 10).
 */
@Composable
private fun RecoveryAndLeaveDialogs(
    viewModel: SettingsViewModel,
    onLeftFoyer: () -> Unit,
    showLeaveConfirm: Boolean,
    onDismissLeaveConfirm: () -> Unit,
    showRegenerateConfirm: Boolean,
    onDismissRegenerateConfirm: () -> Unit,
) {
    val regenerateState by viewModel.regenerateState.collectAsState()
    val leaveState by viewModel.leaveState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(leaveState) {
        if (leaveState is SettingsViewModel.LeaveState.LoggedOut) {
            onLeftFoyer()
        }
    }

    // --- Regenerer : confirmation ---
    if (showRegenerateConfirm &&
        regenerateState is SettingsViewModel.RegenerateState.Idle
    ) {
        AlertDialog(
            onDismissRequest = onDismissRegenerateConfirm,
            title = { Text(stringResource(R.string.recovery_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.recovery_dialog_confirm_message))
            },
            confirmButton = {
                Button(onClick = {
                    onDismissRegenerateConfirm()
                    viewModel.regenerateRecoveryCode()
                }) { Text(stringResource(R.string.foyer_recovery_button)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRegenerateConfirm) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (regenerateState is SettingsViewModel.RegenerateState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recovery_dialog_loading_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    val regenSuccess = regenerateState as? SettingsViewModel.RegenerateState.Success
    if (regenSuccess != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text(stringResource(R.string.recovery_dialog_success_title)) },
            text = {
                Column {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = regenSuccess.recoveryCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.recovery_dialog_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.recovery_dialog_copy))
                        }
                        OutlinedButton(
                            onClick = { shareCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.recovery_dialog_share))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text(stringResource(R.string.recovery_dialog_noted)) }
            },
        )
    }

    val regenError = regenerateState as? SettingsViewModel.RegenerateState.Error
    if (regenError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text(stringResource(R.string.recovery_dialog_error_title)) },
            text = { Text(regenError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    // --- Quitter le foyer ---
    if (showLeaveConfirm && leaveState is SettingsViewModel.LeaveState.Idle) {
        AlertDialog(
            onDismissRequest = onDismissLeaveConfirm,
            title = { Text(stringResource(R.string.leave_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.leave_dialog_confirm_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissLeaveConfirm()
                        viewModel.leaveFoyer()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.leave_dialog_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissLeaveConfirm) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.leave_dialog_loading_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.SwitchedFoyer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text(stringResource(R.string.leave_dialog_switched_title)) },
            text = { Text(stringResource(R.string.leave_dialog_switched_message)) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    val leaveError = leaveState as? SettingsViewModel.LeaveState.Error
    if (leaveError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text(stringResource(R.string.recovery_dialog_error_title)) },
            text = { Text(leaveError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
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
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
