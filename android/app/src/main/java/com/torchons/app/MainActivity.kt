package com.torchons.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import com.torchons.app.data.local.TokenManager
import com.torchons.app.ui.navigation.NavGraph
import com.torchons.app.ui.navigation.Routes
import com.torchons.app.ui.theme.TorchonsServiettesTheme
import com.torchons.app.util.PopupSessionGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Activite principale de l'application.
 * Gere la navigation entre les ecrans et determine le point de depart
 * en fonction de l'etat d'authentification.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var popupSessionGate: PopupSessionGate

    // Note : le PresenceTrigger (worker periodique 6h) a ete supprime car
    // les restrictions Android 12+ (Doze, background limits) empechaient
    // son execution fiable. La detection de presence passe desormais par
    // un simple checkin WiFi a l'ouverture de l'app (HomeViewModel).

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Rearmer la popup d'accueil a chaque ON_RESUME de l'activite :
        // cold start + retour depuis le background. La navigation Compose
        // inter-ecrans ne passe pas par ON_RESUME, donc la popup ne se
        // reaffiche pas a chaque visite de Home.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                popupSessionGate.arm()
            }
        })

        // Lire le token de maniere synchrone au demarrage
        // pour eviter un flash de l'ecran de login
        val initialToken = runBlocking {
            tokenManager.tokenFlow.first()
        }

        setContent {
            TorchonsServiettesTheme {
                TorchonsApp(initialToken = initialToken)
            }
        }
    }
}

/**
 * Composable racine de l'application.
 * Determine la destination de depart en fonction de la presence du token JWT.
 */
@Composable
private fun TorchonsApp(initialToken: String?) {
    val navController = rememberNavController()

    // Si un token est present, demarrer sur la liste des taches
    // Sinon, demarrer sur l'ecran de connexion
    val startDestination = if (initialToken.isNullOrEmpty()) {
        Routes.LOGIN
    } else {
        Routes.MAIN
    }

    NavGraph(
        navController = navController,
        startDestination = startDestination
    )
}
