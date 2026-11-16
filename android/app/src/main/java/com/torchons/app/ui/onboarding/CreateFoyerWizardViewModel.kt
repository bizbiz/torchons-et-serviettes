package com.torchons.app.ui.onboarding

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.CatalogContextView
import com.torchons.app.data.model.CatalogTaskContextView
import com.torchons.app.data.model.ImportApplyRequest
import com.torchons.app.data.model.ImportDecision
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.model.ImportResolveRequest
import com.torchons.app.data.model.OnboardingCreateFoyerRequest
import com.torchons.app.data.model.ContextCreateRequest
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.service.TokenRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel du wizard "Creer un foyer" (v0.30).
 *
 * Flow 5 etapes :
 *  1. Toi    : display_name
 *  2. Foyer  : foyer_name
 *  3. Pieces : multi-select (skip OK)
 *  4. Taches : multi-select filtre par pieces (skip OK)
 *  5. Recov  : code de recuperation affiche + Copier/Partager (pas skip)
 *
 * Les etapes 1-4 sont remplies cote client sans API. La submission a la
 * transition etape 4 -> 5 declenche en sequence :
 *  a. POST /api/onboarding/create-foyer  -> JWT + recovery_code
 *  b. POST /api/catalog/import/resolve   (si pieces ou taches selectionnees)
 *  c. POST /api/catalog/import/apply     (toutes decisions = "create")
 */

/** Etat du chargement du catalogue (pieces/taches proposees). */
enum class CatalogStatus { LOADING, OK, ERROR }

data class WizardState(
    val step: Int = 1,  // 1..5 inclus
    val displayName: String = "",
    val foyerName: String = "",
    /** L'utilisateur a-t-il ecrit lui-meme le nom du foyer ? Tant que non, la
     *  suggestion derivee du prenom continue de le remplacer a chaque frappe ;
     *  des qu'il y touche, on ne l'ecrase plus jamais. */
    val foyerNameTouched: Boolean = false,
    /** Catalogue sous sa forme contextes — ce que l'ecran utilise desormais. */
    val contexts: List<CatalogContextView> = emptyList(),
    val selectedContextKeys: Set<String> = emptySet(),
    val contextTasks: List<CatalogTaskContextView> = emptyList(),
    // Pieces saisies librement par l'user (hors catalogue), creees via l'API
    // rooms a la finalisation du wizard.
    val customRooms: List<String> = emptyList(),
    val selectedTaskKeys: Set<String> = emptySet(),
    /** Taches cochees a la main alors qu'elles ne l'etaient pas par defaut. */
    val taskOptIn: Set<String> = emptySet(),
    /** Taches decochees a la main alors qu'elles l'etaient par defaut. */
    val taskOptOut: Set<String> = emptySet(),
    val catalogStatus: CatalogStatus = CatalogStatus.LOADING,
    val recoveryCode: String? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    // P4a.2 — variante "ajouter un foyer" (user déjà connecté) : on saute
    // l'étape Toi (prénom) et l'étape Recovery (code compte déjà existant),
    // et on crée via POST /foyers + switch au lieu de l'onboarding-bootstrap.
    val addFoyerMode: Boolean = false,
    /** Step 3 was skipped: a catalog (re)load must not re-check the defaults. */
    val contextsSkipped: Boolean = false,
)

@HiltViewModel
class CreateFoyerWizardViewModel @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val tokenRegistrar: TokenRegistrar,
    private val taskRepository: TaskRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Name pre-filled for the first member when the field is left blank (localized).
    private val defaultDisplayName: String
        get() = context.getString(R.string.projection_card_self_title)

    private val _uiState = MutableStateFlow(WizardState())
    val uiState: StateFlow<WizardState> = _uiState.asStateFlow()

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(catalogStatus = CatalogStatus.LOADING) }
            try {
                val resp = apiService.getCatalogContexts()
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    // Le pre-cochage vient du catalogue et non d'une liste en
                    // dur : c'est le serveur qui sait ce que tout foyer a.
                    val defauts = body.contexts
                        .filter { c -> c.selectable && c.defaultSelected }
                        .map { c -> c.key }
                        .toSet()
                    _uiState.update {
                        it.copy(
                            contexts = body.contexts,
                            contextTasks = body.tasks,
                            selectedContextKeys =
                                if (it.selectedContextKeys.isEmpty() && !it.contextsSkipped)
                                    defauts else it.selectedContextKeys,
                            catalogStatus = CatalogStatus.OK,
                        ).let { charge -> recalculerSelection(charge) }
                    }
                } else {
                    _uiState.update { it.copy(catalogStatus = CatalogStatus.ERROR) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(catalogStatus = CatalogStatus.ERROR) }
            }
        }
    }

    /** Re-tente le chargement du catalogue (bouton "Reessayer" en cas
     *  d'echec reseau). */
    fun retryCatalog() = loadCatalog()

    fun setDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun setFoyerName(value: String) {
        _uiState.update { it.copy(foyerName = value, foyerNameTouched = true) }
    }

    /**
     * Propose un nom de foyer derive du prenom, sans jamais ecraser une saisie.
     *
     * La chaine arrive de l'ecran et non du ViewModel : « Chez Alice » est du
     * texte vu par l'utilisateur, donc il vit dans strings.xml et se traduit.
     */
    fun suggestFoyerName(value: String) {
        _uiState.update { if (it.foyerNameTouched) it else it.copy(foyerName = value) }
    }

    /** Ajoute une piece custom (texte libre). Trim + dedup insensible casse. */
    fun addCustomRoom(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.update {
            if (it.customRooms.any { r -> r.equals(trimmed, ignoreCase = true) }) it
            else it.copy(customRooms = it.customRooms + trimmed)
        }
    }

    fun removeCustomRoom(name: String) {
        _uiState.update {
            it.copy(customRooms = it.customRooms.filterNot { r -> r == name })
        }
    }

    fun toggleTask(key: String) {
        _uiState.update {
            val coche = key in it.selectedTaskKeys
            val optIn = it.taskOptIn.toMutableSet()
            val optOut = it.taskOptOut.toMutableSet()
            if (coche) { optIn.remove(key); optOut.add(key) }
            else { optOut.remove(key); optIn.add(key) }
            recalculerSelection(it.copy(taskOptIn = optIn, taskOptOut = optOut))
        }
    }

    /**
     * Recalcule les taches cochees a partir des contextes et des ecarts.
     *
     * La selection est DERIVEE et non stockee : elle doit suivre les contextes.
     * Le pre-cochage s'appliquait auparavant a tout le catalogue, y compris aux
     * taches d'un contexte non coche — un foyer sans couloir heritait des
     * plinthes du couloir, invisibles dans le wizard et pourtant creees.
     */
    private fun recalculerSelection(state: WizardState): WizardState = recomputeSelection(state)

    /**
     * Taches a afficher en step 4 : on garde celles dont la `default_room_key`
     * matche une piece selectionnee, ou celles sans piece (ex: "courses").
     */
    fun toggleContext(key: String) {
        _uiState.update {
            val s = it.selectedContextKeys.toMutableSet()
            if (!s.add(key)) s.remove(key)
            recalculerSelection(it.copy(selectedContextKeys = s))
        }
    }

    /**
     * Taches visibles : une tache apparait si l'un de ses contextes
     * SELECTIONNABLES est coche.
     *
     * Les implicites (menage, linge, entretien) ne rendent rien visible a eux
     * seuls — sinon toute tache portant `menage` s'afficherait quoi qu'il
     * arrive, ce qui reproduirait le defaut corrige par ce chantier : 41 % du
     * catalogue etait affiche sans condition. Meme regle que `Catalog.tasks_for`
     * cote serveur.
     */
    fun visibleContextTasks(state: WizardState = _uiState.value): List<CatalogTaskContextView> =
        visibleTasksOf(state)

    /**
     * Sous quel contexte afficher cette tache : le premier SELECTIONNE, pas le
     * premier declare. Une tache rattachee a [chat, chien] doit apparaitre
     * sous Chien chez quelqu'un qui n'a qu'un chien.
     */
    fun displayContext(task: CatalogTaskContextView, state: WizardState = _uiState.value): String? =
        task.contexts.firstOrNull { it in state.selectedContextKeys }
            ?: task.contexts.firstOrNull()

    /** P4a.2 — entrée "ajouter un foyer" (user connecté) : saute l'étape
     *  Toi (prénom déjà connu). Appelé une fois par l'écran. */
    fun enableAddFoyerMode() {
        _uiState.update {
            if (it.addFoyerMode) it
            else it.copy(
                addFoyerMode = true,
                step = if (it.step < 2) 2 else it.step,
            )
        }
    }

    fun nextStep() {
        val cur = _uiState.value
        // addFoyer : pas d'étape 5 (Recovery). L'étape 4 "Suivant" déclenche
        // le submit, qui passe `done` à true (cf submit()).
        if (cur.addFoyerMode && cur.step == 4) {
            submit()
            return
        }
        _uiState.update {
            val s = it.step
            // Step 1 : default value si vide
            val newDisplay =
                if (s == 1 && it.displayName.isBlank()) defaultDisplayName else it.displayName
            val newFoyer = if ((s == 1 || s == 2) && it.foyerName.isBlank())
                if (newDisplay.isNotBlank()) {
                    context.getString(R.string.onboarding_default_foyer_name_format, newDisplay)
                } else {
                    context.getString(R.string.settings_foyer_default_name)
                }
            else it.foyerName
            it.copy(
                // L'etape 2 (nom du foyer seul) ne subsiste que pour le mode
                // « ajouter un foyer », ou le prenom est deja connu.
                step = (if (s == 1) 3 else s + 1).coerceAtMost(5),
                displayName = newDisplay,
                foyerName = newFoyer,
            )
        }
        // Step 4 -> 5 declenche le submit (flow onboarding normal)
        if (!_uiState.value.addFoyerMode &&
            _uiState.value.step == 5 && _uiState.value.recoveryCode == null) {
            _uiState.update { it.copy(step = 4) }  // re-place sur 4
            submit()
        }
    }

    /**
     * "Skip this step" on steps 3 and 4: nothing from the step is kept (no
     * context and no custom room on 3, no task on 4), then advance like
     * [nextStep] (which submits on step 4). Other steps just advance.
     */
    fun skipStep() {
        _uiState.update {
            when (it.step) {
                3 -> withContextsSkipped(it)
                4 -> withTasksSkipped(it)
                else -> it
            }
        }
        nextStep()
    }

    fun prevStep() {
        _uiState.update {
            val cible = if (it.step == 3 && !it.addFoyerMode) 1 else it.step - 1
            it.copy(step = cible.coerceAtLeast(1))
        }
    }

    fun finish() {
        _uiState.update { it.copy(done = true) }
    }

    /**
     * P4a.2 — création d'un foyer SUPPLÉMENTAIRE (user déjà connecté) :
     * POST /foyers + switch (pas d'onboarding-bootstrap, pas de recovery
     * code). Pièces/tâches importées APRÈS le switch -> ciblent le
     * nouveau foyer. `done=true` à la fin (pas d'étape 5).
     */
    private suspend fun submitAddFoyer(s: WizardState) {
        val createResp = apiService.createFoyer(
            FoyerCreateRequest(s.foyerName.ifBlank { context.getString(R.string.settings_foyer_default_name) }),
        )
        val created = createResp.body()
        if (!createResp.isSuccessful || created == null) {
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    error = context.getString(R.string.onboarding_error_create_foyer_format, createResp.code()),
                )
            }
            return
        }
        val switched = authRepository.switchFoyer(created.id)
        if (switched.isFailure) {
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    error = context.getString(R.string.onboarding_error_switch_foyer),
                )
            }
            return
        }
        // Contextes libres saisis a l'etape 2 — best-effort : le foyer est
        // cree de toute facon, un contexte manquant se rajoute apres coup.
        if (s.customRooms.isNotEmpty()) {
            runCatching {
                for (name in s.customRooms) {
                    apiService.createContext(ContextCreateRequest(name = name))
                }
            }
        }
        // Import catalogue dans le NOUVEAU foyer (JWT déjà switché). On NE
        // swallow PLUS en silence : si l'import échoue, l'user (et le dev)
        // doit voir POURQUOI — sinon foyer vide = mystère (bug dogfood).
        if (s.selectedContextKeys.isNotEmpty() || s.selectedTaskKeys.isNotEmpty()) {
            try {
                val resolveResp = apiService.resolveCatalogImport(
                    ImportResolveRequest(
                        taskKeys = s.selectedTaskKeys.toList(),
                        contextKeys = s.selectedContextKeys.toList(),
                    )
                )
                val resolved = resolveResp.body()
                if (!resolveResp.isSuccessful || resolved == null) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = context.getString(
                                R.string.onboarding_error_import_resolve_format,
                                resolveResp.code(),
                            ),
                        )
                    }
                    return
                }
                val contextDecisions = resolved.contexts.map {
                    ImportDecision(it.templateKey, "create", null)
                }
                val applyResp = apiService.applyCatalogImport(
                    ImportApplyRequest(
                        taskKeys = s.selectedTaskKeys.toList(),
                        contextKeys = s.selectedContextKeys.toList(),
                        contextDecisions = contextDecisions,
                    )
                )
                if (!applyResp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = context.getString(R.string.onboarding_error_import_apply_format, applyResp.code()),
                        )
                    }
                    return
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = context.getString(R.string.onboarding_error_import_failed_format, e.message ?: ""),
                    )
                }
                return
            }
        }
        // Bascule effectuée : wiper le cache Room local (sinon l'UI montre
        // les données de l'ANCIEN foyer — cf switchFoyer docstring) puis
        // re-fetcher le nouveau foyer (tâches/pièces importées incluses).
        taskRepository.wipeLocalCache()
        runCatching {
            taskRepository.refreshTasks()
            taskRepository.refreshContexts()
            taskRepository.refreshUsers()
            taskRepository.refreshCompletions(limit = 2000)
        }
        _uiState.update { it.copy(isSubmitting = false, done = true) }
    }

    /**
     * Submit : appelle create-foyer puis (optionnel) catalog/import.
     * Sur succes, place step = 5 avec le recovery_code en state.
     */
    private fun submit() {
        val s = _uiState.value
        if (s.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            try {
                if (s.addFoyerMode) {
                    submitAddFoyer(s)
                    return@launch
                }
                val deviceId = tokenManager.getOrCreateDeviceId()
                val createResp = apiService.onboardingCreateFoyer(
                    OnboardingCreateFoyerRequest(
                        displayName = s.displayName.ifBlank { defaultDisplayName },
                        foyerName = s.foyerName.ifBlank { context.getString(R.string.settings_foyer_default_name) },
                        deviceId = deviceId,
                    )
                )
                if (!createResp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = context.getString(R.string.onboarding_error_create_foyer_format, createResp.code()),
                        )
                    }
                    return@launch
                }
                val createBody = createResp.body()
                if (createBody == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = context.getString(R.string.common_empty_response))
                    }
                    return@launch
                }

                // Sauvegarde JWT + identite
                authRepository.saveAuthDirect(
                    token = createBody.token,
                    userId = createBody.userId,
                    username = "",  // plus utilise (cf. design doc)
                    displayName = s.displayName.ifBlank { defaultDisplayName },
                    foyerId = createBody.foyerId,
                )
                // Push token FCM (best-effort silent)
                tokenRegistrar.registerFcmToken()

                // Pieces custom (texte libre) creees via l'API rooms apres
                // creation du foyer (JWT en place). Best-effort : si une
                // echoue, le foyer existe deja, l'user pourra reessayer
                // depuis Reglages > Pieces.
                if (s.customRooms.isNotEmpty()) {
                    runCatching {
                        for (name in s.customRooms) {
                            apiService.createContext(ContextCreateRequest(name = name))
                        }
                    }
                }

                // Import catalogue si pieces ou taches selectionnees
                if (s.selectedContextKeys.isNotEmpty() || s.selectedTaskKeys.isNotEmpty()) {
                    runCatching {
                        val resolveResp = apiService.resolveCatalogImport(
                            ImportResolveRequest(
                                taskKeys = s.selectedTaskKeys.toList(),
                                contextKeys = s.selectedContextKeys.toList(),
                            )
                        )
                        val resolved = resolveResp.body()
                        if (resolveResp.isSuccessful && resolved != null) {
                            val contextDecisions = resolved.contexts.map {
                                ImportDecision(it.templateKey, "create", null)
                            }
                            apiService.applyCatalogImport(
                                ImportApplyRequest(
                                    taskKeys = s.selectedTaskKeys.toList(),
                                    contextKeys = s.selectedContextKeys.toList(),
                                    contextDecisions = contextDecisions,
                                )
                            )
                        }
                    }
                    // Si l'import echoue, le foyer existe quand meme — l'user
                    // pourra importer manuellement plus tard depuis Reglages.
                }

                _uiState.update {
                    it.copy(
                        step = 5,
                        recoveryCode = createBody.recoveryCode,
                        isSubmitting = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = e.message ?: context.getString(R.string.common_error_network),
                    )
                }
            }
        }
    }
}
