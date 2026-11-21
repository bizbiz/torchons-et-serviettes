package com.torchons.app.ui.settings

import android.Manifest
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.LocalIsOnline
import com.torchons.app.ui.common.NetworkNeededSlot
import com.torchons.app.util.WifiHelper

// "Presence & absences" card of the household settings screen: home freeze + home WiFi.
@Composable
internal fun FoyerPresenceCard(
    viewModel: SettingsViewModel,
    homeFreezeEnabled: Boolean,
    homeSsids: List<String>,
    settingsLoaded: Boolean,
    onRequestWifiExplanation: () -> Unit,
    onOpenPresenceModal: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

        // Home freeze and the Wi-Fi names are server writes: disabled offline, with the reason shown.
        val isOnline = LocalIsOnline.current
        NetworkNeededSlot(visible = !isOnline)

        // Toggle mise en pause si absent (home freeze)
        SettingSwitch(
            title = stringResource(R.string.foyer_presence_freeze_title),
            subtitle = stringResource(R.string.foyer_presence_freeze_subtitle),
            checked = homeFreezeEnabled,
            onCheckedChange = { viewModel.setCurrentFoyerHomeFreezeEnabled(it) },
            enabled = isOnline,
        )

        AbsenceSheetLink(freezeEnabled = homeFreezeEnabled)

        // The personal "hide home tasks off WiFi" switch moved to Settings
        // (HomeWifiCard): it is the member's choice, not the household's.

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
            // Settings not loaded yet (offline start): the list is unknown, not empty.
            if (settingsLoaded) Text(
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
                        enabled = isOnline,
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
                    onRequestWifiExplanation()
                }
            },
            enabled = isOnline,
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
            onClick = onOpenPresenceModal,
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
}
