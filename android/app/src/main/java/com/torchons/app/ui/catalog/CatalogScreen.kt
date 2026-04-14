package com.torchons.app.ui.catalog

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.torchons.app.data.model.CatalogCategoryView
import com.torchons.app.data.model.CatalogResponse
import com.torchons.app.data.model.CatalogRoomView
import com.torchons.app.data.model.CatalogTaskView
import com.torchons.app.data.model.ImportApplyRequest
import com.torchons.app.data.model.ImportApplyResponse
import com.torchons.app.data.model.ImportDecision
import com.torchons.app.data.model.ImportResolveRequest
import com.torchons.app.data.model.ImportResolveResponse
import com.torchons.app.data.model.ResolvedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ecran du catalogue de templates (v0.9.0 Feature 4).
 *
 * 3 tabs (Pieces / Categories / Taches). L'user peut multi-selectionner
 * des items sur chaque tab. Le compteur de selection est maintenu global,
 * et le bouton "Importer N" declenche le flow :
 *
 * 1. POST /api/catalog/import/resolve -> on recupere les propositions de
 *    mapping pour les rooms/categories referencees.
 * 2. Affiche le modal de resolution ou l'user confirme/modifie les decisions.
 * 3. POST /api/catalog/import/apply -> execute l'import.
 * 4. Snackbar/toast de succes, pop back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    apiService: ApiService,
    onBack: () -> Unit,
) {
    var catalog by remember { mutableStateOf<CatalogResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Taches", "Pieces", "Categories")

    // Selections (par key)
    val selectedTasks = remember { mutableStateMapOf<String, Boolean>() }
    val selectedRooms = remember { mutableStateMapOf<String, Boolean>() }
    val selectedCategories = remember { mutableStateMapOf<String, Boolean>() }

    var resolution by remember { mutableStateOf<ImportResolveResponse?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportApplyResponse?>(null) }

    LaunchedEffect(Unit) {
        val resp = apiService.getCatalog()
        if (resp.isSuccessful) catalog = resp.body()
        isLoading = false
    }

    fun triggerResolve() {
        val tasks = selectedTasks.filterValues { it }.keys.toList()
        val rooms = selectedRooms.filterValues { it }.keys.toList()
        val cats = selectedCategories.filterValues { it }.keys.toList()
        if (tasks.isEmpty() && rooms.isEmpty() && cats.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val resp = apiService.resolveCatalogImport(
                ImportResolveRequest(taskKeys = tasks, roomKeys = rooms, categoryKeys = cats)
            )
            if (resp.isSuccessful) {
                resolution = resp.body()
            }
        }
    }

    fun applyImport(
        roomDecisions: List<ImportDecision>,
        categoryDecisions: List<ImportDecision>,
    ) {
        isImporting = true
        val tasks = selectedTasks.filterValues { it }.keys.toList()
        val rooms = selectedRooms.filterValues { it }.keys.toList()
        val cats = selectedCategories.filterValues { it }.keys.toList()
        CoroutineScope(Dispatchers.IO).launch {
            val resp = apiService.applyCatalogImport(
                ImportApplyRequest(
                    taskKeys = tasks,
                    roomDecisions = roomDecisions,
                    categoryDecisions = categoryDecisions,
                    roomKeys = rooms,
                    categoryKeys = cats,
                )
            )
            if (resp.isSuccessful) {
                result = resp.body()
                resolution = null
            }
            isImporting = false
        }
    }

    // Modal de resolution
    resolution?.let { res ->
        CatalogResolutionDialog(
            resolution = res,
            onConfirm = { roomDecisions, categoryDecisions ->
                applyImport(roomDecisions, categoryDecisions)
            },
            onDismiss = { resolution = null },
        )
    }

    // Modal de succes apres apply
    result?.let { r ->
        AlertDialog(
            onDismissRequest = {
                result = null
                selectedTasks.clear()
                selectedRooms.clear()
                selectedCategories.clear()
                onBack()
            },
            title = { Text("Import reussi", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (r.createdRooms > 0) Text("- ${r.createdRooms} piece${if (r.createdRooms > 1) "s" else ""} creee${if (r.createdRooms > 1) "s" else ""}")
                    if (r.createdCategories > 0) Text("- ${r.createdCategories} categorie${if (r.createdCategories > 1) "s" else ""} creee${if (r.createdCategories > 1) "s" else ""}")
                    if (r.createdTasks > 0) Text("- ${r.createdTasks} tache${if (r.createdTasks > 1) "s" else ""} creee${if (r.createdTasks > 1) "s" else ""}")
                    if (r.skipped > 0) Text("- ${r.skipped} ignore${if (r.skipped > 1) "s" else ""}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    result = null
                    selectedTasks.clear()
                    selectedRooms.clear()
                    selectedCategories.clear()
                    onBack()
                }) { Text("OK") }
            },
        )
    }

    val totalSelected = selectedTasks.count { it.value } +
        selectedRooms.count { it.value } +
        selectedCategories.count { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogue", fontWeight = FontWeight.Bold) },
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
            if (totalSelected > 0 && !isImporting) {
                ExtendedFloatingActionButton(
                    onClick = { triggerResolve() },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Importer $totalSelected", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            if (isLoading || catalog == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val cat = catalog!!
            when (selectedTab) {
                0 -> TasksTab(cat.tasks, selectedTasks)
                1 -> RoomsTab(cat.rooms, selectedRooms)
                2 -> CategoriesTab(cat.categories, selectedCategories)
            }
        }
    }
}

@Composable
private fun TasksTab(
    tasks: List<CatalogTaskView>,
    selected: MutableMap<String, Boolean>,
) {
    val sorted = remember(tasks) { tasks.sortedBy { it.title.lowercase() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorted, key = { it.key }) { task ->
            val isSelected = selected[task.key] == true
            Card(
                onClick = { selected[task.key] = !isSelected },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { selected[task.key] = it },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.SemiBold)
                        if (!task.description.isNullOrBlank()) {
                            Text(
                                text = task.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val recurrenceLabel = recurrenceToLabel(task.defaultRecurrence)
                        Text(
                            text = "$recurrenceLabel · ${task.defaultWeight * 5} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun RoomsTab(
    rooms: List<CatalogRoomView>,
    selected: MutableMap<String, Boolean>,
) {
    val sorted = remember(rooms) { rooms.sortedBy { it.name.lowercase() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorted, key = { it.key }) { room ->
            val isSelected = selected[room.key] == true
            Card(
                onClick = { selected[room.key] = !isSelected },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { selected[room.key] = it },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    if (!room.emoji.isNullOrBlank()) {
                        Text(room.emoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(room.name, fontWeight = FontWeight.Medium)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun CategoriesTab(
    categories: List<CatalogCategoryView>,
    selected: MutableMap<String, Boolean>,
) {
    val sorted = remember(categories) { categories.sortedBy { it.name.lowercase() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorted, key = { it.key }) { cat ->
            val isSelected = selected[cat.key] == true
            val color = try { Color(android.graphics.Color.parseColor(cat.color)) } catch (_: Exception) { Color.Gray }
            Card(
                onClick = { selected[cat.key] = !isSelected },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { selected[cat.key] = it },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(cat.name, fontWeight = FontWeight.Medium)
                        if (cat.requiresHome) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Home,
                                    null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "A la maison",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/**
 * Modal de resolution de mapping. Pour chaque room/category referencee,
 * affiche 3 choix radio : creer nouvelle / mapper sur existant / ignorer.
 * Les decisions sont pre-selectionnees selon ce que le backend a resolu
 * (already_same_template -> map verrouille, map_existing -> map
 * pre-coche, create -> create pre-coche).
 */
@Composable
private fun CatalogResolutionDialog(
    resolution: ImportResolveResponse,
    onConfirm: (roomDecisions: List<ImportDecision>, categoryDecisions: List<ImportDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Decision par template_key : "create" / "map" / "map_to_id"
    val roomChoices = remember {
        mutableStateMapOf<String, String>().apply {
            resolution.rooms.forEach {
                put(it.templateKey, if (it.strategy == "create") "create" else "map")
            }
        }
    }
    val categoryChoices = remember {
        mutableStateMapOf<String, String>().apply {
            resolution.categories.forEach {
                put(it.templateKey, if (it.strategy == "create") "create" else "map")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rattachement", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resolution.rooms.isEmpty() && resolution.categories.isEmpty()) {
                    Text("Rien a mapper, les tachs seront creees telles quelles.")
                } else {
                    Text(
                        "Les taches que tu importes referencent des pieces / categories. Comment tu veux les rattacher ?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                resolution.rooms.forEach { r ->
                    ResolutionItemBlock(
                        label = "Piece : ${r.templateName}",
                        resolved = r,
                        currentChoice = roomChoices[r.templateKey] ?: "create",
                        onChoiceChange = { roomChoices[r.templateKey] = it },
                    )
                }
                resolution.categories.forEach { c ->
                    ResolutionItemBlock(
                        label = "Categorie : ${c.templateName}",
                        resolved = c,
                        currentChoice = categoryChoices[c.templateKey] ?: "create",
                        onChoiceChange = { categoryChoices[c.templateKey] = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val roomDecisions = resolution.rooms.map { r ->
                    val choice = roomChoices[r.templateKey] ?: "create"
                    ImportDecision(
                        templateKey = r.templateKey,
                        decision = choice,
                        mapToId = if (choice == "map") r.existingId else null,
                    )
                }
                val categoryDecisions = resolution.categories.map { c ->
                    val choice = categoryChoices[c.templateKey] ?: "create"
                    ImportDecision(
                        templateKey = c.templateKey,
                        decision = choice,
                        mapToId = if (choice == "map") c.existingId else null,
                    )
                }
                onConfirm(roomDecisions, categoryDecisions)
            }) { Text("Importer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ResolutionItemBlock(
    label: String,
    resolved: ResolvedItem,
    currentChoice: String,
    onChoiceChange: (String) -> Unit,
) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (resolved.strategy == "already_same_template") {
            Text(
                "Deja rattache a \"${resolved.existingName}\". Sera reutilise.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = currentChoice == "create",
                    onClick = { onChoiceChange("create") },
                )
                Text("Creer nouvelle", style = MaterialTheme.typography.bodySmall)
            }
            if (resolved.existingId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = currentChoice == "map",
                        onClick = { onChoiceChange("map") },
                    )
                    Text(
                        "Mapper sur \"${resolved.existingName}\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun recurrenceToLabel(rule: Map<String, Any>?): String {
    if (rule == null) return "Ponctuelle"
    val type = rule["type"] as? String ?: return "?"
    return when (type) {
        "interval" -> {
            val n = (rule["every_n_days"] as? Number)?.toInt() ?: 1
            "Tous les $n j"
        }
        "monthly" -> "Tous les mois"
        "weekdays" -> "Chaque semaine"
        "custom" -> "Recurrent"
        else -> type
    }
}
