package com.torchons.app.ui.taskedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.ui.common.InfoTooltip

/**
 * Ecran de creation ou edition d'une tache.
 * Si taskId est null = creation, sinon edition.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditScreen(
    taskId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit = {},
    viewModel: TaskEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Charger les donnees au lancement
    LaunchedEffect(taskId) {
        viewModel.loadData(taskId)
    }

    // Naviguer en arriere apres sauvegarde
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    // Naviguer a la liste apres suppression
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
    }

    // Afficher les erreurs
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Dialog de confirmation de suppression
    if (showDeleteDialog && taskId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer la tache ?") },
            text = { Text("Cette action est irreversible. La tache sera supprimee pour tous les utilisateurs.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(taskId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isEditMode) "Modifier la tache" else "Nouvelle tache",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.isEditMode && taskId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Titre
            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("Titre *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Description
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Categorie
            var categoryExpanded by remember { mutableStateOf(false) }
            val selectedCategory = uiState.categories.find { it.id == uiState.categoryId }

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedCategory?.name ?: "Aucune",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Categorie") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Aucune") },
                        onClick = {
                            viewModel.updateCategoryId(null)
                            categoryExpanded = false
                        }
                    )
                    uiState.categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = {
                                viewModel.updateCategoryId(cat.id)
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            // Assignes
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "Assigne a",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = "Assignation",
                    description = "Selectionne qui doit faire cette tache. Si tous les membres sont coches, c'est une tache commune. Si un seul est coche, c'est une tache personnelle visible uniquement par cette personne."
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.users.forEach { user ->
                    FilterChip(
                        selected = uiState.assignedToIds.contains(user.id),
                        onClick = { viewModel.toggleUserAssignment(user.id) },
                        label = { Text(user.displayName) }
                    )
                }
            }

            // Recurrence — uniquement en mode CREATION. En edition, la frequence
            // se modifie via le vote individuel (modal de notation depuis TaskDetail
            // ou la popup d'accueil). Voir RatingDialog.
            if (!uiState.isEditMode) {
                var recurrenceExpanded by remember { mutableStateOf(false) }
                val recurrenceLabel = when (uiState.recurrenceType) {
                    "none" -> "Ponctuelle (pas de recurrence)"
                    "interval" -> "Tous les X jours"
                    "monthly_variable" -> "Variable par mois"
                    else -> uiState.recurrenceType
                }

                ExposedDropdownMenuBox(
                    expanded = recurrenceExpanded,
                    onExpandedChange = { recurrenceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = recurrenceLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Recurrence") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = recurrenceExpanded,
                        onDismissRequest = { recurrenceExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ponctuelle (pas de recurrence)") },
                            onClick = {
                                viewModel.updateRecurrenceType("none")
                                recurrenceExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tous les X jours") },
                            onClick = {
                                viewModel.updateRecurrenceType("interval")
                                recurrenceExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Variable par mois") },
                            onClick = {
                                viewModel.updateRecurrenceType("monthly_variable")
                                recurrenceExpanded = false
                            }
                        )
                    }
                }

                // Nombre de jours si interval
                if (uiState.recurrenceType == "interval") {
                    OutlinedTextField(
                        value = uiState.recurrenceEveryNDays,
                        onValueChange = { viewModel.updateRecurrenceEveryNDays(it) },
                        label = { Text("Tous les combien de jours ?") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Tableau 12 mois si monthly_variable
                if (uiState.recurrenceType == "monthly_variable") {
                    val monthNames = listOf("Jan", "Fev", "Mar", "Avr", "Mai", "Jun", "Jul", "Aou", "Sep", "Oct", "Nov", "Dec")
                    Text(
                        text = "Jours entre chaque repetition par mois (vide = inactif)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 2 colonnes de 6 mois
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..6).forEach { m ->
                                OutlinedTextField(
                                    value = uiState.daysPerMonth[m.toString()] ?: "",
                                    onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                                    label = { Text(monthNames[m - 1]) },
                                    singleLine = true
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            (7..12).forEach { m ->
                                OutlinedTextField(
                                    value = uiState.daysPerMonth[m.toString()] ?: "",
                                    onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                                    label = { Text(monthNames[m - 1]) },
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }

            // Tolerance au retard (uniquement pour les taches recurrentes)
            if (uiState.recurrenceType != "none") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tolerance au retard",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Tolerance au retard",
                        description = "Strict : la tache devient rouge avant l'echeance. Normal : la tache est jaune a l'echeance. Souple : la tache reste verte meme apres l'echeance."
                    )
                }
                val tolerances = listOf(
                    "STRICT" to "Strict",
                    "NORMAL" to "Normal",
                    "SOUPLE" to "Souple",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tolerances.forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            selected = uiState.tolerance == value,
                            onClick = { viewModel.updateTolerance(value) },
                            shape = SegmentedButtonDefaults.itemShape(idx, tolerances.size),
                        ) { Text(label) }
                    }
                }
            }

            // Effort (temps estime)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val minutes = uiState.weight * 5
                val timeLabel = if (minutes >= 60) "${minutes / 60}h${if (minutes % 60 > 0) "%02d".format(minutes % 60) else ""}" else "${minutes}min"
                Text(
                    text = "Effort : $timeLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = "Effort",
                    description = "Estimation du temps que prend cette tache. Chaque utilisateur pourra donner sa propre estimation, la moyenne sera utilisee pour calculer les points."
                )
            }
            Slider(
                value = uiState.weight.toFloat(),
                onValueChange = { viewModel.updateWeight(it.toInt()) },
                valueRange = 1f..18f,
                steps = 16,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bouton sauvegarder
            Button(
                onClick = { viewModel.save(taskId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.height(24.dp).width(24.dp)
                    )
                } else {
                    Text(
                        text = if (uiState.isEditMode) "Enregistrer" else "Creer la tache",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bouton supprimer en mode edition
            if (uiState.isEditMode && taskId != null) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Supprimer la tache",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
