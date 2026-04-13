package com.torchons.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.RoomCreateRequest
import com.torchons.app.data.model.RoomUpdateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ecran de gestion des pieces. Mirror du pattern CategoriesScreen.
 *
 * Permet de creer / editer / supprimer les pieces. Chaque piece a un nom
 * (unique), une couleur, et optionnellement une icone Material ou un emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    apiService: ApiService,
    onBack: () -> Unit
) {
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editRoom by remember { mutableStateOf<Room?>(null) }
    var deleteRoom by remember { mutableStateOf<Room?>(null) }

    LaunchedEffect(Unit) {
        val resp = apiService.getRooms()
        if (resp.isSuccessful) rooms = resp.body() ?: emptyList()
    }

    fun reload() {
        CoroutineScope(Dispatchers.IO).launch {
            val resp = apiService.getRooms()
            if (resp.isSuccessful) rooms = resp.body() ?: emptyList()
        }
    }

    if (showCreateDialog) {
        RoomEditDialog(
            title = "Nouvelle piece",
            initialName = "",
            initialColor = "#6366F1",
            initialEmoji = "",
            onSave = { name, color, emoji ->
                showCreateDialog = false
                CoroutineScope(Dispatchers.IO).launch {
                    apiService.createRoom(
                        RoomCreateRequest(
                            name = name,
                            color = color,
                            emoji = emoji.ifBlank { null },
                        )
                    )
                    reload()
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (editRoom != null) {
        val r = editRoom!!
        RoomEditDialog(
            title = "Modifier ${r.name}",
            initialName = r.name,
            initialColor = r.color,
            initialEmoji = r.emoji ?: "",
            onSave = { name, color, emoji ->
                editRoom = null
                CoroutineScope(Dispatchers.IO).launch {
                    apiService.updateRoom(
                        r.id,
                        RoomUpdateRequest(
                            name = name,
                            color = color,
                            emoji = emoji.ifBlank { null },
                        )
                    )
                    reload()
                }
            },
            onDismiss = { editRoom = null }
        )
    }

    if (deleteRoom != null) {
        val r = deleteRoom!!
        AlertDialog(
            onDismissRequest = { deleteRoom = null },
            title = { Text("Supprimer ${r.name} ?") },
            text = { Text("Les taches de cette piece n'auront plus de piece assignee.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteRoom = null
                        CoroutineScope(Dispatchers.IO).launch {
                            apiService.deleteRoom(r.id)
                            reload()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteRoom = null }) { Text("Annuler") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pieces", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Ajouter", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rooms, key = { it.id }) { room ->
                val roomColor = remember(room.color) {
                    try { Color(android.graphics.Color.parseColor(room.color)) }
                    catch (_: Exception) { Color.Gray }
                }

                Card(
                    onClick = { editRoom = room },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pastille couleur
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(roomColor),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!room.emoji.isNullOrBlank()) {
                                Text(room.emoji, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            room.name,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deleteRoom = room }) {
                            Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RoomEditDialog(
    title: String,
    initialName: String,
    initialColor: String,
    initialEmoji: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var emoji by remember { mutableStateOf(initialEmoji) }

    val colors = listOf(
        "#10B981", "#F59E0B", "#6366F1", "#EC4899",
        "#8B5CF6", "#F97316", "#06B6D4", "#EF4444", "#6B7280",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = emoji,
                    onValueChange = { if (it.length <= 4) emoji = it },
                    label = { Text("Emoji (optionnel)") },
                    placeholder = { Text("Ex: 🛁") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Couleur", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEach { c ->
                        val parsed = remember(c) {
                            try { Color(android.graphics.Color.parseColor(c)) }
                            catch (_: Exception) { Color.Gray }
                        }
                        val isSelected = c == color
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 36.dp else 28.dp)
                                .clip(CircleShape)
                                .background(parsed),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Surface(
                                onClick = { color = c },
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Transparent,
                            ) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, color, emoji) },
                enabled = name.isNotBlank()
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
