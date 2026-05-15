package com.torchons.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.CatalogRoomView
import com.torchons.app.data.model.CatalogTaskView
import com.torchons.app.data.model.ImportApplyRequest
import com.torchons.app.data.model.ImportDecision
import com.torchons.app.data.model.ImportResolveRequest
import com.torchons.app.data.model.OnboardingCreateFoyerRequest
import com.torchons.app.data.model.RoomCreateRequest
import com.torchons.app.data.repository.AuthRepository
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
 *
 * Cf. docs/design-onboarding-public.md §4 et le sous-§ "Onboarding (creation
 * de foyer wizard)" du DTOs.
 */

data class WizardState(
    val step: Int = 1,  // 1..5 inclus
    val displayName: String = "",
    val foyerName: String = "",
    val rooms: List<CatalogRoomView> = emptyList(),
    val selectedRoomKeys: Set<String> = emptySet(),
    // Pieces saisies librement par l'user (hors catalogue), creees via l'API
    // rooms a la finalisation du wizard.
    val customRooms: List<String> = emptyList(),
    val tasks: List<CatalogTaskView> = emptyList(),
    val selectedTaskKeys: Set<String> = emptySet(),
    val recoveryCode: String? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class CreateFoyerWizardViewModel @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val tokenRegistrar: TokenRegistrar,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WizardState())
    val uiState: StateFlow<WizardState> = _uiState.asStateFlow()

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            try {
                val resp = apiService.getCatalog()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) {
                        _uiState.update {
                            it.copy(rooms = body.rooms, tasks = body.tasks)
                        }
                    }
                }
                // En echec on n'affiche pas d'erreur : les etapes pieces/taches
                // resteront vides, l'user peut skip.
            } catch (_: Exception) {
                // Idem, silently best-effort.
            }
        }
    }

    fun setDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun setFoyerName(value: String) {
        _uiState.update { it.copy(foyerName = value) }
    }

    fun toggleRoom(key: String) {
        _uiState.update {
            val s = it.selectedRoomKeys.toMutableSet()
            if (key in s) s.remove(key) else s.add(key)
            it.copy(selectedRoomKeys = s)
        }
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
            val s = it.selectedTaskKeys.toMutableSet()
            if (key in s) s.remove(key) else s.add(key)
            it.copy(selectedTaskKeys = s)
        }
    }

    /**
     * Taches a afficher en step 4 : on garde celles dont la `default_room_key`
     * matche une piece selectionnee, ou celles sans piece (ex: "courses").
     */
    fun visibleTasks(state: WizardState = _uiState.value): List<CatalogTaskView> {
        val selected = state.selectedRoomKeys
        if (selected.isEmpty()) {
            // Si aucune piece selectionnee, on affiche les taches sans piece
            // (taches generiques type "courses"). Le skip step pieces n'a
            // pas besoin de cacher les taches generales.
            return state.tasks.filter { it.defaultRoomKey == null }
        }
        return state.tasks.filter {
            it.defaultRoomKey == null || it.defaultRoomKey in selected
        }
    }

    fun nextStep() {
        _uiState.update {
            val s = it.step
            // Step 1 : default value si vide
            val newDisplay = if (s == 1 && it.displayName.isBlank()) "Moi" else it.displayName
            val newFoyer = if (s == 2 && it.foyerName.isBlank())
                if (newDisplay.isNotBlank()) "Chez $newDisplay" else "Mon foyer"
            else it.foyerName
            it.copy(
                step = (s + 1).coerceAtMost(5),
                displayName = newDisplay,
                foyerName = newFoyer,
            )
        }
        // Step 4 -> 5 declenche le submit
        if (_uiState.value.step == 5 && _uiState.value.recoveryCode == null) {
            // On vient de passer a 5 sans code -> il faut le recuperer
            _uiState.update { it.copy(step = 4) }  // re-place sur 4
            submit()
        }
    }

    fun prevStep() {
        _uiState.update {
            it.copy(step = (it.step - 1).coerceAtLeast(1))
        }
    }

    fun finish() {
        _uiState.update { it.copy(done = true) }
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
                val deviceId = tokenManager.getOrCreateDeviceId()
                val createResp = apiService.onboardingCreateFoyer(
                    OnboardingCreateFoyerRequest(
                        displayName = s.displayName.ifBlank { "Moi" },
                        foyerName = s.foyerName.ifBlank { "Mon foyer" },
                        deviceId = deviceId,
                    )
                )
                if (!createResp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = "Erreur creation foyer (${createResp.code()})",
                        )
                    }
                    return@launch
                }
                val createBody = createResp.body()
                if (createBody == null) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = "Reponse vide")
                    }
                    return@launch
                }

                // Sauvegarde JWT + identite
                authRepository.saveAuthDirect(
                    token = createBody.token,
                    userId = createBody.userId,
                    username = "",  // plus utilise (cf. design doc)
                    displayName = s.displayName.ifBlank { "Moi" },
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
                            apiService.createRoom(RoomCreateRequest(name = name))
                        }
                    }
                }

                // Import catalogue si pieces ou taches selectionnees
                if (s.selectedRoomKeys.isNotEmpty() || s.selectedTaskKeys.isNotEmpty()) {
                    runCatching {
                        val resolveResp = apiService.resolveCatalogImport(
                            ImportResolveRequest(
                                taskKeys = s.selectedTaskKeys.toList(),
                                roomKeys = s.selectedRoomKeys.toList(),
                                categoryKeys = emptyList(),
                            )
                        )
                        val resolved = resolveResp.body()
                        if (resolveResp.isSuccessful && resolved != null) {
                            val roomDecisions = resolved.rooms.map {
                                ImportDecision(it.templateKey, "create", null)
                            }
                            val catDecisions = resolved.categories.map {
                                ImportDecision(it.templateKey, "create", null)
                            }
                            apiService.applyCatalogImport(
                                ImportApplyRequest(
                                    taskKeys = s.selectedTaskKeys.toList(),
                                    roomDecisions = roomDecisions,
                                    categoryDecisions = catDecisions,
                                    roomKeys = s.selectedRoomKeys.toList(),
                                    categoryKeys = emptyList(),
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
                        error = e.message ?: "Erreur reseau",
                    )
                }
            }
        }
    }
}
