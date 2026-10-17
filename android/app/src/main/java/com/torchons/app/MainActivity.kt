package com.torchons.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.torchons.app.core.network.VersionGateMonitor
import com.torchons.app.data.billing.BillingService
import com.torchons.app.ui.gate.AppUpdateRequiredScreen
import com.torchons.app.data.local.TokenManager
import com.torchons.app.service.TokenRegistrar
import com.torchons.app.ui.navigation.NavGraph
import com.torchons.app.ui.navigation.Routes
import com.torchons.app.ui.theme.TorchonsServiettesTheme
import com.torchons.app.util.PopupSessionGate
import com.torchons.app.widget.TaskWidget
import androidx.glance.appwidget.updateAll
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

    @Inject
    lateinit var versionGateMonitor: VersionGateMonitor

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
            // Leaving the app refreshes the home-screen widget: without it, the widget
            // waited for its 30-minute period (often deferred by Android) and kept
            // showing tasks just done, or none at all.
            if (event == Lifecycle.Event.ON_STOP) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { TaskWidget().updateAll(applicationContext) }
                }
            }
        })

        // POST_NOTIFICATIONS n'est PLUS demandee ici. Elle l'etait a chaque
        // cold start, donc au tout premier lancement l'ecran de bienvenue
        // s'ouvrait derriere une popup systeme : on faisait trancher un canal
        // de rappels a quelqu'un qui ignorait encore ce que fait l'app.
        // La demande vit desormais dans Reglages > Rappels, au moment ou
        // l'utilisateur active reellement une notification
        // (SettingsScreen.ensureNotificationPermission).

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
                // Le version gate court-circuite TOUTE l'app, navigation
                // comprise : un ecran qu'on peut contourner par un retour
                // arriere ne bloque rien. Tant que `blocked` est null (cas
                // normal, et cas hors ligne tant qu'aucun 426 n'a ete recu),
                // l'app se comporte exactement comme avant.
                val blocked by versionGateMonitor.blocked.collectAsStateWithLifecycle()
                val gate = blocked
                if (gate != null) {
                    AppUpdateRequiredScreen(blocked = gate)
                } else {
                    TorchonsApp(initialToken = initialToken)
                }
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
