package com.torchons.app.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * Ecran d'accueil v0.30 — onboarding sans compte.
 *
 * Trois entrees :
 *  - Creer un foyer  -> CreateFoyerWizardScreen (B3)
 *  - Rejoindre un foyer  -> JoinFoyerScreen (B4)
 *  - Retrouver mon foyer  -> RestoreFoyerScreen (B5, lien discret)
 *
 * L'auto-login par IP Tailscale de la phase dogfood a ete retire le
 * 2026-10-05 : il reposait sur des routes qui delivraient un jeton sans secret.
 */
@Composable
fun WelcomeScreen(
    onCreateFoyerClick: () -> Unit,
    onJoinFoyerClick: () -> Unit,
    onRestoreFoyerClick: () -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CleaningServices,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.welcome_app_name),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.welcome_app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onCreateFoyerClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.welcome_create_foyer))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onJoinFoyerClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.welcome_join_foyer))
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRestoreFoyerClick) {
                Text(
                    text = stringResource(R.string.welcome_restore_foyer),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
