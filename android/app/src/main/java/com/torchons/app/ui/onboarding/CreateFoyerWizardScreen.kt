package com.torchons.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import kotlinx.coroutines.launch
import com.torchons.app.data.model.CatalogContextView
import com.torchons.app.data.model.CatalogTaskContextView

/**
 * Wizard "Creer un foyer" — 5 etapes (v0.30).
 *
 * @param onSuccess appele quand l'user a termine l'etape 5 (recovery affichee
 *   et "Continuer" cliquee). L'appelant doit naviguer vers MAIN.
 * @param onBack appele si l'user fait back depuis l'etape 1 (sortie du wizard
 *   sans avoir cree le foyer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFoyerWizardScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    /** P4a.2 — true = user déjà connecté ajoutant un foyer : 3 étapes
     *  (Foyer/Pièces/Tâches), pas de Toi ni de Recovery. */
    addFoyerMode: Boolean = false,
    viewModel: CreateFoyerWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // Le defilement est partage par toutes les etapes : sans remise a zero, on
    // arrive sur l'etape suivante a la hauteur ou on avait quitte la
    // precedente — donc sous son titre, parfois au milieu d'une liste.
    val defilement = rememberScrollState()
    val portee = rememberCoroutineScope()
    LaunchedEffect(state.step) { defilement.scrollTo(0) }

    LaunchedEffect(addFoyerMode) { if (addFoyerMode) viewModel.enableAddFoyerMode() }

    // En addFoyer : étapes internes 2..4 affichées 1/3..3/3.
    val totalSteps = if (state.addFoyerMode) 3 else 4
    // Hors addFoyer, l'etape interne 2 n'est plus affichee : 1,3,4,5 se montrent
    // 1,2,3,4. Le decalage vit ici et non dans le ViewModel, pour que la
    // numerotation interne reste stable pour le reste du code.
    val shownStep = if (state.addFoyerMode)
        (state.step - 1).coerceIn(1, totalSteps)
    else (if (state.step == 1) 1 else state.step - 1).coerceIn(1, totalSteps)
    val firstStep = if (state.addFoyerMode) 2 else 1

    LaunchedEffect(state.done) { if (state.done) onSuccess() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_title_format, shownStep, totalSteps)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step <= firstStep) onBack()
                            else viewModel.prevStep()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LinearProgressIndicator(
                progress = { shownStep / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(defilement)
                    .padding(24.dp),
            ) {
                when (state.step) {
                    1 -> StepIdentite(
                        displayName = state.displayName,
                        foyerName = state.foyerName,
                        onDisplayNameChange = viewModel::setDisplayName,
                        onFoyerNameChange = viewModel::setFoyerName,
                        onSuggestFoyerName = viewModel::suggestFoyerName,
                        onNext = viewModel::nextStep,
                    )

                    2 -> StepFoyer(
                        value = state.foyerName,
                        defaultHint = if (state.displayName.isNotBlank())
                            stringResource(R.string.wizard_foyer_default_at_someones_format, state.displayName)
                        else stringResource(R.string.settings_foyer_default_name),
                        onChange = viewModel::setFoyerName,
                        onNext = viewModel::nextStep,
                    )

                    3 -> StepContextes(
                        contexts = state.contexts,
                        selectedKeys = state.selectedContextKeys,
                        customRooms = state.customRooms,
                        catalogStatus = state.catalogStatus,
                        onToggle = viewModel::toggleContext,
                        onAddCustomRoom = viewModel::addCustomRoom,
                        onRemoveCustomRoom = viewModel::removeCustomRoom,
                        onRetry = viewModel::retryCatalog,
                        onNext = viewModel::nextStep,
                    )

                    4 -> StepTaches(
                        contexts = state.contexts,
                        tasks = viewModel.visibleContextTasks(state),
                        displayContext = { viewModel.displayContext(it, state) },
                        selectedKeys = state.selectedTaskKeys,
                        isSubmitting = state.isSubmitting,
                        catalogStatus = state.catalogStatus,
                        onToggle = viewModel::toggleTask,
                        onRetry = viewModel::retryCatalog,
                        onNext = viewModel::nextStep,
                        onPageChange = { portee.launch { defilement.scrollTo(0) } },
                    )

                    5 -> StepRecovery(
                        recoveryCode = state.recoveryCode,
                        onContinue = viewModel::finish,
                    )
                }
            }
        }
    }
}

/**
 * Prenom et nom du foyer sur un seul ecran.
 *
 * Les deux etaient separes, ce qui faisait payer un ecran entier a un champ
 * qu'on peut deviner : « Chez Alice » se derive du prenom qu'on vient de
 * saisir. Le champ est PRE-REMPLI et non seulement suggere en gris — un
 * placeholder oblige encore a taper quelque chose, alors qu'ici la valeur par
 * defaut est bonne pour la plupart des foyers et reste modifiable.
 */
@Composable
private fun StepIdentite(
    displayName: String,
    foyerName: String,
    onDisplayNameChange: (String) -> Unit,
    onFoyerNameChange: (String) -> Unit,
    onSuggestFoyerName: (String) -> Unit,
    onNext: () -> Unit,
) {
    val suggestion = if (displayName.isNotBlank())
        stringResource(R.string.wizard_foyer_default_at_someones_format, displayName)
    else stringResource(R.string.settings_foyer_default_name)
    LaunchedEffect(suggestion) { onSuggestFoyerName(suggestion) }

    Text(
        text = stringResource(R.string.onboarding_your_name_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_your_name_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text(stringResource(R.string.onboarding_your_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.wizard_foyer_name_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = foyerName,
        onValueChange = onFoyerNameChange,
        label = { Text(stringResource(R.string.foyer_name_label)) },
        placeholder = { Text(suggestion) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

/** Nom du foyer seul — ne sert plus qu'au mode « ajouter un foyer », ou le
 *  prenom de l'utilisateur est deja connu. */
@Composable
private fun StepFoyer(
    value: String,
    defaultHint: String,
    onChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.wizard_foyer_name_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_foyer_name_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.foyer_name_label)) },
        placeholder = { Text(defaultHint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepContextes(
    contexts: List<CatalogContextView>,
    selectedKeys: Set<String>,
    customRooms: List<String>,
    catalogStatus: CatalogStatus,
    onToggle: (String) -> Unit,
    onAddCustomRoom: (String) -> Unit,
    onRemoveCustomRoom: (String) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.wizard_contexts_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_contexts_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    when (catalogStatus) {
        CatalogStatus.LOADING -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        CatalogStatus.ERROR -> CatalogErrorBox(onRetry = onRetry)

        // Seuls les contextes SELECTIONNABLES sont proposes : demander « as-tu
        // du menage ? » n'aurait pas de sens, et les implicites servent au
        // filtrage, pas au choix.
        CatalogStatus.OK -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            contexts.filter { it.selectable }.forEach { ctx ->
                FilterChip(
                    selected = ctx.key in selectedKeys,
                    onClick = { onToggle(ctx.key) },
                    label = {
                        Text(
                            text = if (ctx.emoji != null) "${ctx.emoji} ${ctx.name}" else ctx.name,
                        )
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.wizard_contexts_add_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    var newRoom by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newRoom,
            onValueChange = { newRoom = it },
            label = { Text(stringResource(R.string.wizard_contexts_custom_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAddCustomRoom(newRoom)
                newRoom = ""
            },
            enabled = newRoom.isNotBlank(),
        ) { Text(stringResource(R.string.common_add)) }
    }
    if (customRooms.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            customRooms.forEach { name ->
                FilterChip(
                    selected = true,
                    onClick = { onRemoveCustomRoom(name) },
                    label = { Text(stringResource(R.string.wizard_rooms_custom_chip_format, name)) },
                )
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

/** Un paquet de taches affiche ensemble, sous le nom de son contexte. */
private data class SectionTaches(
    val titre: String,
    val taches: List<CatalogTaskContextView>,
)

/**
 * Repartit les sections en sous-etapes, sans jamais couper une section.
 *
 * Une liste a plat de 69 taches est illisible et se termine par un abandon ou
 * un « tout cocher » qui ne veut rien dire. On coupe donc, mais par PAQUETS de
 * contextes et non contexte par contexte : un foyer coche facilement une
 * douzaine de contextes, et douze ecrans a valider coutent plus cher en
 * abandons que le defilement qu'ils evitent.
 */
private fun paginer(sections: List<SectionTaches>, parPage: Int): List<List<SectionTaches>> {
    if (sections.isEmpty()) return listOf(emptyList())
    val pages = mutableListOf<MutableList<SectionTaches>>()
    var courante = mutableListOf<SectionTaches>()
    var compte = 0
    for (section in sections) {
        // Une section plus grosse que le quota tient seule sur sa page : la
        // couper melangerait deux contextes sous un seul titre.
        if (courante.isNotEmpty() && compte + section.taches.size > parPage) {
            pages.add(courante)
            courante = mutableListOf()
            compte = 0
        }
        courante.add(section)
        compte += section.taches.size
    }
    if (courante.isNotEmpty()) pages.add(courante)
    return pages
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepTaches(
    contexts: List<CatalogContextView>,
    tasks: List<CatalogTaskContextView>,
    displayContext: (CatalogTaskContextView) -> String?,
    selectedKeys: Set<String>,
    isSubmitting: Boolean,
    catalogStatus: CatalogStatus,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onPageChange: () -> Unit,
) {
    // Sections dans l'ordre du CATALOGUE, pas dans celui des taches : c'est
    // l'ordre que l'utilisateur vient de voir a l'etape precedente.
    val sections = remember(contexts, tasks) {
        val parContexte = tasks.groupBy(displayContext)
        contexts.mapNotNull { ctx ->
            parContexte[ctx.key]?.let { liste ->
                SectionTaches(
                    titre = if (ctx.emoji != null) "${ctx.emoji} ${ctx.name}" else ctx.name,
                    taches = liste,
                )
            }
        }
    }
    val pages = remember(sections) { paginer(sections, 18) }
    var page by remember(pages) { mutableStateOf(0) }
    val derniere = page >= pages.size - 1

    Text(
        text = stringResource(R.string.wizard_tasks_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (pages.size > 1)
            stringResource(R.string.wizard_tasks_page_format, page + 1, pages.size)
        else stringResource(R.string.wizard_tasks_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    when (catalogStatus) {
        CatalogStatus.LOADING -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        CatalogStatus.ERROR -> CatalogErrorBox(onRetry = onRetry)

        CatalogStatus.OK -> if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.wizard_tasks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            pages.getOrElse(page) { emptyList() }.forEach { section ->
                Text(
                    text = stringResource(
                        R.string.wizard_tasks_section_format,
                        section.titre,
                        section.taches.size,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    section.taches.forEach { task ->
                        FilterChip(
                            selected = task.key in selectedKeys,
                            onClick = { onToggle(task.key) },
                            label = { Text(task.title) },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { if (derniere) onNext() else { page++; onPageChange() } },
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wizard_tasks_creating))
        } else {
            Text(
                stringResource(
                    if (derniere) R.string.wizard_tasks_submit
                    else R.string.wizard_tasks_next_page
                )
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onNext,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

/** Encart d'erreur reseau partage (steps Pieces/Taches) : message clair +
 *  bouton Reessayer. L'user peut aussi continuer sans (skip) ou ajouter des
 *  pieces custom — pas de spinner infini. */
@Composable
private fun CatalogErrorBox(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.wizard_catalog_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.wizard_retry)) }
        }
    }
}

@Composable
private fun StepRecovery(
    recoveryCode: String?,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.wizard_recovery_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_recovery_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = recoveryCode ?: stringResource(R.string.wizard_recovery_placeholder),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.recovery_warning_dont_share_long),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }

    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                recoveryCode?.let { copyToClipboard(context, it) }
            },
            modifier = Modifier.weight(1f),
            enabled = recoveryCode != null,
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_dialog_copy))
        }
        OutlinedButton(
            onClick = {
                recoveryCode?.let { shareCode(context, it) }
            },
            modifier = Modifier.weight(1f),
            enabled = recoveryCode != null,
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_dialog_share))
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        enabled = recoveryCode != null,
    ) { Text(stringResource(R.string.wizard_recovery_continue)) }
}

private fun copyToClipboard(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    cm.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.recovery_code_clipboard_label), code),
    )
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.recovery_share_subject))
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(R.string.recovery_share_body_format, code),
        )
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.recovery_share_chooser)),
    )
}
