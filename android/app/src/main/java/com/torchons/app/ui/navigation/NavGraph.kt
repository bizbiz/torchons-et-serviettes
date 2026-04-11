package com.torchons.app.ui.navigation

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
import com.torchons.app.ui.changelog.ChangelogScreen
import com.torchons.app.ui.home.HomeScreen
import com.torchons.app.ui.login.LoginScreen
import com.torchons.app.ui.settings.CategoriesScreen
import com.torchons.app.ui.settings.SettingsScreen
import com.torchons.app.ui.stats.StatsScreen
import com.torchons.app.ui.taskdetail.TaskDetailScreen
import com.torchons.app.ui.taskedit.TaskEditScreen
import com.torchons.app.ui.tasklist.TaskListScreen
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
    const val CATEGORIES = "categories"
    const val CHANGELOG = "changelog"

    fun taskDetail(taskId: String) = "task_detail/$taskId"
    fun taskEdit(taskId: String) = "task_edit/$taskId"
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
        // Ecran de connexion
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
                onNavigateToChangelog = {
                    navController.navigate(Routes.CHANGELOG)
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        // Notes de version + Roadmap
        composable(Routes.CHANGELOG) {
            ChangelogScreen(onBack = { navController.popBackStack() })
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

        // Detail d'une tache
        composable(
            route = Routes.TASK_DETAIL,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType })
        ) {
            TaskDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { taskId ->
                    navController.navigate(Routes.taskEdit(taskId))
                }
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
    onNavigateToChangelog: () -> Unit,
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
        NavHost(
            navController = innerNavController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(paddingValues)
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
                    onOpenChangelog = onNavigateToChangelog
                )
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
