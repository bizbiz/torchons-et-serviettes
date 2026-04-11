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
    onOpenChangelog: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val displayName by viewModel.displayName.collectAsState()
    val s by viewModel.settings.collectAsState()
    val notifDaily = s.notifDaily
    val notifUrgent = s.notifUrgent
    val notifCompleted = s.notifCompleted
    val notifHour = s.notifHour
    val affinityPopup = s.affinityPopup
    val freshnessThreshold = s.freshnessThreshold
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
                            text = "Heure : ${notifHour}h00",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Slider(
                        value = notifHour.toFloat(),
                        onValueChange = { viewModel.setNotifHour(it.toInt()) },
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

                // Taches completees
                SettingSwitch(
                    title = "Taches completees",
                    subtitle = "Notification quand l'autre complete une tache",
                    checked = notifCompleted,
                    onCheckedChange = { viewModel.setNotifCompleted(it) }
                )
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

                    // Seuil de suggestion
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Seuil de suggestion : ${freshnessThreshold}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        InfoTooltip(
                            title = "Seuil de suggestion",
                            description = "Les taches sont suggerees sur l'accueil quand leur fraicheur passe sous ce seuil. Plus le seuil est bas, moins il y a de suggestions."
                        )
                    }
                    Slider(
                        value = freshnessThreshold.toFloat(),
                        onValueChange = { viewModel.setFreshnessThreshold(it.toInt()) },
                        valueRange = 5f..95f,
                        steps = 17,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                                .background(if (backendConnected) com.torchons.app.util.FreshnessCalculator.VERT else com.torchons.app.util.FreshnessCalculator.ROUGE)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (backendConnected) "Connecte" else "Hors ligne",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (backendConnected) com.torchons.app.util.FreshnessCalculator.VERT else com.torchons.app.util.FreshnessCalculator.ROUGE
                        )
                    }
                }
            }
        }

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
