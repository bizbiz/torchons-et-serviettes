package com.torchons.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import qrcode.QRCode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ecran "Inviter quelqu'un" (v0.30, Phase B6).
 *
 * Affiche le QR code encodant l'invite_token genere via /api/pairing/init.
 * Le joiner scanne ce QR depuis l'app via JoinFoyerScreen. TTL 15 min ;
 * bouton refresh genere un nouveau token.
 *
 * Cf. docs/design-onboarding-public.md §3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteQrScreen(
    onBack: () -> Unit,
    viewModel: InviteQrViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inviter quelqu'un") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.generate() },
                        enabled = !state.isLoading,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Generer un nouveau code",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "Demande a la personne d'installer l'app et de choisir \"Rejoindre un foyer\". Elle pourra ensuite scanner ce QR.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when {
                state.isLoading && state.inviteToken == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.generate() }) {
                        Text("Reessayer")
                    }
                }

                state.inviteToken != null -> {
                    val qrBitmap = remember(state.inviteToken) {
                        renderQr(state.inviteToken!!)
                    }
                    if (qrBitmap != null) {
                        Surface(
                            color = Color.White,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR code d'invitation",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .padding(16.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.foyerName ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    val expiresLabel = formatExpires(state.expiresAt)
                    if (expiresLabel != null) {
                        Text(
                            text = "Expire $expiresLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "Code : ${state.inviteToken!!.take(8)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Genere un QR code PNG en bitmap a partir du token. QRCode-Kotlin v4 expose
 * `renderToBytes()` qui retourne directement les bytes PNG ; on les decode
 * via BitmapFactory pour obtenir un ImageBitmap utilisable par Compose.
 */
private fun renderQr(content: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val pngBytes = QRCode.ofSquares()
            .withSize(16)
            .build(content)
            .renderToBytes()
        val androidBmp = android.graphics.BitmapFactory.decodeByteArray(
            pngBytes, 0, pngBytes.size,
        ) ?: return null
        androidBmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun formatExpires(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val odt = OffsetDateTime.parse(iso)
        val local = odt.atZoneSameInstant(ZoneId.systemDefault())
        "a ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    } catch (_: Exception) {
        null
    }
}
