package com.torchons.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.torchons.app.data.local.TokenManager
import com.torchons.app.ui.navigation.TokenManagerEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Lecture d'un module depuis un composable sans passer par un ViewModel.
 *
 * Meme motif que ce que MainScreen faisait deja pour `gamificationVisibleFlow`
 * (EntryPoint Hilt sur TokenManager), generalise aux 8 modules. L'etat vient
 * de DataStore, alimente apres chaque GET /api/modules.
 *
 * `fallback = true` par defaut, et ce n'est pas un defaut metier : le defaut
 * metier vit dans modules.yaml cote serveur. Ici on ne comble que la fenetre
 * avant la premiere reponse reseau, et on la comble en montrant plutot qu'en
 * masquant — faire disparaitre un ecran le temps d'un aller-retour serait pire
 * que de l'afficher une seconde de trop.
 */
@Composable
fun moduleEnabled(key: String, fallback: Boolean = true): Boolean {
    val context = LocalContext.current
    val tokenManager: TokenManager = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, TokenManagerEntryPoint::class.java)
            .tokenManager()
    }
    val enabled by tokenManager
        .moduleEnabledFlow(key, fallback)
        .collectAsState(initial = fallback)
    return enabled
}

/**
 * Points and streaks as the screens show them: module `motivation` on AND the personal
 * "hide points" preference not set. Same flow as the Home / Stats screens.
 */
@Composable
fun gamificationVisible(): Boolean {
    val context = LocalContext.current
    val tokenManager: TokenManager = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, TokenManagerEntryPoint::class.java)
            .tokenManager()
    }
    val visible by tokenManager.gamificationVisibleFlow.collectAsState(initial = true)
    return visible
}

/** Cles du registre, pour eviter les chaines libres aux points d'appel. */
object Modules {
    const val REPARTITION = "repartition"
    const val MOTIVATION = "motivation"
    const val BONUS_MALUS = "bonus_malus"
    const val OBJECTIVES = "objectives"
    const val PRESENCE = "presence"
    const val AUTO_ADJUST = "auto_adjust"
    const val STATISTICS = "statistics"
    const val REMINDERS = "reminders"
}
