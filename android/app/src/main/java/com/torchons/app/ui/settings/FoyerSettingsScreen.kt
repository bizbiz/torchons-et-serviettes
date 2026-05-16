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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.home.ProjectionSimulationDialog
import com.torchons.app.util.WifiHelper

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

    val foyerName = currentFoyer?.name ?: "Mon foyer"
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
                            "Retour",
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
            GroupHeader(title = "Ce foyer")

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
                            text = "Nom du foyer",
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
                            contentDescription = "Renommer",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // (2) Inviter quelqu'un (QR)
            ActionCard(
                icon = Icons.Default.PersonAdd,
                title = "Inviter quelqu'un",
                subtitle = "Generer un QR code pour ajouter un membre au foyer",
                onClick = onInviteSomeone,
            )

            // (3) Changer de foyer — uniquement si >= 2 foyers
            if (allFoyers.size >= 2) {
                ActionCard(
                    icon = Icons.Default.SwapHoriz,
                    title = "Changer de foyer",
                    subtitle = "Basculer vers un autre de tes foyers",
                    onClick = { showSwitchSheet = true },
                )
            }

            // (4) Pieces
            ActionCard(
                icon = Icons.Default.MeetingRoom,
                title = "Pieces",
                subtitle = "Cuisine, SDB, Chambre... + emoji et couleur",
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
                            text = "Categories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Ajouter, modifier, supprimer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoTooltip(
                        title = "Categories et gel pendant l'absence",
                        description = "Une categorie marquee 'a la maison' contient " +
                            "des taches qui ne peuvent etre faites que sur place. " +
                            "Quand personne n'est detecte a la maison (cf. bloc " +
                            "Presence & absences), ces taches sont gelees : elles " +
                            "arretent de vieillir et reprennent au retour.",
                    )
                }
            }

            // (6) Presence & absences (bloc : home freeze + WiFi maison)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Presence & absences",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Presence & absences",
                        description = "Quand aucun membre du foyer n'est detecte au " +
                            "WiFi maison depuis plus de 24h, les taches des " +
                            "categories \"a la maison\" arretent de vieillir " +
                            "(mise en pause). Elles reprennent normalement au " +
                            "retour.\n\nLa detection automatique repose sur le(s) " +
                            "reseau(x) WiFi declares ci-dessous. Elle est " +
                            "facultative : sans WiFi configure, toutes les taches " +
                            "restent visibles en permanence.",
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Toggle mise en pause si absent (home freeze)
                SettingSwitch(
                    title = "Mettre en pause si tout le monde est absent",
                    subtitle = "Les taches \"a la maison\" arretent de vieillir " +
                        "quand personne n'est detecte depuis 24h.",
                    checked = homeFreezeEnabled,
                    onCheckedChange = { viewModel.setCurrentFoyerHomeFreezeEnabled(it) },
                )

                // Toggle perso : masquer les taches maison de SA liste hors
                // WiFi maison. Per-user (l'autre membre a son propre reglage).
                // (i) precise la portee + le no-op si pas de WiFi configure.
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Masquer hors WiFi",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Masquer hors WiFi",
                        description = "S'applique a TOI sur ce foyer (l'autre " +
                            "membre a son propre reglage). Sans WiFi maison " +
                            "configure ci-dessous, ce reglage n'a aucun effet — " +
                            "tout reste visible.",
                    )
                }
                SettingSwitch(
                    title = "Masquer les taches maison hors WiFi",
                    subtitle = "Les taches \"a la maison\" sont cachees de la " +
                        "liste quand tu n'es pas connecte a ton WiFi maison.",
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
                        text = "WiFi maison",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = "WiFi maison",
                        description = "Les taches des categories marquees 'a la " +
                            "maison' ne seront suggerees que lorsque tu es " +
                            "connecte a un de ces reseaux WiFi.",
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (homeSsids.isEmpty()) {
                    Text(
                        text = "Aucun WiFi configure — toutes les taches sont visibles",
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
                                    "Supprimer",
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
                    Text("Ajouter mon WiFi actuel")
                }
            }

            // (7) Gamification de ce foyer (+ (i))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Gamification de ce foyer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Gamification de ce foyer",
                        description = "Active ou desactive les points, stats et " +
                            "bonus pour TOUS les membres de ce foyer. C'est un " +
                            "reglage partage : le desactiver le retire aussi a " +
                            "l'autre membre. Distinct de la gamification de ton " +
                            "compte (qui, elle, s'applique a tous tes foyers).",
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activer la gamification du foyer",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (gamifFoyer)
                                "Activee — tous les membres voient les points dans ce foyer."
                            else
                                "Desactivee — personne ne voit les points dans ce foyer.",
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
                            text = "Simuler le rattrapage du foyer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Tester un rythme et voir en combien de temps " +
                                "le retard est rattrape",
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
                    Text("Ouvrir la simulation")
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
                        text = "Bonus / Malus de points",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = "Bonus / Malus de points",
                        description = "Reglages avances de la mecanique de points : " +
                            "bonus quand une tache est faite en retard, malus " +
                            "quand elle est faite trop en avance, et detection " +
                            "des retards systematiques. Valeurs par defaut " +
                            "raisonnables — a ajuster seulement si tu sais ce " +
                            "que tu fais. Partage entre tous les membres du foyer.",
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
                        "Bonus retard",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        "Bonus %", "+${(cfg.bonusPct * 100).toInt()}%",
                        cfg.bonusPct, 0f..2f, 19,
                        "Multiplicateur applique quand une tache est en retard " +
                            "au-dela du seuil. +50% = touche 1.5x les points.",
                    ) { viewModel.updateBonusConfig("bonus_pct", it) }
                    BonusSlider(
                        "Seuil bonus (Strict)",
                        "${(cfg.seuilBonusStrict * 100).toInt()}%",
                        cfg.seuilBonusStrict, 0f..1f, 19,
                        "% du cycle de retard pour declencher le bonus sur les " +
                            "taches Strict.",
                    ) { viewModel.updateBonusConfig("seuil_bonus_strict", it) }
                    BonusSlider(
                        "Seuil bonus (Normal)",
                        "${(cfg.seuilBonusNormal * 100).toInt()}%",
                        cfg.seuilBonusNormal, 0f..1f, 19,
                        "% du cycle de retard pour declencher le bonus sur les " +
                            "taches Normal.",
                    ) { viewModel.updateBonusConfig("seuil_bonus_normal", it) }
                    BonusSlider(
                        "Seuil bonus (Souple)",
                        "${(cfg.seuilBonusSouple * 100).toInt()}%",
                        cfg.seuilBonusSouple, 0f..1f, 19,
                        "% du cycle de retard pour declencher le bonus sur les " +
                            "taches Souple.",
                    ) { viewModel.updateBonusConfig("seuil_bonus_souple", it) }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        "Malus avance",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        "Plancher malus", "${(cfg.plancherMalus * 100).toInt()}%",
                        cfg.plancherMalus, 0.1f..1f, 17,
                        "Multiplicateur minimum a 100% d'avance (debut du cycle). " +
                            "50% = au pire la moitie des points.",
                    ) { viewModel.updateBonusConfig("plancher_malus", it) }
                    BonusSlider(
                        "Seuil malus (Strict)",
                        "${(cfg.seuilMalusStrict * 100).toInt()}%",
                        cfg.seuilMalusStrict, 0f..1f, 19,
                        "% du cycle en avance pour entrer en zone malus sur les " +
                            "taches Strict.",
                    ) { viewModel.updateBonusConfig("seuil_malus_strict", it) }
                    BonusSlider(
                        "Seuil malus (Normal)",
                        "${(cfg.seuilMalusNormal * 100).toInt()}%",
                        cfg.seuilMalusNormal, 0f..1f, 19,
                        "% du cycle en avance pour entrer en zone malus sur les " +
                            "taches Normal.",
                    ) { viewModel.updateBonusConfig("seuil_malus_normal", it) }
                    BonusSlider(
                        "Seuil malus (Souple)",
                        "${(cfg.seuilMalusSouple * 100).toInt()}%",
                        cfg.seuilMalusSouple, 0f..1f, 19,
                        "% du cycle en avance pour entrer en zone malus sur les " +
                            "taches Souple.",
                    ) { viewModel.updateBonusConfig("seuil_malus_souple", it) }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        "Detection retards repetitifs",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    BonusSlider(
                        "Fenetre min", "${cfg.bumpYMinDays} j",
                        cfg.bumpYMinDays.toFloat(), 30f..365f, 10,
                        "Duree minimale de la fenetre d'analyse pour detecter un " +
                            "retard systematique.",
                    ) { viewModel.updateBonusConfig("bump_y_min_days", it.toInt()) }
                    BonusSlider(
                        "Fenetre max", "${cfg.bumpYMaxDays} j",
                        cfg.bumpYMaxDays.toFloat(), 180f..3650f, 10,
                        "Duree maximale de la fenetre d'analyse (les vieilles " +
                            "donnees pourrissent).",
                    ) { viewModel.updateBonusConfig("bump_y_max_days", it.toInt()) }
                    BonusSlider(
                        "Completions min", "${cfg.bumpZMin}",
                        cfg.bumpZMin.toFloat(), 1f..10f, 8,
                        "Nombre minimum de completions dans la fenetre pour tirer " +
                            "une conclusion.",
                    ) { viewModel.updateBonusConfig("bump_z_min", it.toInt()) }
                    BonusSlider(
                        "Ratio retard", "${(cfg.bumpXRatio * 100).toInt()}%",
                        cfg.bumpXRatio, 0.3f..1f, 13,
                        "Pourcentage de completions qui doivent etre en zone " +
                            "bonus pour qualifier 'systematiquement en retard'.",
                    ) { viewModel.updateBonusConfig("bump_x_ratio", it) }
                    BonusSlider(
                        "Grace period", "${(cfg.bumpGraceRatio * 100).toInt()}%",
                        cfg.bumpGraceRatio, 0.1f..2f, 18,
                        "Apres proposition de bump, on attend ce % de la fenetre " +
                            "avant de re-analyser.",
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
                            text = "Quitter le foyer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "Tu ne verras plus les taches de ce foyer.",
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
                        Text("Quitter", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ====== GROUPE "Mon compte · vaut pour tous mes foyers" ======
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupHeader(
                    title = "Mon compte · vaut pour tous mes foyers",
                    modifier = Modifier.weight(1f),
                )
                InfoTooltip(
                    title = "Perimetre : ton compte",
                    description = "Les reglages de ce groupe sont attaches a TON " +
                        "compte, pas a ce foyer en particulier. Ils s'appliquent " +
                        "donc de la meme maniere dans TOUS les foyers dont tu es " +
                        "membre.",
                )
            }

            // (11) Mes appareils (ajouter)
            ActionCard(
                icon = Icons.Default.Devices,
                title = "Mes appareils",
                subtitle = "Connecter une tablette ou un 2eme telephone a ton compte",
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
                                text = "Regenerer mon code de recuperation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(4.dp))
                            InfoTooltip(
                                title = "Code de recuperation — irreversible",
                                description = "Ce code est le SEUL moyen de " +
                                    "retrouver ton foyer si tu perds ton " +
                                    "telephone. En generer un nouveau invalide " +
                                    "definitivement l'ancien.\n\nIl n'est affiche " +
                                    "qu'une seule fois, a la generation. Personne " +
                                    "ne peut le recuperer ni le reinitialiser a ta " +
                                    "place — pas meme le developpeur. Note-le " +
                                    "immediatement et garde-le en lieu sur.",
                            )
                        }
                        Text(
                            text = "Te permet de retrouver ton foyer si tu perds ton tel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { showRegenerateConfirm = true }) {
                        Text("Generer", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // (13) Renouvellement affinite/effort (slider + (i))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Renouvellement : $affinityExpiry mois",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = "Renouvellement affinites/efforts",
                        description = "Apres cette duree, l'app te redemandera ton " +
                            "affinite et estimation d'effort pour chaque tache. " +
                            "Les anciennes valeurs restent valables tant qu'elles " +
                            "ne sont pas remplacees.",
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
                        text = "Tolerance fair-share : ${fairShareTolerance}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    InfoTooltip(
                        title = "Tolerance fair-share",
                        description = "L'app te dit 'tu as fait plus que ta part' " +
                            "quand tu as fait au moins X% de plus que ton ratio " +
                            "cible. A 30% (defaut) avec un ratio 50/50, l'app te " +
                            "felicite quand tu as fait 65% du boulot ou plus. A " +
                            "0%, l'app te felicite des que tu depasses ton ratio. " +
                            "A 100%, jamais (sauf si tu fais tout).",
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
            title = { Text("Detection WiFi maison") },
            text = {
                Text(
                    "L'appli Torchons & Serviettes peut afficher les taches a " +
                        "faire a la maison uniquement lorsque vous y etes.\n\n" +
                        "Pour ce faire, l'app a besoin d'acceder a votre " +
                        "localisation lorsqu'elle est ouverte.\n\nSi vous etes " +
                        "interesse par cette fonctionnalite, cliquez sur " +
                        "Continuer. Sinon, cliquez sur Annuler — vous pourrez " +
                        "reactiver cette fonctionnalite dans vos reglages plus tard.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showWifiExplanation = false
                    locationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                }) { Text("Continuer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWifiExplanation = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    // --- Dialog confirmation desactivation gamif foyer ---
    if (showFoyerGamifConfirmDialog) {
        val otherMemberName = users
            .firstOrNull { it.displayName != displayName }
            ?.displayName
            ?: "l'autre membre du foyer"
        AlertDialog(
            onDismissRequest = { showFoyerGamifConfirmDialog = false },
            title = { Text("Desactiver la gamification du foyer ?") },
            text = {
                Column {
                    Text(
                        text = "Tu vas desactiver la gamification du foyer " +
                            "\"$foyerName\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "$otherMemberName et toi ne verrez plus les " +
                            "points, les stats ni les bonus dans ce foyer. Vous " +
                            "pourrez le reactiver a tout moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setCurrentFoyerGamificationEnabled(false)
                    showFoyerGamifConfirmDialog = false
                }) { Text("Desactiver") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFoyerGamifConfirmDialog = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    // --- Dialog renommer le foyer ---
    if (showRenameDialog) {
        var name by remember { mutableStateOf(foyerName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renommer le foyer", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
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
                ) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Annuler") }
            },
        )
    }

    // --- Feuille de selection "Changer de foyer" (>= 2 foyers) ---
    if (showSwitchSheet) {
        AlertDialog(
            onDismissRequest = { showSwitchSheet = false },
            title = { Text("Changer de foyer", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Basculer videra le cache local et rechargera les " +
                            "donnees du foyer choisi. Ca prend quelques secondes.",
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
                                        contentDescription = "Foyer actif",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwitchSheet = false }) { Text("Fermer") }
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
            title = { Text("Regenerer ton code ?") },
            text = {
                Text(
                    "Cela invalide ton ancien code de recuperation s'il existait. " +
                        "Tu recevras un nouveau code, a noter immediatement — il " +
                        "ne sera affiche qu'une seule fois.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    onDismissRegenerateConfirm()
                    viewModel.regenerateRecoveryCode()
                }) { Text("Generer") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRegenerateConfirm) { Text("Annuler") }
            },
        )
    }

    if (regenerateState is SettingsViewModel.RegenerateState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Generation...") },
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
            title = { Text("Voici ton nouveau code") },
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
                        text = "⚠ Note-le maintenant. Il ne sera plus jamais affiche.",
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
                            Text("Copier")
                        }
                        OutlinedButton(
                            onClick = { shareCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Partager")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text("J'ai note") }
            },
        )
    }

    val regenError = regenerateState as? SettingsViewModel.RegenerateState.Error
    if (regenError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text("Erreur") },
            text = { Text(regenError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text("OK") }
            },
        )
    }

    // --- Quitter le foyer ---
    if (showLeaveConfirm && leaveState is SettingsViewModel.LeaveState.Idle) {
        AlertDialog(
            onDismissRequest = onDismissLeaveConfirm,
            title = { Text("Quitter ce foyer ?") },
            text = {
                Text(
                    "Tu ne verras plus les taches, votes, completions de ce " +
                        "foyer. Tu peux le rejoindre a nouveau plus tard via un " +
                        "QR ou ton code de recuperation.",
                )
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
                ) { Text("Quitter") }
            },
            dismissButton = {
                TextButton(onClick = onDismissLeaveConfirm) { Text("Annuler") }
            },
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Sortie en cours...") },
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
            title = { Text("C'est fait") },
            text = { Text("Tu as bascule sur un autre foyer ou tu es membre.") },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text("OK") }
            },
        )
    }

    val leaveError = leaveState as? SettingsViewModel.LeaveState.Error
    if (leaveError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text("Erreur") },
            text = { Text(leaveError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text("OK") }
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
