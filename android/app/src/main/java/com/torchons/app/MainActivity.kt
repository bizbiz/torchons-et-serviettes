package com.torchons.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import com.torchons.app.data.billing.BillingService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.service.TokenRegistrar
import com.torchons.app.ui.navigation.NavGraph
import com.torchons.app.ui.navigation.Routes
import com.torchons.app.ui.theme.TorchonsServiettesTheme
import com.torchons.app.util.PopupSessionGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    @Inject
    lateinit var tokenRegistrar: TokenRegistrar

    @Inject
    lateinit var billingService: BillingService

    // Note : le PresenceTrigger (worker periodique 6h) a ete supprime car
    // les restrictions Android 12+ (Doze, background limits) empechaient
    // son execution fiable. La detection de presence passe desormais par
    // un simple checkin WiFi a l'ouverture de l'app (HomeViewModel).

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* reponse silencieuse : si refuse, pas de notifs. */ }

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

        // Android 13+ (API 33) : POST_NOTIFICATIONS devient runtime. Declaree dans
        // le manifest mais silencieusement refusee tant qu'on demande pas, ce qui
        // empeche toute notif (DailyNotificationWorker, FCM) de s'afficher.
        // Pas de rationale : la demande est suffisante, le toggle dans Reglages
        // continue de piloter le comportement metier.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Lire le token de maniere synchrone au demarrage
        // pour eviter un flash de l'ecran de login
        val initialToken = runBlocking {
            tokenManager.tokenFlow.first()
        }

        // v0.28.1 — Sync FCM token au cold start si user deja loggue.
        // Avant, registerFcmToken() etait uniquement appele depuis
        // LoginViewModel.autoLogin(), donc un user qui a un JWT sauvegarde
        // depuis longtemps ne re-poussait jamais son token. Resultat :
        // pas de notif. L'appel ici est idempotent cote backend.
        if (!initialToken.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                tokenRegistrar.registerFcmToken()
            }
        }

        // v0.30.x — Plomberie Play Billing : init au cold start, restoration
        // silencieuse des achats existants si JWT present. Aucun paywall
        // actif a ce stade (cf. roadmap-dev §C).
        billingService.start()

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

    // Si un token est present, demarrer sur la liste des taches.
    // Sinon, demarrer sur l'ecran de bienvenue (v0.30) qui tente
    // l'auto-login Tailscale en arriere-plan puis offre Creer/Rejoindre/
    // Retrouver si echec.
    val startDestination = if (initialToken.isNullOrEmpty()) {
        Routes.WELCOME
    } else {
        Routes.MAIN
    }

    NavGraph(
        navController = navController,
        startDestination = startDestination
    )
}
