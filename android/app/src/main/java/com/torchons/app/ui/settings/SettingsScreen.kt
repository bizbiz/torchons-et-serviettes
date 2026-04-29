package com.torchons.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.automirrored.filled.Logout
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.util.WifiHelper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onManageCategories: () -> Unit = {},
    onManageRooms: () -> Unit = {},
    onManageFoyers: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onInviteSomeone: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val displayName by viewModel.displayName.collectAsState()
    val s by viewModel.settings.collectAsState()
    val notifDaily = s.notifDaily
    val notifUrgent = s.notifUrgent
    val notifSolo = s.notifSolo
    val notifCollectiveWithMe = s.notifCollectiveWithMe
    val notifCollectiveWithoutMe = s.notifCollectiveWithoutMe
    val foyerMemberCount = s.foyerMemberCount
    val notifHour = s.notifHour
    val affinityPopup = s.affinityPopup
    val affinityExpiry = s.affinityExpiry
    val fairShareTolerance = s.fairShareTolerance
    val homeSsids = s.homeSsids
    var showWifiExplanation by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val ssid = WifiHelper.getCurrentSsid(context)
            if (ssid != null) viewModel.addHomeSsid(ssid)
        }
    }
    val fairShareRatio by viewModel.fairShareRatio.collectAsState()
    val users by viewModel.users.collectAsState()
    val backendConnected by viewModel.backendConnected.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkBackend()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Reglages",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Profil
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = displayName ?: "...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Connecte via Tailscale",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Categories
        Card(
            onClick = onManageCategories,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gerer les categories",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ajouter, modifier, supprimer, WiFi maison",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Foyers (multi-tenant)
        Card(
            onClick = onManageFoyers,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mes foyers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Voir, creer, basculer entre les foyers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // v0.30 — Inviter quelqu'un dans le foyer actif (QR code).
        Card(
            onClick = onInviteSomeone,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inviter quelqu'un",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Generer un QR code pour ajouter un membre au foyer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Pieces (rooms)
        Card(
            onClick = onManageRooms,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gerer les pieces",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Cuisine, SDB, Chambre... + emoji et couleur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Nouveautes + Roadmap
        Card(
            onClick = onOpenChangelog,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Nouveautes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Notes de version et roadmap",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // WiFi maison
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WiFi maison",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = "WiFi maison",
                        description = "Les taches des categories marquees 'a la maison' ne seront suggerees que lorsque tu es connecte a un de ces reseaux WiFi."
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (homeSsids.isEmpty()) {
                    Text(
                        text = "Aucun WiFi configure — toutes les taches sont visibles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    homeSsids.forEach { ssid ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ssid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.removeHomeSsid(ssid) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Supprimer", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            val ssid = WifiHelper.getCurrentSsid(context)
                            if (ssid != null) viewModel.addHomeSsid(ssid)
                        } else {
                            showWifiExplanation = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ajouter mon WiFi actuel")
                }
            }
        }

        // Dialog explication WiFi avant permission
        if (showWifiExplanation) {
            AlertDialog(
                onDismissRequest = { showWifiExplanation = false },
                title = { Text("Detection WiFi maison") },
                text = {
                    Text("L'appli Torchons & Serviettes peut afficher les taches a faire a la maison uniquement lorsque vous y etes.\n\nPour ce faire, l'app a besoin d'acceder a votre localisation lorsqu'elle est ouverte.\n\nSi vous etes interesse par cette fonctionnalite, cliquez sur Continuer. Sinon, cliquez sur Annuler — vous pourrez reactiver cette fonctionnalite dans vos reglages plus tard.")
                },
                confirmButton = {
                    Button(onClick = {
                        showWifiExplanation = false
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }) { Text("Continuer") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showWifiExplanation = false }) { Text("Annuler") }
                }
            )
        }

        // Notifications
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resume quotidien
                SettingSwitch(
                    title = "Resume quotidien",
                    subtitle = "Recevoir un resume de tes taches chaque jour",
                    checked = notifDaily,
                    onCheckedChange = { viewModel.setNotifDaily(it) }
                )

                // Heure du resume
                if (notifDaily) {
                    // Etat local qui suit le pouce pendant le drag. On ne
                    // pousse la valeur au VM (backend + reschedule worker)
                    // qu'au release, sinon le drag spamme 15+ POST et
                    // cancel/reenqueue WorkManager. La cle `remember(notifHour)`
                    // re-seed quand la valeur backend arrive apres loadAll.
                    var pendingNotifHour by remember(notifHour) { mutableStateOf(notifHour) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Heure : ${pendingNotifHour}h00",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Slider(
                        value = pendingNotifHour.toFloat(),
                        onValueChange = { pendingNotifHour = it.toInt() },
                        onValueChangeFinished = {
                            if (pendingNotifHour != notifHour) {
                                viewModel.setNotifHour(pendingNotifHour)
                            }
                        },
                        valueRange = 6f..22f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Taches urgentes
                SettingSwitch(
                    title = "Taches urgentes",
                    subtitle = "Notification quand une tache passe en rouge",
                    checked = notifUrgent,
                    onCheckedChange = { viewModel.setNotifUrgent(it) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Trois flags orthogonaux pour les completions (v0.22) : on
                // ne gere plus un seul "notif_completed", l'utilisateur choisit
                // par categorie.
                SettingSwitch(
                    title = "Quand quelqu'un fait une tache seul",
                    subtitle = "Notification quand l'autre fait une tache solo",
                    checked = notifSolo,
                    onCheckedChange = { viewModel.setNotifSolo(it) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SettingSwitch(
                    title = "Quand on fait une tache ensemble (avec moi)",
                    subtitle = "Notification quand quelqu'un enregistre un collectif qui t'inclut",
                    checked = notifCollectiveWithMe,
                    onCheckedChange = { viewModel.setNotifCollectiveWithMe(it) }
                )

                if (foyerMemberCount > 2) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingSwitch(
                        title = "Quand une tache est faite a plusieurs sans moi",
                        subtitle = "Notification quand un collectif te laisse de cote",
                        checked = notifCollectiveWithoutMe,
                        onCheckedChange = { viewModel.setNotifCollectiveWithoutMe(it) }
                    )
                }
            }
        }

        // Affinite
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.QuestionMark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Popup affinite",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitch(
                    title = "Activer",
                    subtitle = "Demander ton affinite pour chaque tache a l'ouverture de l'app",
                    checked = affinityPopup,
                    onCheckedChange = { viewModel.setAffinityPopup(it) }
                )

            }
        }

        // Fair-share
        if (users.size >= 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Balance,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Equilibre fair-share",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Repartition souhaitee des taches du foyer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${users[0].displayName} : $fairShareRatio% — ${users[1].displayName} : ${100 - fairShareRatio}%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = fairShareRatio.toFloat(),
                        onValueChange = { viewModel.updateFairShareRatio(it.toInt()) },
                        onValueChangeFinished = { viewModel.saveFairShare() },
                        valueRange = 10f..90f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Options avancees (pliable)
        Card(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Options avancees",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                if (showAdvanced) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Duree de persistance affinites/efforts
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Renouvellement : $affinityExpiry mois",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        InfoTooltip(
                            title = "Renouvellement affinites/efforts",
                            description = "Apres cette duree, l'app te redemandera ton affinite et estimation d'effort pour chaque tache. Les anciennes valeurs restent valables tant qu'elles ne sont pas remplacees."
                        )
                    }
                    Slider(
                        value = affinityExpiry.toFloat(),
                        onValueChange = { viewModel.setAffinityExpiry(it.toInt()) },
                        valueRange = 1f..12f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Tolerance fair-share
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tolerance fair-share : ${fairShareTolerance}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        InfoTooltip(
                            title = "Tolerance fair-share",
                            description = "L'app te dit 'tu as fait plus que ta part' quand tu as fait au moins X% de plus que ton ratio cible. A 30% (defaut) avec un ratio 50/50, l'app te felicite quand tu as fait 65% du boulot ou plus. A 0%, l'app te felicite des que tu depasses ton ratio. A 100%, jamais (sauf si tu fais tout)."
                        )
                    }
                    Slider(
                        value = fairShareTolerance.toFloat(),
                        onValueChange = { viewModel.setFairShareTolerance(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // --- Bonus / Malus de points ---
                    val bonusConfig by viewModel.bonusConfig.collectAsState()
                    var showBonusMalus by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBonusMalus = !showBonusMalus }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "Bonus / Malus de points",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showBonusMalus) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    if (showBonusMalus && bonusConfig != null) {
                        val cfg = bonusConfig!!
                        Spacer(modifier = Modifier.height(8.dp))

                        // Bonus retard
                        Text("Bonus retard", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        BonusSlider("Bonus %", "+${(cfg.bonusPct * 100).toInt()}%", cfg.bonusPct, 0f..2f, 19,
                            "Multiplicateur applique quand une tache est en retard au-dela du seuil. +50% = touche 1.5x les points.",
                        ) { viewModel.updateBonusConfig("bonus_pct", it) }
                        BonusSlider("Seuil bonus (Strict)", "${(cfg.seuilBonusStrict * 100).toInt()}%", cfg.seuilBonusStrict, 0f..1f, 19,
                            "% du cycle de retard pour declencher le bonus sur les taches Strict.",
                        ) { viewModel.updateBonusConfig("seuil_bonus_strict", it) }
                        BonusSlider("Seuil bonus (Normal)", "${(cfg.seuilBonusNormal * 100).toInt()}%", cfg.seuilBonusNormal, 0f..1f, 19,
                            "% du cycle de retard pour declencher le bonus sur les taches Normal.",
                        ) { viewModel.updateBonusConfig("seuil_bonus_normal", it) }
                        BonusSlider("Seuil bonus (Souple)", "${(cfg.seuilBonusSouple * 100).toInt()}%", cfg.seuilBonusSouple, 0f..1f, 19,
                            "% du cycle de retard pour declencher le bonus sur les taches Souple.",
                        ) { viewModel.updateBonusConfig("seuil_bonus_souple", it) }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Malus avance
                        Text("Malus avance", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        BonusSlider("Plancher malus", "${(cfg.plancherMalus * 100).toInt()}%", cfg.plancherMalus, 0.1f..1f, 17,
                            "Multiplicateur minimum a 100% d'avance (debut du cycle). 50% = au pire la moitie des points.",
                        ) { viewModel.updateBonusConfig("plancher_malus", it) }
                        BonusSlider("Seuil malus (Strict)", "${(cfg.seuilMalusStrict * 100).toInt()}%", cfg.seuilMalusStrict, 0f..1f, 19,
                            "% du cycle en avance pour entrer en zone malus sur les taches Strict.",
                        ) { viewModel.updateBonusConfig("seuil_malus_strict", it) }
                        BonusSlider("Seuil malus (Normal)", "${(cfg.seuilMalusNormal * 100).toInt()}%", cfg.seuilMalusNormal, 0f..1f, 19,
                            "% du cycle en avance pour entrer en zone malus sur les taches Normal.",
                        ) { viewModel.updateBonusConfig("seuil_malus_normal", it) }
                        BonusSlider("Seuil malus (Souple)", "${(cfg.seuilMalusSouple * 100).toInt()}%", cfg.seuilMalusSouple, 0f..1f, 19,
                            "% du cycle en avance pour entrer en zone malus sur les taches Souple.",
                        ) { viewModel.updateBonusConfig("seuil_malus_souple", it) }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Analyse retard (bump)
                        Text("Detection retards repetitifs", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        BonusSlider("Fenetre min", "${cfg.bumpYMinDays} j", cfg.bumpYMinDays.toFloat(), 30f..365f, 10,
                            "Duree minimale de la fenetre d'analyse pour detecter un retard systematique.",
                        ) { viewModel.updateBonusConfig("bump_y_min_days", it.toInt()) }
                        BonusSlider("Fenetre max", "${cfg.bumpYMaxDays} j", cfg.bumpYMaxDays.toFloat(), 180f..3650f, 10,
                            "Duree maximale de la fenetre d'analyse (les vieilles donnees pourrissent).",
                        ) { viewModel.updateBonusConfig("bump_y_max_days", it.toInt()) }
                        BonusSlider("Completions min", "${cfg.bumpZMin}", cfg.bumpZMin.toFloat(), 1f..10f, 8,
                            "Nombre minimum de completions dans la fenetre pour tirer une conclusion.",
                        ) { viewModel.updateBonusConfig("bump_z_min", it.toInt()) }
                        BonusSlider("Ratio retard", "${(cfg.bumpXRatio * 100).toInt()}%", cfg.bumpXRatio, 0.3f..1f, 13,
                            "Pourcentage de completions qui doivent etre en zone bonus pour qualifier 'systematiquement en retard'.",
                        ) { viewModel.updateBonusConfig("bump_x_ratio", it) }
                        BonusSlider("Grace period", "${(cfg.bumpGraceRatio * 100).toInt()}%", cfg.bumpGraceRatio, 0.1f..2f, 18,
                            "Apres proposition de bump, on attend ce % de la fenetre avant de re-analyser.",
                        ) { viewModel.updateBonusConfig("bump_grace_ratio", it) }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Infos app
                    Text(
                        text = "Infos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "v${com.torchons.app.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.checkBackend() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Backend",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (backendConnected) com.torchons.app.util.TaskUrgencyCalculator.VERT else com.torchons.app.util.TaskUrgencyCalculator.ROUGE)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (backendConnected) "Connecte" else "Hors ligne",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (backendConnected) com.torchons.app.util.TaskUrgencyCalculator.VERT else com.torchons.app.util.TaskUrgencyCalculator.ROUGE
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // v0.30 — Securite du foyer (regenerer code, quitter foyer)
        SecuritySection(
            viewModel = viewModel,
            onLoggedOut = onLogout,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // v0.30 — Soutenir / Discuter avec le dev (BMC + mailto)
        SupportDevCard()

        Spacer(modifier = Modifier.height(16.dp))

        // Deconnexion
        Button(
            onClick = {
                scope.launch {
                    viewModel.logout()
                    onLogout()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Deconnexion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Slider pour les parametres bonus/malus avec label + valeur + tooltip i.
 * Envoie onValueChangeEnd (pas onValueChange) pour ne PAS spammer le backend
 * a chaque pixel du drag.
 */
@Composable
private fun BonusSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tooltipDescription: String,
    onValueChangeEnd: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label : $valueLabel",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        InfoTooltip(
            title = label,
            description = tooltipDescription,
        )
    }
    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = { onValueChangeEnd(sliderValue) },
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}
