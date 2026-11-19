package com.torchons.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feeds the « Pendant une absence » sheet.
 *
 * Tasks come from the Room cache, the same `observeActiveTasks()` flow as the Tasks tab
 * (active, not archived), so the split works offline. The household freeze switch is read
 * once from `/api/foyers` (current household); it stays null until that answers, and the
 * sheet then falls back on what its caller already knows or on "enabled".
 */
@HiltViewModel
class AbsenceSheetViewModel @Inject constructor(
    taskRepository: TaskRepository,
    private val apiService: ApiService,
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = taskRepository.observeActiveTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _freezeEnabled = MutableStateFlow<Boolean?>(null)
    val freezeEnabled: StateFlow<Boolean?> = _freezeEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val resp = apiService.getMyFoyers()
                if (resp.isSuccessful) {
                    _freezeEnabled.value = resp.body()?.find { it.isCurrent }?.homeFreezeEnabled
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the sheet keeps its fallback.
            }
        }
    }
}
