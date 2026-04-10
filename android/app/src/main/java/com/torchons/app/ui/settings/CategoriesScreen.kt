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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
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
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CategoryCreateRequest
import com.torchons.app.data.model.CategoryUpdateRequest
import com.torchons.app.ui.common.InfoTooltip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    apiService: ApiService,
    onBack: () -> Unit
) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<Category?>(null) }
    var deleteCategory by remember { mutableStateOf<Category?>(null) }

    // Charger les categories
    LaunchedEffect(Unit) {
        val resp = apiService.getCategories()
        if (resp.isSuccessful) categories = resp.body() ?: emptyList()
    }

    fun reload() {
        CoroutineScope(Dispatchers.IO).launch {
            val resp = apiService.getCategories()
            if (resp.isSuccessful) categories = resp.body() ?: emptyList()
        }
    }

    // Dialog creation
    if (showCreateDialog) {
        CategoryEditDialog(
            title = "Nouvelle categorie",
            initialName = "",
            initialColor = "#6366F1",
            initialRequiresHome = false,
            onSave = { name, color, reqHome ->
                showCreateDialog = false
                CoroutineScope(Dispatchers.IO).launch {
                    apiService.createCategory(CategoryCreateRequest(name, color, requiresHome = reqHome))
                    reload()
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    // Dialog edition
    if (editCategory != null) {
        val cat = editCategory!!
        CategoryEditDialog(
            title = "Modifier ${cat.name}",
            initialName = cat.name,
            initialColor = cat.color,
            initialRequiresHome = cat.requiresHome,
            onSave = { name, color, reqHome ->
                editCategory = null
                CoroutineScope(Dispatchers.IO).launch {
                    apiService.updateCategory(cat.id, CategoryUpdateRequest(name, color, requiresHome = reqHome))
                    reload()
                }
            },
            onDismiss = { editCategory = null }
        )
    }

    // Dialog suppression
    if (deleteCategory != null) {
        val cat = deleteCategory!!
        AlertDialog(
            onDismissRequest = { deleteCategory = null },
            title = { Text("Supprimer ${cat.name} ?") },
            text = { Text("Les taches de cette categorie seront deplacees en 'sans categorie'.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteCategory = null
                        CoroutineScope(Dispatchers.IO).launch {
                            apiService.deleteCategory(cat.id)
                            reload()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteCategory = null }) { Text("Annuler") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories", fontWeight = FontWeight.Bold) },
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
            items(categories, key = { it.id }) { cat ->
                val catColor = try {
                    Color(android.graphics.Color.parseColor(cat.color))
                } catch (_: Exception) { Color.Gray }

                Card(
                    onClick = { editCategory = cat },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(catColor)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cat.name, fontWeight = FontWeight.Medium)
                            if (cat.requiresHome) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Home, null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "A la maison",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { deleteCategory = cat }) {
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
private fun CategoryEditDialog(
    title: String,
    initialName: String,
    initialColor: String,
    initialRequiresHome: Boolean,
    onSave: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var requiresHome by remember { mutableStateOf(initialRequiresHome) }

    val colors = listOf("#10B981", "#F59E0B", "#6366F1", "#EC4899", "#8B5CF6", "#F97316", "#06B6D4", "#EF4444", "#6B7280")

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
                    singleLine = true
                )

                Text("Couleur", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c ->
                        val parsed = try { Color(android.graphics.Color.parseColor(c)) } catch (_: Exception) { Color.Gray }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .then(
                                    if (c == color) Modifier.padding(2.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(parsed)
                                    else Modifier
                                )
                                .let { mod ->
                                    mod
                                }
                        )
                    }
                }
                // Simpler color selection with clickable boxes
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEach { c ->
                        val parsed = try { Color(android.graphics.Color.parseColor(c)) } catch (_: Exception) { Color.Gray }
                        val isSelected = c == color
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 36.dp else 28.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .let { if (isSelected) it.padding(3.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(parsed) else it },
                            contentAlignment = Alignment.Center
                        ) {
                            // Clickable wrapper
                            androidx.compose.material3.Surface(
                                onClick = { color = c },
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Transparent
                            ) {}
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("A la maison uniquement", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(4.dp))
                            InfoTooltip(
                                title = "A la maison",
                                description = "Si active, les taches de cette categorie ne seront suggerees et visibles que lorsque tu es connecte au WiFi de ta maison."
                            )
                        }
                    }
                    Switch(checked = requiresHome, onCheckedChange = { requiresHome = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, color, requiresHome) },
                enabled = name.isNotBlank()
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
