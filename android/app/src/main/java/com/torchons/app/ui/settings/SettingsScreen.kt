package com.torchons.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
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
    /** P4a.2 — ouvre le wizard "Créer un foyer" (variante add-foyer). */
    onCreateFoyer: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()

    // POST_NOTIFICATIONS (Android 13+) est demandee ICI et nulle part ailleurs :
    // au moment ou l'utilisateur active une notification, donc quand il sait
    // deja de quoi il s'agit. Elle etait auparavant demandee dans
    // MainActivity.onCreate a chaque cold start, ce qui plaquait une popup
    // systeme par-dessus l'ecran de bienvenue d'une installation neuve.
    //
    // Le toggle metier reste independant de la permission OS : on enregistre
    // le choix quoi qu'il arrive. Un refus systeme n'efface pas la preference,
    // il empeche seulement l'affichage — et l'utilisateur peut la reaccorder
    // depuis les reglages Android sans avoir a re-basculer quoi que ce soit.
    val notifPermissionContext = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* refus : la preference est conservee, rien ne s'affichera. */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            notifPermissionContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Enregistre le choix, et ne demande la permission qu'a l'activation. */
    fun onNotifToggle(enabled: Boolean, persist: (Boolean) -> Unit) {
        if (enabled) ensureNotificationPermission()
        persist(enabled)
    }

    val displayName by viewModel.displayName.collectAsState()
    val s by viewModel.settings.collectAsState()
    val modules by viewModel.modules.collectAsState()
    // Vrai si le module est actif. Tant que GET /api/modules n'a pas repondu la
    // liste est vide et on repond `true` : l'ecran reste alors exactement ce
    // qu'il etait avant P2, au lieu de faire disparaitre des reglages le temps
    // d'un aller-retour reseau.
    fun moduleEnabled(key: String): Boolean =
        modules.firstOrNull { it.key == key }?.enabled ?: true

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

    val foyerName = currentFoyer?.name ?: stringResource(R.string.settings_foyer_default_name)

    // Non-null when the current household turned this collective module off: the personal
    // option stays visible but disabled, with a link to the household screen.
    fun moduleOffInfo(key: String): ModuleOffInfo? {
        val module = modules.firstOrNull { it.key == key } ?: return null
        if (module.enabled) return null
        return ModuleOffInfo(foyerName, module.title, onOpenFoyerSettings)
    }

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
            text = stringResource(R.string.settings_title),
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
                    // Sous-titre "Connecté via Tailscale" retiré : trompeur en
                    // beta. Le canal effectif est visible dans Infos > Build.
                    Text(
                        text = displayName ?: stringResource(R.string.settings_placeholder_loading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
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
                        text = stringResource(R.string.settings_foyer_card_title_format, foyerName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_foyer_card_subtitle),
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

        // P4a.2 — Créer un foyer = wizard onboarding (variante add-foyer :
        // nom/pièces/tâches puis POST /foyers + switch). Décision 3 v2.
        OutlinedButton(
            onClick = onCreateFoyer,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_create_foyer_button))
        }

        // P2 — le registre des modules. Niveau 1 de l'arbre : ce qui suit dans
        // l'ecran n'est que le niveau 2, revele module par module.
        ModulesSection(viewModel = viewModel, collective = false)

        ModuleToggleError(viewModel = viewModel)

        // Personal choice on the household's `motivation` module: hide points and streaks
        // for me only. Shown (disabled, with the reason) even when the household turned it off.
        HidePointsCard(
            hidden = !gamifUser,
            onHiddenChange = { hide -> viewModel.setGamificationEnabled(!hide) },
            moduleOff = moduleOffInfo("motivation"),
            helpLinkVisible = helpLinkVisible,
            onWhyNoPoints = { showWhyNoPointsDialog = true },
        )

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
                        text = stringResource(R.string.settings_changelog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_changelog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sous-options du module `reminders` : visibles seulement si le module
        // est actif. Les 6 notifications sont les sous-options de Rappels.
        // L'interrupteur du module vit dans le bloc Fonctionnalites ci-dessus ;
        // ici on ne montre que ce qu'il contient. Un reglage, un seul ecrivain.
        if (moduleEnabled("reminders")) {
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
                        text = stringResource(R.string.settings_notif_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resume quotidien
                SettingSwitch(
                    title = stringResource(R.string.settings_notif_daily_title),
                    subtitle = stringResource(R.string.settings_notif_daily_subtitle),
                    checked = notifDaily,
                    onCheckedChange = { enabled -> onNotifToggle(enabled, viewModel::setNotifDaily) }
                )

                // Heure du resume
                if (notifDaily) {
                    NotifHourPicker(hour = notifHour, onCommit = viewModel::setNotifHour)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Taches urgentes
                SettingSwitch(
                    title = stringResource(R.string.settings_notif_urgent_title),
                    subtitle = stringResource(R.string.settings_notif_urgent_subtitle),
                    checked = notifUrgent,
                    onCheckedChange = { enabled -> onNotifToggle(enabled, viewModel::setNotifUrgent) }
                )

                if (notifUrgent) {
                    NotifHourPicker(hour = s.notifOverdueHour, onCommit = viewModel::setNotifOverdueHour)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Trois flags orthogonaux pour les completions (v0.22) : on
                // ne gere plus un seul "notif_completed", l'utilisateur choisit
                // par categorie.
                SettingSwitch(
                    title = stringResource(R.string.settings_notif_solo_title),
                    subtitle = stringResource(R.string.settings_notif_solo_subtitle),
                    checked = notifSolo,
                    onCheckedChange = { enabled -> onNotifToggle(enabled, viewModel::setNotifSolo) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SettingSwitch(
                    title = stringResource(R.string.settings_notif_collective_with_me_title),
                    subtitle = stringResource(R.string.settings_notif_collective_with_me_subtitle),
                    checked = notifCollectiveWithMe,
                    onCheckedChange = { enabled -> onNotifToggle(enabled, viewModel::setNotifCollectiveWithMe) }
                )

                if (foyerMemberCount > 2) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingSwitch(
                        title = stringResource(R.string.settings_notif_collective_without_me_title),
                        subtitle = stringResource(R.string.settings_notif_collective_without_me_subtitle),
                        checked = notifCollectiveWithoutMe,
                        onCheckedChange = { enabled -> onNotifToggle(enabled, viewModel::setNotifCollectiveWithoutMe) }
                    )
                }
            }
        }
        }

        // Personal choice on the household's `repartition` module: the affinity popup.
        AffinityPopupCard(
            enabled = affinityPopup,
            onEnabledChange = { viewModel.setAffinityPopup(it) },
            moduleOff = moduleOffInfo("repartition"),
        )

        // Personal choice on the household's `presence` module.
        HomeWifiCard(
            hidden = s.hideHomeTasksOffWifi,
            onHiddenChange = { viewModel.setHideHomeTasksOffWifi(it) },
            moduleOff = moduleOffInfo("presence"),
        )

        // Infos app (relogees ici — etaient dans "Options avancees" qui a
        // disparu avec la refonte P1 ; doivent rester accessibles).
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_infos_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_infos_version),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.settings_infos_version_format, com.torchons.app.BuildConfig.VERSION_NAME),
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
                            text = stringResource(R.string.settings_infos_build),
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
                        text = stringResource(R.string.settings_infos_backend),
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
                        text = if (backendConnected) stringResource(R.string.settings_infos_backend_connected) else stringResource(R.string.settings_infos_backend_offline),
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
                text = stringResource(R.string.settings_logout),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        DeleteAccountSection(viewModel = viewModel, onDeleted = onLogout) // last section (privacy policy)
    }

    // P4a.2 — l'ancien dialog "nom seul" est remplacé par le wizard
    // (onCreateFoyer -> route create_foyer_wizard?add=true).

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
                // Si c'est le foyer courant, on bascule directement le module. Sinon
                // l'user doit switcher dessus d'abord — on ferme le dialog
                // et on l'oriente vers l'ecran Foyer (qui gere le switch).
                if (currentFoyer?.id == foyerId) {
                    // The household decision is the `motivation` module: same path as its switch.
                    viewModel.toggleModule("motivation", true)
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
