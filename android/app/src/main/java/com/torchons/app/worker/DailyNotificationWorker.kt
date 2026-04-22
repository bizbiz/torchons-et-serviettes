package com.torchons.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torchons.app.MainActivity
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Suggestion
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Worker pour envoyer une notification locale quotidienne
 * avec le resume des taches a faire.
 *
 * Utilise ApiService (authentifie via AuthInterceptor) plutot qu'un
 * HttpURLConnection brut. Sans token JWT, l'endpoint /api/suggestions
 * renvoyait 401 et le worker echouait silencieusement (emptyList ->
 * Result.success sans notif) — c'etait le bug historique.
 */
class DailyNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun apiService(): ApiService
        fun tokenManager(): TokenManager
    }

    companion object {
        const val CHANNEL_ID = "torchons_daily"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "daily_notification"

        /**
         * Planifie la notification quotidienne a l'heure choisie (0..23).
         *
         * - `enabled` = false -> annule, aucune notif prevue.
         * - `replace` = true -> applique immediatement le nouveau delai
         *   (utilise depuis Reglages quand l'heure change). Sinon KEEP :
         *   si une instance existe deja, on ne touche pas (appel idempotent
         *   depuis Application.onCreate).
         *
         * WorkManager respecte une fenetre de flex de ~15min pour la batterie :
         * la notif peut tomber jusqu'a 15min autour de l'heure cible.
         */
        fun schedule(
            context: Context,
            hourOfDay: Int = 9,
            enabled: Boolean = true,
            replace: Boolean = false,
        ) {
            if (!enabled) {
                cancel(context)
                return
            }

            val hour = hourOfDay.coerceIn(0, 23)
            val now = java.time.LocalDateTime.now()
            var target = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
            if (!target.isAfter(now)) {
                target = target.plusDays(1)
            }
            val initialDelayMs = java.time.Duration.between(now, target).toMillis()

            val request = PeriodicWorkRequestBuilder<DailyNotificationWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()

            val policy = if (replace) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                policy,
                request,
            )
        }

        /** Annule la notification quotidienne. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val ep = EntryPointAccessors.fromApplication(
            applicationContext, WorkerEntryPoint::class.java
        )

        // Pas connecte : rien a afficher. On laisse le worker se replanifier
        // normalement, il reessayera demain (eventuellement apres login).
        val token = try { ep.tokenManager().tokenFlow.first() } catch (_: Exception) { null }
        if (token.isNullOrBlank()) return Result.success()

        // Appel authentifie via ApiService (Retrofit + AuthInterceptor).
        // v0.16+ : /suggestions retourne un wrapper SuggestionsResponse — on
        // extrait juste la liste (le worker n'a pas de notion de dismiss list).
        val suggestions: List<Suggestion> = try {
            val resp = ep.apiService().getSuggestions()
            if (resp.isSuccessful) resp.body()?.suggestions ?: emptyList() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (suggestions.isEmpty()) return Result.success()

        val urgentCount = suggestions.count { it.reason == "urgent" }
        val title = "Torchons & Serviettes"
        val text = if (urgentCount > 0) {
            "Tu as $urgentCount tache(s) urgente(s) a faire !"
        } else {
            "Des taches t'attendent aujourd'hui."
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }

        return Result.success()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Resume quotidien",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Resume quotidien des taches a faire"
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
