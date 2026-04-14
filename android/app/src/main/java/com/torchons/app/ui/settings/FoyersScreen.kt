package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Foyer
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ecran de gestion des foyers — permet au user de voir ses foyers,
 * d'en creer de nouveaux, et de switcher entre eux.
 *
 * Au switch, le cache Room local est wipe et un refresh complet du nouveau
 * foyer est declenche (pattern decide dans la grille v0.7.0, option A).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoyersScreen(
    apiService: ApiService,
    authRepository: AuthRepository,
    taskRepository: TaskRepository,
    onBack: () -> Unit,
) {
    var foyers by remember { mutableStateOf<List<Foyer>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var switchingTo by remember { mutableStateOf<Foyer?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val resp = apiService.getMyFoyers()
        if (resp.isSuccessful) foyers = resp.body() ?: emptyList()
        isLoading = false
    }

    fun reload() {
        CoroutineScope(Dispatchers.IO).launch {
            val resp = apiService.getMyFoyers()
            if (resp.isSuccessful) foyers = resp.body() ?: emptyList()
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Nouveau foyer", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    placeholder = { Text("Ex: Colloc Paris") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            showCreateDialog = false
                            CoroutineScope(Dispatchers.IO).launch {
                                apiService.createFoyer(FoyerCreateRequest(trimmed))
                                reload()
                            }
                        }
                    },
                    enabled = name.trim().isNotEmpty(),
                ) { Text("Creer") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Annuler") }
            },
        )
    }

    // Confirmation au switch (wipe cache + refresh)
    switchingTo?.let { target ->
        AlertDialog(
            onDismissRequest = { switchingTo = null },
            title = { Text("Changer de foyer ?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Tu vas basculer vers le foyer \"${target.name}\". L'app va " +
                        "vider son cache local et recharger les donnees du nouveau " +
                        "foyer. Ca prend quelques secondes.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = switchingTo!!
                    switchingTo = null
                    CoroutineScope(Dispatchers.IO).launch {
                        // 1. Switch cote backend (regenere JWT)
                        val result = authRepository.switchFoyer(t.id)
                        if (result.isSuccess) {
                            // 2. Wipe le cache Room local
                            taskRepository.wipeLocalCache()
                            // 3. Refresh tout depuis le nouveau foyer
                            taskRepository.refreshTasks()
                            taskRepository.refreshCategories()
                            taskRepository.refreshRooms()
                            taskRepository.refreshUsers()
                            taskRepository.refreshCompletions()
                            reload()
                        }
                    }
                }) { Text("Basculer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { switchingTo = null }) { Text("Annuler") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes foyers", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Retour",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, "Creer", tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(foyers, key = { it.id }) { foyer ->
                Card(
                    onClick = {
                        if (!foyer.isCurrent) switchingTo = foyer
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (foyer.isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(foyer.name, fontWeight = FontWeight.Bold)
                            Text(
                                text = foyer.role.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (foyer.isCurrent) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Foyer actif",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}
