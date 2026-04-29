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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Ecran d'accueil v0.30 — onboarding sans compte.
 *
 * Tente l'auto-login Tailscale en arriere-plan (fast-path dogfood pendant le
 * dogfood). Si echec, affiche 3 boutons :
 *  - Creer un foyer  -> CreateFoyerWizardScreen (B3)
 *  - Rejoindre un foyer  -> JoinFoyerScreen (B4)
 *  - Retrouver mon foyer  -> RestoreFoyerScreen (B5, lien discret)
 *
 * Cf. docs/design-onboarding-public.md §4 (welcome 3 boutons) et §6
 * (Tailscale auto-login conserve pendant le dogfood).
 */
@Composable
fun WelcomeScreen(
    onAutoLoginSuccess: () -> Unit,
    onCreateFoyerClick: () -> Unit,
    onJoinFoyerClick: () -> Unit,
    onRestoreFoyerClick: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.probe() }

    LaunchedEffect(uiState) {
        if (uiState is WelcomeUiState.AutoLoginSuccess) {
            onAutoLoginSuccess()
        }
    }

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
                text = "Torchons &\nServiettes",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Gerer les taches du foyer a plusieurs",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))

            when (uiState) {
                WelcomeUiState.Idle, WelcomeUiState.Probing,
                WelcomeUiState.AutoLoginSuccess -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Connexion...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WelcomeUiState.NoSession -> {
                    Button(
                        onClick = onCreateFoyerClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Creer un foyer")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onJoinFoyerClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rejoindre un foyer")
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = onRestoreFoyerClick) {
                        Text(
                            text = "Retrouver mon foyer",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
