package com.torchons.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.torchons.app.data.local.TokenManager
import com.torchons.app.ui.navigation.NavGraph
import com.torchons.app.ui.navigation.Routes
import com.torchons.app.ui.theme.TorchonsServiettesTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
