package com.torchons.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.local.TokenManager
import com.torchons.app.ui.navigation.TokenManagerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/*
 * The welcome screen, shown once per phone on the first arrival on the Home tab (a household
 * created or joined; never before Create / Join). Existing installs see it once after the update.
 * The "seen" flag lives in the local DataStore (TokenManager.firstRunWelcomeSeenFlow), not on the
 * server. Not to be confused with WelcomeScreen, the sign-in entry (Create / Join / Restore).
 * Texts: docs/plans/aide-et-bienvenue.md.
 */

/** Shows the welcome page until it is dismissed, then [content]. Blank for the instant the flag loads. */
@Composable
fun FirstRunWelcomeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tokenManager: TokenManager = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, TokenManagerEntryPoint::class.java)
            .tokenManager()
    }
    val seen by produceState<Boolean?>(initialValue = null, tokenManager) {
        tokenManager.firstRunWelcomeSeenFlow.collect { value = it }
    }
    val foyerName by produceState<String?>(initialValue = null, tokenManager) {
        tokenManager.currentFoyerNameFlow.collect { value = it }
    }
    val scope = rememberCoroutineScope()
    when (seen) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        false -> FirstRunWelcomeScreen(
            foyerName = foyerName ?: stringResource(R.string.settings_foyer_default_name),
            onDismiss = { scope.launch { tokenManager.markFirstRunWelcomeSeen() } },
        )
        else -> content()
    }
}

@Composable
private fun FirstRunWelcomeScreen(foyerName: String, onDismiss: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.first_run_welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                // Show rather than tell (Quentin, 2026-11-19): a paragraph that names a path ends
                // on « : » and the miniature below draws that path (WelcomeMiniatures.kt).
                Paragraph(R.string.first_run_welcome_p1)
                Paragraph(R.string.first_run_welcome_p2)
                MiniFoyerCard(foyerName)
                Spacer(Modifier.height(8.dp))
                Paragraph(R.string.first_run_welcome_p3)
                MiniHelpButton()
                Spacer(Modifier.height(8.dp))
                Paragraph(R.string.first_run_welcome_p4)
                MiniWriteToMe()
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.first_run_welcome_button))
            }
        }
    }
}

@Composable
private fun Paragraph(textRes: Int) {
    Text(text = stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
}
