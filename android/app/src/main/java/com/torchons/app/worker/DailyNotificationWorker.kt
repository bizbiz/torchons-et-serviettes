package com.torchons.app.worker

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torchons.app.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/**
 * Daily local notification: how many overdue tasks are mine today,
 * N = round_half_up(red tasks x my target share). Computed offline from the
 * Room cache (see [loadStatePairs]); N == 0 means no notification.
 */
class DailyNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "torchons_daily"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "daily_notification"

        /**
         * Schedules the daily notification at [hourOfDay]; `enabled` = false cancels.
         * See [scheduleDailyWorker] for the `replace` semantics.
         */
        fun schedule(
            context: Context,
            hourOfDay: Int = 9,
            enabled: Boolean = true,
            replace: Boolean = false,
        ) = scheduleDailyWorker<DailyNotificationWorker>(context, WORK_NAME, hourOfDay, enabled, replace)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val tokens = ep.tokenManager()
        val repo = ep.taskRepository()

        // Signed out: nothing to show; the work keeps its schedule.
        if (tokens.tokenFlow.first().isNullOrBlank()) return Result.success()

        val states = loadStatePairs(repo, tokens, applicationContext.resources)
        val count = dailyReminderCount(states.count { it.isRedToday() }, myTargetRatio(repo, tokens))
        if (count == 0) {
            // Silent day: also withdraw yesterday's count, which would otherwise stay on screen.
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
            return Result.success()
        }

        postReminder(
            context = applicationContext,
            channelId = CHANNEL_ID,
            channelName = applicationContext.getString(R.string.notif_daily_channel_name),
            notificationId = NOTIFICATION_ID,
            title = applicationContext.getString(R.string.app_name),
            text = applicationContext.resources.getQuantityString(R.plurals.notif_daily_overdue_text, count, count),
        )
        return Result.success()
    }
}
