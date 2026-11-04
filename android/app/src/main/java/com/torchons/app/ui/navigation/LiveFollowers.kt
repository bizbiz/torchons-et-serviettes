package com.torchons.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.torchons.app.core.modules.ModuleStateRefresher
import com.torchons.app.core.proposition.PropositionStateHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PropositionEntryPoint {
    fun propositionStateHolder(): PropositionStateHolder
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ModuleStateEntryPoint {
    fun moduleStateRefresher(): ModuleStateRefresher
}

/**
 * Keeps server-owned state fresh while the app is in the foreground, whatever screen
 * is open: it is called above the root NavHost, not inside the tabs screen. Inside the
 * tabs, both stopped as soon as a task page or the Foyer screen was opened (seen on the
 * emulator on 2026-11-04).
 *
 * - Module states (ModuleStateRefresher), fetched on each return to the foreground.
 * - Live propositions (PropositionStateHolder.pollWhileVisible): push is best-effort
 *   (the beta sends none, a phone can drop one), and without polling the waiting dialog
 *   never closed and a new proposition only showed after an app restart.
 */
@Composable
internal fun KeepLiveStateFresh() {
    val context = LocalContext.current
    val app = context.applicationContext
    val refresher = remember(app) {
        EntryPointAccessors.fromApplication(app, ModuleStateEntryPoint::class.java).moduleStateRefresher()
    }
    val holder = remember(app) {
        EntryPointAccessors.fromApplication(app, PropositionEntryPoint::class.java).propositionStateHolder()
    }
    val tokenManager = remember(app) {
        EntryPointAccessors.fromApplication(app, TokenManagerEntryPoint::class.java).tokenManager()
    }
    // Keyed on the token: the first start happens BEFORE sign-in (no token, refresh
    // returns at once), so without this a new member kept every module "on" until
    // the app went to the background and back (seen on the emulator, 2026-11-04).
    // A foyer switch issues a new token too, and reloads that foyer's modules.
    val token by tokenManager.tokenFlow.collectAsState(initial = null)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, token) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { refresher.refresh() }
            holder.pollWhileVisible()
        }
    }
}

/**
 * A live foyer proposition (3 minutes to answer) is shown by the home tab only: the
 * proposer's waiting dialog and the others' accept/decline dialog. Whatever tab is
 * open, and even when the proposition was made from the Foyer screen, follow it there.
 * A proposition that arrives while a screen outside the tabs is open shows on return.
 */
@Composable
internal fun FollowLiveProposition(innerNavController: NavHostController, currentRoute: String?) {
    val context = LocalContext.current
    val holder = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PropositionEntryPoint::class.java)
            .propositionStateHolder()
    }
    val live by holder.currentProposition.collectAsState()
    LaunchedEffect(live?.id, currentRoute) {
        if (live != null && currentRoute != null && currentRoute != Routes.HOME) {
            innerNavController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
            }
        }
    }
}
