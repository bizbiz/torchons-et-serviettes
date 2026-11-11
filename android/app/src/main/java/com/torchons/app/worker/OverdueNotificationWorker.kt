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
 * Overdue notification: the tasks that turned RED today (RED today, not RED
 * yesterday), computed offline from the Room cache. None: no notification.
 * Sibling of [DailyNotificationWorker], scheduled the same way.
 */
class OverdueNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "torchons_overdue"
        const val NOTIFICATION_ID = 1002
        const val WORK_NAME = "overdue_notification"

        /** Schedules at [hourOfDay]; `enabled` = false cancels. */
        fun schedule(
            context: Context,
            hourOfDay: Int = 8,
            enabled: Boolean = true,
            replace: Boolean = false,
        ) = scheduleDailyWorker<OverdueNotificationWorker>(context, WORK_NAME, hourOfDay, enabled, replace)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val tokens = ep.tokenManager()

        if (tokens.tokenFlow.first().isNullOrBlank()) return Result.success()

        val titles = newlyRedTitles(loadStatePairs(ep.taskRepository(), tokens, applicationContext.resources))
        if (titles.isEmpty()) {
            // Nothing turned red today: withdraw the previous list, which is no longer news.
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
            return Result.success()
        }

        postReminder(
            context = applicationContext,
            channelId = CHANNEL_ID,
            channelName = applicationContext.getString(R.string.notif_overdue_channel_name),
            notificationId = NOTIFICATION_ID,
            title = applicationContext.getString(R.string.notif_overdue_title),
            text = overdueBody(titles),
        )
        return Result.success()
    }
}
