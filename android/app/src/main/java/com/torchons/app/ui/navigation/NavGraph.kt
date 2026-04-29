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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.torchons.app.ui.components.OfflineBanner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.torchons.app.data.api.ApiService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.torchons.app.ui.catalog.CatalogScreen
import com.torchons.app.ui.changelog.ChangelogScreen
import com.torchons.app.ui.home.HomeScreen
import com.torchons.app.ui.login.LoginScreen
import com.torchons.app.ui.settings.CategoriesScreen
import com.torchons.app.ui.settings.FoyersScreen
import com.torchons.app.ui.settings.RoomsScreen
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
    const val LOGIN = "login"
    const val MAIN = "main"
    const val HOME = "home"
    const val TASK_LIST = "task_list"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task_detail/{taskId}"
    const val TASK_CREATE = "task_create"
    const val TASK_EDIT = "task_edit/{taskId}"
    const val TASK_CLONE = "task_clone/{cloneFromId}"
    const val CATEGORIES = "categories"
    const val ROOMS = "rooms"
    const val FOYERS = "foyers"
    const val CHANGELOG = "changelog"
    const val CATALOG = "catalog"

    // v0.30 — onboarding sans compte
    const val WELCOME = "welcome"
    const val CREATE_FOYER_WIZARD = "create_foyer_wizard"
    const val JOIN_FOYER = "join_foyer"
    const val RESTORE_FOYER = "restore_foyer"
    const val INVITE_QR = "invite_qr"

    fun taskDetail(taskId: String) = "task_detail/$taskId"
    fun taskEdit(taskId: String) = "task_edit/$taskId"
    fun taskClone(cloneFromId: String) = "task_clone/$cloneFromId"
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
        // v0.30 — Ecran d'accueil onboarding sans compte. Tente Tailscale
        // auto-login en arriere-plan (fast-path dogfood) puis offre
        // 3 entrees : Creer / Rejoindre / Retrouver.
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onAutoLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
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
        composable(Routes.CREATE_FOYER_WIZARD) {
            CreateFoyerWizardScreen(
                onSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
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

        // v0.30 — QR code d'invitation pour rejoindre le foyer actif.
        composable(Routes.INVITE_QR) {
            InviteQrScreen(onBack = { navController.popBackStack() })
        }

        // Ecran de connexion (legacy : conserve pour le dogfood Tailscale).
        // Sera retire a la bascule publique cf. design-onboarding-public.md §6.
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
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
                onNavigateToCategories = {
                    navController.navigate(Routes.CATEGORIES)
                },
                onNavigateToRooms = {
                    navController.navigate(Routes.ROOMS)
                },
                onNavigateToFoyers = {
                    navController.navigate(Routes.FOYERS)
                },
                onNavigateToChangelog = {
                    navController.navigate(Routes.CHANGELOG)
                },
                onNavigateToCatalog = {
                    navController.navigate(Routes.CATALOG)
                },
                onNavigateToInviteSomeone = {
                    navController.navigate(Routes.INVITE_QR)
                },
                onLogout = {
                    // v0.30 — bascule sur Welcome (les 3 boutons + tentative
                    // Tailscale en arriere-plan). LOGIN reste un fallback
                    // legacy mais n'est plus le point d'entree.
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
        composable(Routes.CATALOG) {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                navController.context.applicationContext,
                ApiServiceEntryPoint::class.java,
            )
            CatalogScreen(
                apiService = ep.apiService(),
                onBack = { navController.popBackStack() },
            )
        }

        // Gestion des categories
        composable(Routes.CATEGORIES) {
            val apiService = javax.inject.Provider<ApiService> {
                // Workaround: on accede via Hilt entry point
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    navController.context.applicationContext,
                    ApiServiceEntryPoint::class.java
                ).apiService()
            }
            CategoriesScreen(
                apiService = apiService.get(),
                onBack = { navController.popBackStack() }
            )
        }

        // Gestion des pieces (rooms) — meme pattern que CategoriesScreen
        composable(Routes.ROOMS) {
            val apiService = javax.inject.Provider<ApiService> {
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    navController.context.applicationContext,
                    ApiServiceEntryPoint::class.java
                ).apiService()
            }
            RoomsScreen(
                apiService = apiService.get(),
                onBack = { navController.popBackStack() }
            )
        }

        // Gestion des foyers (multi-tenant v0.7.0)
        composable(Routes.FOYERS) {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                navController.context.applicationContext,
                FoyersEntryPoint::class.java,
            )
            FoyersScreen(
                apiService = ep.apiService(),
                authRepository = ep.authRepository(),
                taskRepository = ep.taskRepository(),
                onBack = { navController.popBackStack() },
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
    onNavigateToCategories: () -> Unit,
    onNavigateToRooms: () -> Unit,
    onNavigateToFoyers: () -> Unit,
    onNavigateToChangelog: () -> Unit,
    onNavigateToCatalog: () -> Unit,
    onNavigateToInviteSomeone: () -> Unit,
    onLogout: () -> Unit
) {
    val innerNavController = androidx.navigation.compose.rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { item ->
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
        // Bandeau "hors ligne" global au-dessus de tous les ecrans de la
        // navigation principale. Visible uniquement quand le BackendHealthMonitor
        // detecte une panne de connexion. Le NavHost prend la place restante.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OfflineBanner()
            NavHost(
                navController = innerNavController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onTaskClick = onNavigateToTaskDetail,
                        onEditTask = onNavigateToEditTask
                    )
                }
                composable(Routes.TASK_LIST) {
                    TaskListScreen(
                        onTaskClick = onNavigateToTaskDetail,
                        onCreateTask = onNavigateToCreateTask,
                        onOpenCatalog = onNavigateToCatalog,
                        onLogout = onLogout
                    )
                }
                composable(Routes.STATS) {
                    StatsScreen(onTaskClick = onNavigateToTaskDetail)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onLogout = onLogout,
                        onManageCategories = onNavigateToCategories,
                        onManageRooms = onNavigateToRooms,
                        onManageFoyers = onNavigateToFoyers,
                        onOpenChangelog = onNavigateToChangelog,
                        onInviteSomeone = onNavigateToInviteSomeone,
                    )
                }
            }
        }
    }
}

/** EntryPoint Hilt pour acceder a ApiService depuis les composables sans ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApiServiceEntryPoint {
    fun apiService(): ApiService
}

/**
 * EntryPoint Hilt pour FoyersScreen qui a besoin de plusieurs dependances :
 * ApiService + AuthRepository (pour le switch) + TaskRepository (pour le wipe cache).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FoyersEntryPoint {
    fun apiService(): ApiService
    fun authRepository(): com.torchons.app.data.repository.AuthRepository
    fun taskRepository(): com.torchons.app.data.repository.TaskRepository
}
