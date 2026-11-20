package com.torchons.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import androidx.compose.ui.platform.LocalContext
import com.torchons.app.ui.common.LocalIsOnline
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.NetworkNeededNote
import com.torchons.app.ui.common.OnlineResult
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrorBlock
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.ui.common.runOnline
import com.torchons.app.ui.common.dismissKeyboardThen
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.common.submitKeyboardActions
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen
import com.torchons.app.data.model.ContextCreateRequest
import com.torchons.app.data.model.ContextUpdateRequest
import com.torchons.app.data.model.ContextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Household contexts — the screen replacing Rooms and Categories.
 *
 * Those two screens asked the user to maintain two lists that were nearly the
 * same, and to pick one home for each chore. A context is the single answer:
 * a room, a pet, an outdoor area, a situation.
 *
 * The two switches are the reason this screen is not just a renamed Rooms
 * screen. `requires_home` used to answer two questions with one value and was
 * necessarily wrong about one of them — a garden must be masked off home WiFi
 * yet must NOT be frozen during holidays, because grass keeps growing. Both
 * are exposed here in the user's words, not the model's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextsScreen(
    apiService: ApiService,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var contexts by remember { mutableStateOf<List<ContextView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<ContextView?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ContextView?>(null) }
    // The last refused write (create, edit, delete), translated: the taken name (409, the one the
    // user can fix) says so, anything else goes through UserErrors. Never a success by default (D7).
    val appContext = LocalContext.current
    val isOnline = LocalIsOnline.current
    var actionError by remember { mutableStateOf<UserError?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    fun errorOf(t: Throwable) = UserErrors.from(appContext, t) {
        if (it.httpCode == 409) appContext.getString(R.string.contexts_name_taken) else null
    }

    // Every call of this screen is guarded: offline, an uncaught ConnectException in a
    // Compose coroutine killed the app (emulator, 2026-11-03, retraced stack in todo.md).
    suspend fun reload() {
        try {
            val resp = apiService.getContexts()
            if (resp.isSuccessful) contexts = resp.body().orEmpty()
            unreachable = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unreachable = true
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contexts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = { HelpButton(HelpScreen.CONTEXTS) },
            )
        },
        floatingActionButton = {
            // A floating button cannot be disabled: offline it is replaced by the note, in the same
            // floating slot (a line above the list pushed every context down when the network dropped).
            if (isOnline) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_add))
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    NetworkNeededNote(Modifier.padding(12.dp))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            if (moduleEnabled(Modules.PRESENCE)) AbsenceSheetLink()
            actionError?.let {
                Spacer(Modifier.height(8.dp))
                UserErrorBlock(it, "Contexts")
            }
            Spacer(Modifier.height(12.dp))

            if (unreachable) {
                Text(
                    text = stringResource(R.string.contexts_unreachable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (!loading && contexts.isEmpty()) {
                Text(
                    text = stringResource(R.string.contexts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contexts, key = { it.id }) { ctx ->
                    ContextRow(
                        context = ctx,
                        onClick = { editing = ctx },
                        onDelete = { pendingDelete = ctx },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        val cible = editing
        ContextEditDialog(
            initial = cible,
            onDismiss = { creating = false; editing = null },
            onConfirm = { nom, emoji, presence, accrues ->
                scope.launch {
                    actionError = null
                    val result = runOnline(::errorOf) {
                        if (cible == null) {
                            apiService.createContext(
                                ContextCreateRequest(
                                    name = nom, emoji = emoji,
                                    requiresPresence = presence, accruesWhenAway = accrues,
                                )
                            )
                        } else {
                            apiService.updateContext(
                                cible.id,
                                ContextUpdateRequest(
                                    name = nom, emoji = emoji,
                                    requiresPresence = presence, accruesWhenAway = accrues,
                                )
                            )
                        }
                    }
                    creating = false; editing = null
                    // A 409 (name taken) says so, any other failure is told: only a 2xx reloads.
                    when (result) {
                        is OnlineResult.Done -> reload()
                        is OnlineResult.Failed -> actionError = result.error
                    }
                }
            },
        )
    }

    pendingDelete?.let { ctx ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(ctx.label) },
            text = {
                Column {
                    Text(stringResource(R.string.contexts_dialog_delete_message))
                    if (!isOnline) NetworkNeededNote(Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            actionError = null
                            val result = runOnline(::errorOf) { apiService.deleteContext(ctx.id) }
                            pendingDelete = null
                            when (result) {
                                is OnlineResult.Done -> reload()
                                is OnlineResult.Failed -> actionError = result.error
                            }
                        }
                    },
                    enabled = isOnline,
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ContextRow(
    context: ContextView,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // La ligne entiere ouvre l'edition : un bouton « Modifier » en plus du
    // nom repeterait l'action sans rien ajouter.
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(runCatching { Color(android.graphics.Color.parseColor(context.color)) }
                        .getOrDefault(MaterialTheme.colorScheme.surfaceVariant)),
                contentAlignment = Alignment.Center,
            ) { context.emoji?.let { Text(it) } }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(context.name, fontWeight = FontWeight.Medium)
                val marqueurs = buildList {
                    if (context.requiresPresence) add(stringResource(R.string.context_requires_presence_label))
                    if (context.accruesWhenAway) add(stringResource(R.string.context_accrues_when_away_label))
                }
                if (marqueurs.isNotEmpty()) {
                    Text(
                        text = marqueurs.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = LocalIsOnline.current) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun ContextEditDialog(
    initial: ContextView?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, Boolean, Boolean) -> Unit,
) {
    var nom by remember { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(initial?.emoji.orEmpty()) }
    var presence by remember { mutableStateOf(initial?.requiresPresence ?: true) }
    var accrues by remember { mutableStateOf(initial?.accruesWhenAway ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                if (initial == null) R.string.contexts_dialog_new_title
                else R.string.contexts_dialog_edit_title
            ))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = nom,
                    onValueChange = { nom = it },
                    label = { Text(stringResource(R.string.common_name_label)) },
                    singleLine = true,
                    keyboardActions = submitKeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(2) },
                    label = { Text(stringResource(R.string.rooms_dialog_emoji_label)) },
                    placeholder = { Text(stringResource(R.string.rooms_dialog_emoji_placeholder)) },
                    singleLine = true,
                    keyboardActions = submitKeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                DrapeauLigne(
                    titre = stringResource(R.string.context_requires_presence_label),
                    aide = stringResource(R.string.context_requires_presence_help),
                    valeur = presence,
                    onChange = { presence = it },
                )
                Spacer(Modifier.height(12.dp))
                DrapeauLigne(
                    titre = stringResource(R.string.context_accrues_when_away_label),
                    aide = stringResource(R.string.context_accrues_when_away_help),
                    valeur = accrues,
                    onChange = { accrues = it },
                )
                if (!LocalIsOnline.current) NetworkNeededNote(Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = dismissKeyboardThen {
                    onConfirm(nom.trim(), emoji.trim().ifBlank { null }, presence, accrues)
                },
                enabled = nom.isNotBlank() && LocalIsOnline.current,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun DrapeauLigne(
    titre: String,
    aide: String,
    valeur: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titre, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                aide,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = valeur, onCheckedChange = onChange)
    }
}
