package com.torchons.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.service.TokenRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Success : LoginUiState()
    data class ChooseUser(val users: List<User>, val ip: String) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    private val tokenRegistrar: TokenRegistrar
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun autoLogin() {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading

            val result = authRepository.autoLogin()

            result.fold(
                onSuccess = { response ->
                    tokenRegistrar.registerFcmToken()
                    _uiState.value = LoginUiState.Success
                },
                onFailure = { error ->
                    // Verifier si c'est un unknown_ip (le repo a parse la reponse)
                    val msg = error.message ?: ""
                    if (msg.startsWith("CHOOSE_USER:")) {
                        // Parse la liste d'users
                        tryLoadUsersForChoice()
                    } else {
                        _uiState.value = LoginUiState.Error(msg)
                    }
                }
            )
        }
    }

    private fun tryLoadUsersForChoice() {
        viewModelScope.launch {
            try {
                val resp = apiService.autoLogin()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val status = (body as? Map<*, *>)?.get("status") as? String
                    if (status == "unknown_ip") {
                        val ip = (body as? Map<*, *>)?.get("ip") as? String ?: "?"
                        @Suppress("UNCHECKED_CAST")
                        val usersRaw = (body as? Map<*, *>)?.get("users") as? List<Map<String, Any>> ?: emptyList()
                        val users = usersRaw.map { u ->
                            User(
                                id = u["id"] as? String ?: "",
                                username = u["username"] as? String ?: "",
                                displayName = u["display_name"] as? String ?: ""
                            )
                        }
                        _uiState.value = LoginUiState.ChooseUser(users, ip)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error("Impossible de charger les utilisateurs")
            }
        }
    }

    fun claimIP(username: String) {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            try {
                val resp = apiService.claimIP(mapOf("username" to username))
                if (resp.isSuccessful) {
                    val data = resp.body()
                    val token = (data as? Map<*, *>)?.get("token") as? String
                    @Suppress("UNCHECKED_CAST")
                    val userMap = (data as? Map<*, *>)?.get("user") as? Map<String, Any>
                    if (token != null && userMap != null) {
                        authRepository.saveAuthDirect(
                            token,
                            userMap["id"] as? String ?: "",
                            userMap["username"] as? String ?: "",
                            userMap["display_name"] as? String ?: ""
                        )
                        tokenRegistrar.registerFcmToken()
                        _uiState.value = LoginUiState.Success
                    }
                } else {
                    _uiState.value = LoginUiState.Error("Erreur lors de l'association")
                }
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(e.message ?: "Erreur")
            }
        }
    }
}
