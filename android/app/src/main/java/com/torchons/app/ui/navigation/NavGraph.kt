package com.torchons.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.torchons.app.ui.devices.DevicesScreen
import com.torchons.app.ui.changelog.ChangelogScreen
import com.torchons.app.ui.home.HomeScreen
import com.torchons.app.ui.settings.ContextsScreen
import com.torchons.app.ui.settings.FoyerSettingsScreen
import com.torchons.app.ui.settings.SettingsScreen
import com.torchons.app.ui.stats.StatsScreen
import com.torchons.app.ui.taskdetail.TaskDetailScreen
import com.torchons.app.ui.taskedit.TaskEditScreen
import com.torchons.app.ui.tasklist.TaskListScreen
import com.torchons.app.ui.welcome.WelcomeScreen
import com.torchons.app.ui.onboarding.CreateFoyerWizardScreen
import com.torchons.app.ui.onboarding.InviteQrScreen
import com.torchons.app.ui.onboarding.JoinFoyerScreen
import com.torchons.app.ui.onboarding.OnboardingStubScreen
import com.torchons.app.ui.onboarding.RestoreFoyerScreen
import androidx.navigation.navArgument

/**
 * Routes de navigation de l'application.
 */
object Routes {
    const val MAIN = "main"
    const val HOME = "home"
    const val TASK_LIST = "task_list"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task_detail/{taskId}"
    const val TASK_CREATE = "task_create"
    const val TASK_EDIT = "task_edit/{taskId}"
    const val TASK_CLONE = "task_clone/{cloneFromId}"
    const val CONTEXTS = "contexts"
    // v0.39 — liste des appareils connectes + revocation (docs/appareils.fr.md).
    const val MY_DEVICES = "my_devices"
    // P1 refonte : ecran Foyer unique (remplace l'ancien ecran autonome
    // "Mes foyers" — route FOYERS supprimee).
    const val FOYER_SETTINGS = "foyer_settings"
    const val CHANGELOG = "changelog"

    // v0.30 — onboarding sans compte
    const val WELCOME = "welcome"
    const val CREATE_FOYER_WIZARD = "create_foyer_wizard"
    // P4a.2 — même wizard, variante "ajouter un foyer" (user connecté).
    fun createFoyerWizardAdd() = "$CREATE_FOYER_WIZARD?add=true"
    const val JOIN_FOYER = "join_foyer"
    const val RESTORE_FOYER = "restore_foyer"
    /**
     * Route INVITE_QR avec un parametre optionnel `addDevice` (v0.31) :
     *  - "invite_qr/false" -> QR foyer_join (inviter quelqu'un)
     *  - "invite_qr/true"  -> QR add_device (ajouter un de mes appareils)
     * Utiliser [inviteQr] pour construire l'URL.
     */
    const val INVITE_QR = "invite_qr/{addDevice}"

    fun taskDetail(taskId: String) = "task_detail/$taskId"
    fun taskEdit(taskId: String) = "task_edit/$taskId"
    fun taskClone(cloneFromId: String) = "task_clone/$cloneFromId"
    fun inviteQr(addDevice: Boolean = false) = "invite_qr/$addDevice"
}

/**
 * Elements de la barre de navigation inferieure.
 */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "Accueil", Icons.Default.Home),
    BottomNavItem(Routes.TASK_LIST, "Taches", Icons.Default.CheckCircle),
    BottomNavItem(Routes.STATS, "Stats", Icons.Default.BarChart),
    BottomNavItem(Routes.SETTINGS, "Reglages", Icons.Default.Settings),
)

/**
 * Graphe de navigation principal de l'application.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // v0.30 — Ecran d'accueil onboarding sans compte : 3 entrees,
        // Creer / Rejoindre / Retrouver.
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateFoyerClick = {
                    navController.navigate(Routes.CREATE_FOYER_WIZARD)
                },
                onJoinFoyerClick = {
                    navController.navigate(Routes.JOIN_FOYER)
                },
                onRestoreFoyerClick = {
                    navController.navigate(Routes.RESTORE_FOYER)
                },
            )
        }

        // v0.30 — Wizard "Creer un foyer" en 5 etapes (Toi/Foyer/Pieces/
        // Taches/Recovery). Au succes, navigue vers MAIN en effacant tout
        // le backstack (welcome + wizard ne doivent pas etre re-accessibles
        // via back).
        composable(
            route = Routes.CREATE_FOYER_WIZARD + "?add={add}",
            arguments = listOf(
                navArgument("add") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val add = entry.arguments?.getBoolean("add") ?: false
            CreateFoyerWizardScreen(
                addFoyerMode = add,
                onSuccess = {
                    if (add) {
                        // User déjà connecté : le VM a déjà créé+switché le
                        // foyer ; on revient à l'écran d'origine (Réglages).
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // v0.30 — Scanner QR pour rejoindre un foyer.
        composable(Routes.JOIN_FOYER) {
            JoinFoyerScreen(
                onSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // v0.30 — Saisie recovery code + verif step + restauration JWT.
        composable(Routes.MY_DEVICES) {
            DevicesScreen(
                onBack = { navController.popBackStack() },
                onAddDevice = {
                    navController.navigate(Routes.inviteQr(addDevice = true))
                },
                onSignedOut = {
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.RESTORE_FOYER) {
            RestoreFoyerScreen(
                onSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // v0.30 — QR code d'invitation. v0.31 ajoute le param `addDevice`
        // pour basculer entre "inviter quelqu'un" (false) et "ajouter un de
        // mes appareils" (true). Lu par InviteQrViewModel via SavedStateHandle.
        composable(
            route = Routes.INVITE_QR,
            arguments = listOf(
                navArgument("addDevice") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) {
            InviteQrScreen(onBack = { navController.popBackStack() })
        }

        // Ecran principal avec bottom nav
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToTaskDetail = { taskId ->
                    navController.navigate(Routes.taskDetail(taskId))
                },
                onNavigateToCreateTask = {
                    navController.navigate(Routes.TASK_CREATE)
                },
                onNavigateToEditTask = { taskId ->
                    navController.navigate(Routes.taskEdit(taskId))
                },
                onNavigateToFoyerSettings = {
                    navController.navigate(Routes.FOYER_SETTINGS)
                },
                onNavigateToChangelog = {
                    navController.navigate(Routes.CHANGELOG)
                },
                onNavigateToCreateFoyer = {
                    navController.navigate(Routes.createFoyerWizardAdd())
                },
                onNavigateToInviteSomeone = {
                    navController.navigate(Routes.inviteQr(addDevice = false))
                },
                onNavigateToAddDevice = {
                    navController.navigate(Routes.inviteQr(addDevice = true))
                },
                onLogout = {
                    // v0.30 — bascule sur Welcome (les 3 boutons).
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        // Notes de version + Roadmap
        composable(Routes.CHANGELOG) {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }

        // Catalogue de templates (v0.9.0 Feature 4)
        // Gestion des categories
        // Contextes — remplacent Pieces et Categories. Les deux anciennes
        // destinations restent joignables tant que le serveur ecrit en double,
        // mais plus rien n'y mene depuis l'interface.
        composable(Routes.CONTEXTS) {
            val apiService = javax.inject.Provider<ApiService> {
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    navController.context.applicationContext,
                    ApiServiceEntryPoint::class.java
                ).apiService()
            }
            ContextsScreen(
                apiService = apiService.get(),
                onBack = { navController.popBackStack() }
            )
        }

        // P1 refonte — l'ancien ecran autonome "Mes foyers" (Routes.FOYERS /
        // FoyersScreen) a ete supprime : remplace par l'entree "🏠 <nom>" des
        // Reglages + le bouton "Creer un foyer" + la feuille switch DANS
        // l'ecran Foyer (design decision 3).

        // P1 refonte — Ecran Foyer unique (design-foyer-settings-presence.md
        // decision 2). Plein ecran (couvre la bottom nav), atteignable depuis
        // la ligne Reglages "🏠 <nom>" et la card Foyer de l'accueil.
        composable(Routes.FOYER_SETTINGS) {
            FoyerSettingsScreen(
                onBack = { navController.popBackStack() },
                onManageContexts = { navController.navigate(Routes.CONTEXTS) },
                onInviteSomeone = {
                    navController.navigate(Routes.inviteQr(addDevice = false))
                },
                onAddDevice = {
                    // Mene a la LISTE des appareils, qui porte le bouton
                    // « ajouter » : une seule entree pour voir et pour ajouter,
                    // plutot que deux cartes voisines sur le meme sujet.
                    navController.navigate(Routes.MY_DEVICES)
                },
                onLeftFoyer = {
                    // Plus aucun foyer : retour a Welcome (efface le backstack).
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }

        // Detail d'une tache
        composable(
            route = Routes.TASK_DETAIL,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType })
        ) {
            TaskDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { taskId ->
                    navController.navigate(Routes.taskEdit(taskId))
                },
                onClone = { sourceId ->
                    navController.navigate(Routes.taskClone(sourceId))
                },
            )
        }

        // Creer une tache
        composable(Routes.TASK_CREATE) {
            TaskEditScreen(
                taskId = null,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                }
            )
        }

        // Editer une tache
        composable(
            route = Routes.TASK_EDIT,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
            TaskEditScreen(
                taskId = taskId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                }
            )
        }

        // Dupliquer une tache : meme ecran TaskEditScreen, mais en mode creation
        // pre-remplie depuis la tache source. Pas de tache cree tant que l'user
        // n'a pas valide.
        composable(
            route = Routes.TASK_CLONE,
            arguments = listOf(navArgument("cloneFromId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cloneFromId = backStackEntry.arguments?.getString("cloneFromId")
            TaskEditScreen(
                taskId = null,
                cloneFromId = cloneFromId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = {
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                }
            )
        }
    }
}

/**
 * Ecran principal avec la barre de navigation inferieure.
 */
@Composable
fun MainScreen(
    onNavigateToTaskDetail: (String) -> Unit,
    onNavigateToCreateTask: () -> Unit,
    onNavigateToEditTask: (String) -> Unit,
    onNavigateToFoyerSettings: () -> Unit,
    onNavigateToChangelog: () -> Unit,
    onNavigateToCreateFoyer: () -> Unit,
    onNavigateToInviteSomeone: () -> Unit,
    onNavigateToAddDevice: () -> Unit,
    onLogout: () -> Unit
) {
    val innerNavController = androidx.navigation.compose.rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // v0.32 : tab Stats cachee quand gamification desactivee (user OR foyer
    // courant). Le filtrage UI reactif est observe via TokenManager —
    // toggler dans Reglages fait disparaitre la tab instantanement.
    val context = LocalContext.current
    val tokenManager = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, TokenManagerEntryPoint::class.java)
            .tokenManager()
    }
    val gamificationVisible by tokenManager.gamificationVisibleFlow
        .collectAsState(initial = true)
    // P3 — l'onglet Stats porte DEUX modules : les courbes et l'historique
    // (`statistics`) d'un cote, le classement, les badges et les series
    // (`motivation`) de l'autre. Il reste donc visible si l'un OU l'autre est
    // actif ; c'est a l'interieur de l'ecran que chaque bloc se garde. Le lier
    // au seul `motivation`, comme avant, faisait disparaitre les courbes avec
    // les points alors qu'elles n'en dependent pas.
    val statsVisible by tokenManager
        .moduleEnabledFlow("statistics")
        .collectAsState(initial = true)
    val statsTabVisible = statsVisible || gamificationVisible
    val visibleNavItems = if (statsTabVisible) {
        bottomNavItems
    } else {
        bottomNavItems.filter { it.route != Routes.STATS }
    }

    // Si l'user est sur la tab Stats et que la gamif se desactive (toggle
    // distant par l'autre membre, ou auto-toggle), on rebascule sur Home —
    // sinon il reste coince sur un ecran sans tab visible dans la nav.
    LaunchedEffect(statsTabVisible, currentRoute) {
        if (!statsTabVisible && currentRoute == Routes.STATS) {
            innerNavController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                visibleNavItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            if (currentRoute != item.route) {
                                innerNavController.navigate(item.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { paddingValues ->
        // L'etat de connexion/sync est desormais expose par l'icone
        // SyncStatusIcon du header de l'Accueil (l'ancien OfflineBanner
        // decalait tout le layout vers le bas en apparaissant).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = innerNavController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onTaskClick = onNavigateToTaskDetail,
                        onEditTask = onNavigateToEditTask,
                        onNavigateToSettings = onNavigateToFoyerSettings,
                        onFoyerClick = onNavigateToFoyerSettings,
                    )
                }
                composable(Routes.TASK_LIST) {
                    TaskListScreen(
                        onTaskClick = onNavigateToTaskDetail,
                        onCreateTask = onNavigateToCreateTask,
                        onLogout = onLogout,
                        onNavigateToFoyerSettings = onNavigateToFoyerSettings,
                    )
                }
                composable(Routes.STATS) {
                    StatsScreen(
                        onTaskClick = onNavigateToTaskDetail,
                        onNavigateToFoyerSettings = onNavigateToFoyerSettings,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onLogout = onLogout,
                        onOpenChangelog = onNavigateToChangelog,
                        onOpenFoyerSettings = onNavigateToFoyerSettings,
                        onCreateFoyer = onNavigateToCreateFoyer,
                    )
                }
            }
        }
    }
}

/** EntryPoint Hilt pour acceder a TokenManager depuis les composables sans ViewModel
 *  (utilise par MainScreen pour observer gamificationVisibleFlow). v0.32. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TokenManagerEntryPoint {
    fun tokenManager(): TokenManager
}

/** EntryPoint Hilt pour acceder a ApiService depuis les composables sans ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApiServiceEntryPoint {
    fun apiService(): ApiService
}

// P1 refonte — FoyersEntryPoint supprime avec l'ecran autonome "Mes foyers".
// Le switch/create foyer passe desormais par SettingsViewModel (Hilt).
