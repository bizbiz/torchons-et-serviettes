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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.torchons.app.ui.common.FoyerGamifState
import com.torchons.app.ui.common.WhyNoPointsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Reglages principaux (residuel apres la refonte P1 ecran Foyer).
 *
 * Ne contient plus que ce qui est lie au COMPTE / a l'app (pas au foyer
 * en particulier) : Profil, entree prominente "🏠 <nom>" vers l'ecran
 * Foyer, bouton "Creer un nouveau foyer", gamification de mon compte,
 * notifications, popup affinite, nouveautes, infos version/build/backend,
 * soutien dev, deconnexion.
 *
 * Tout le foyer-scope (pieces, categories, presence/WiFi, gamif foyer,
 * bonus/malus, quitter le foyer, ...) ET les sliders compte-scope
 * (renouvellement, tolerance fair-share) ont migre dans FoyerSettingsScreen.
 */
@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onOpenChangelog: () -> Unit = {},
    onOpenFoyerSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
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
    val backendConnected by viewModel.backendConnected.collectAsState()

    // v0.32 : etat gamification (user-level + foyer courant + liste de tous
    // les foyers pour le dialog "Pourquoi pas de points"). Visible via le
    // toggle prominent en haut, et via le help link conditionnel.
    val gamifUser = s.gamificationEnabled
    val currentFoyer by viewModel.currentFoyer.collectAsState()
    val allFoyers by viewModel.allFoyers.collectAsState()
    val anyFoyerOff = allFoyers.any { !it.gamificationEnabled }
    val helpLinkVisible = !gamifUser || anyFoyerOff

    var showWhyNoPointsDialog by remember { mutableStateOf(false) }
    var showCreateFoyerDialog by remember { mutableStateOf(false) }

    val foyerName = currentFoyer?.name ?: "Mon foyer"

    LaunchedEffect(Unit) {
        viewModel.checkBackend()
        // P4a.1 sweep — l'onglet Réglages a une instance SettingsViewModel
        // DISTINCTE de l'écran Foyer (hiltViewModel par destination) : on
        // re-fetch les foyers à chaque (ré)apparition pour que la ligne
        // "🏠 <nom>" reflète un switch/leave fait dans l'écran Foyer.
        viewModel.refreshFoyers()
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

        // P1 refonte — Entree prominente vers l'ecran Foyer. Remplace
        // entierement l'ancienne card "Mes foyers" (design decision 9).
        Card(
            onClick = onOpenFoyerSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            ),
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
                        text = "🏠 $foyerName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pieces, categories, presence, gamification du foyer...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // P1 refonte — Creer un nouveau foyer (remplace le FAB de l'ancien
        // ecran "Mes foyers", design decision 3). POST /api/foyers puis
        // bascule auto sur le nouveau foyer.
        OutlinedButton(
            onClick = { showCreateFoyerDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Creer un nouveau foyer")
        }

        // v0.32 : Gamification user-level + help link conditionnel.
        // DISTINCTE de la gamification du foyer (qui, elle, vit dans
        // l'ecran Foyer). Ici = "pour mon compte, tous foyers confondus".
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎯", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Gamification",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activer la gamification pour mon compte",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Si desactivee, tu ne verras plus les points, stats et bonus dans aucun de tes foyers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = gamifUser,
                        onCheckedChange = { viewModel.setGamificationEnabled(it) },
                    )
                }

                if (helpLinkVisible) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showWhyNoPointsDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Pourquoi je n'ai pas de points pour certaines taches ?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

        // Infos app (relogees ici — etaient dans "Options avancees" qui a
        // disparu avec la refonte P1 ; doivent rester accessibles).
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Infos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
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
                // Marqueur de build (beta uniquement : BUILD_INFO vide en
                // prod). Permet de verifier de visu QUEL build est installe
                // (date + SHA git court), le versionName ne bougeant pas
                // entre 2 builds dev sur la meme version.
                if (com.torchons.app.BuildConfig.BUILD_INFO.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Build",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = com.torchons.app.BuildConfig.BUILD_INFO,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

    // P1 refonte — Dialog "Creer un nouveau foyer". Reutilise le meme
    // endpoint que l'ancien ecran Mes foyers (POST /api/foyers via
    // SettingsViewModel.createAndSwitchToFoyer), puis bascule dessus.
    if (showCreateFoyerDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFoyerDialog = false },
            title = { Text("Nouveau foyer", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    placeholder = { Text("Ex: Colloc Paris") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            showCreateFoyerDialog = false
                            viewModel.createAndSwitchToFoyer(trimmed)
                        }
                    },
                    enabled = name.trim().isNotEmpty(),
                ) { Text("Creer") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFoyerDialog = false }) { Text("Annuler") }
            },
        )
    }

    // v0.32 : help dialog "Pourquoi pas de points ?" (dynamique selon
    // l'etat user-level + foyer-level + multi-foyer).
    if (showWhyNoPointsDialog) {
        val foyerStates = allFoyers.map { f ->
            FoyerGamifState(
                id = f.id,
                name = f.name,
                gamificationEnabled = f.gamificationEnabled,
                isCurrent = f.isCurrent,
            )
        }
        WhyNoPointsDialog(
            userOff = !gamifUser,
            foyers = foyerStates,
            onReactivateUser = {
                viewModel.setGamificationEnabled(true)
                showWhyNoPointsDialog = false
            },
            onReactivateFoyer = { foyerId ->
                // Si c'est le foyer courant, on toggle directement. Sinon
                // l'user doit switcher dessus d'abord — on ferme le dialog
                // et on l'oriente vers l'ecran Foyer (qui gere le switch).
                if (currentFoyer?.id == foyerId) {
                    viewModel.setCurrentFoyerGamificationEnabled(true)
                    showWhyNoPointsDialog = false
                } else {
                    showWhyNoPointsDialog = false
                    onOpenFoyerSettings()
                }
            },
            onDismiss = { showWhyNoPointsDialog = false },
        )
    }
}

// SettingSwitch + BonusSlider extraits dans SettingsComponents.kt (P1
// refonte ecran Foyer) — reutilises par SettingsScreen et FoyerSettingsScreen.
