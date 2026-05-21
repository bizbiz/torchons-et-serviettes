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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
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

    val titleText = if (state.kind == "add_device") stringResource(R.string.invite_title_add_device)
    else stringResource(R.string.invite_title_invite_someone)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
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
                            contentDescription = stringResource(R.string.invite_regenerate_cd),
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
            val helpText = if (state.kind == "add_device")
                stringResource(R.string.invite_help_add_device)
            else
                stringResource(R.string.invite_help_invite_someone)
            Text(
                text = helpText,
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
                        Text(stringResource(R.string.wizard_retry))
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
                                contentDescription = stringResource(R.string.invite_qr_cd),
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
                            text = stringResource(R.string.invite_expires_format, expiresLabel),
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
                            text = stringResource(R.string.invite_code_preview_format, state.inviteToken!!.take(8)),
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

@Composable
private fun formatExpires(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val hhmm = try {
        val odt = OffsetDateTime.parse(iso)
        val local = odt.atZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) {
        return null
    }
    return stringResource(R.string.invite_expires_at_format, hhmm)
}
