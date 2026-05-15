package com.torchons.app.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.BuildConfig
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.service.TokenRegistrar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Etat du WelcomeScreen (v0.30) — point d'entree de l'app sans compte.
 *
 * - `Idle` : affichage initial avant la tentative auto-login (splash).
 * - `Probing` : tentative en cours de l'auto-login Tailscale (fast-path
 *   pour les deux membres sur le reseau maison pendant la phase dogfood).
 * - `AutoLoginSuccess` : Tailscale a matche -> on bascule directement sur
 *   l'ecran principal sans afficher les 3 boutons.
 * - `NoSession` : ni token, ni Tailscale match -> affiche les 3 boutons
 *   Creer / Rejoindre / Retrouver.
 *
 * Note : on n'expose pas les usernames "ChooseUser" du LoginScreen : le
 * choix d'identite passe maintenant par le wizard ou le recovery code.
 * LoginScreen est conserve uniquement comme fallback technique pour les
 * builds dogfood (cf. design-onboarding-public.md §6).
 */
sealed class WelcomeUiState {
    data object Idle : WelcomeUiState()
    data object Probing : WelcomeUiState()
    data object AutoLoginSuccess : WelcomeUiState()
    data object NoSession : WelcomeUiState()
}

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenRegistrar: TokenRegistrar,
) : ViewModel() {

    private val _uiState = MutableStateFlow<WelcomeUiState>(WelcomeUiState.Idle)
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    /**
     * Tente l'auto-login Tailscale en arriere-plan. Si succes -> Success
     * (le composable navigue sur Main). Sinon -> NoSession (les 3 boutons
     * d'onboarding s'affichent).
     */
    fun probe() {
        // Build public (flavor prod) : pas d'auto-login Tailscale. On affiche
        // directement les 3 entrees d'onboarding. Le flavor beta (dogfood)
        // garde le fast-path Tailscale. Regle aussi l'item roadmap Phase D
        // « drop Tailscale auto-login en build public ».
        if (!BuildConfig.TAILSCALE_AUTOLOGIN) {
            _uiState.value = WelcomeUiState.NoSession
            return
        }
        viewModelScope.launch {
            _uiState.value = WelcomeUiState.Probing
            val result = authRepository.autoLogin()
            result.fold(
                onSuccess = {
                    tokenRegistrar.registerFcmToken()
                    _uiState.value = WelcomeUiState.AutoLoginSuccess
                },
                onFailure = {
                    // Pas de Tailscale-match : on offre les 3 entrees
                    // d'onboarding sans tenter de derouler l'ancien
                    // ChooseUser flow (cf. KDoc).
                    _uiState.value = WelcomeUiState.NoSession
                },
            )
        }
    }
}
