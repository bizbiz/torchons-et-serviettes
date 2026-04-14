package com.torchons.app.core.presence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.PresenceRequest
import com.torchons.app.util.WifiHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PresenceWorkerEntryPoint {
    fun apiService(): ApiService
    fun tokenManager(): TokenManager
}

/**
 * Worker WorkManager qui check si le device est sur un WiFi maison et
 * POST le resultat au backend. Execute toutes les 6h en arriere-plan pour
 * detecter la presence meme quand l'utilisateur n'ouvre pas l'app
 * (Feature 1 home freeze v0.7.4).
 *
 * Peut aussi etre declenche one-shot au demarrage de l'app via
 * PresenceTrigger.reportNow() pour rafraichir immediatement l'etat.
 *
 * Comportement :
 * 1. Recupere la liste des home_ssids du user courant (TokenManager)
 * 2. Lit le SSID WiFi actuel via WifiHelper
 * 3. is_at_home = ssid actuel dans la liste des home_ssids
 * 4. POST /api/presence { is_at_home }
 * 5. Si l'user n'a pas de home_ssids configures ou pas de WiFi connecte,
 *    on saute (pas de report = pas de mise a jour).
 */
class PresenceWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(
            applicationContext, PresenceWorkerEntryPoint::class.java
        )
        val apiService = ep.apiService()
        val tokenManager = ep.tokenManager()

        // Pas de token = pas connecte = on saute silencieusement.
        val token = tokenManager.tokenFlow.first()
        if (token.isNullOrBlank()) return Result.success()

        // Check WiFi local
        val homeSsids = tokenManager.homeSsidsFlow.first()
        if (homeSsids.isEmpty()) {
            // L'user n'a pas configure de WiFi maison : on ne peut pas juger,
            // on ne reporte rien.
            return Result.success()
        }

        val currentSsid = WifiHelper.getCurrentSsid(applicationContext)
        val isAtHome = currentSsid != null && homeSsids.any {
            it.equals(currentSsid, ignoreCase = true)
        }

        // POST au backend (best effort — si offline, on retry au prochain cycle)
        return try {
            val resp = apiService.postPresence(PresenceRequest(isAtHome = isAtHome))
            if (resp.isSuccessful) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
