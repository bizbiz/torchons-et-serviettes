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
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.torchons.app.MainActivity
import com.torchons.app.R
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Plans a once-a-day worker at [hourOfDay] (0..23), or cancels it when not [enabled].
 *
 * `replace` = true cancels and re-enqueues, the only way to move the next run after
 * an hour change (UPDATE keeps the old `lastEnqueueTime`; `setInitialDelay` only
 * applies to the first run of a periodic work). `replace` = false keeps an existing
 * instance, so the call is idempotent from Application.onCreate. WorkManager may
 * fire up to ~10 min around the target hour for battery reasons.
 */
internal inline fun <reified W : ListenableWorker> scheduleDailyWorker(
    context: Context,
    workName: String,
    hourOfDay: Int,
    enabled: Boolean,
    replace: Boolean,
) {
    val workManager = WorkManager.getInstance(context)
    if (!enabled) {
        workManager.cancelUniqueWork(workName)
        return
    }
    val now = LocalDateTime.now()
    var target = now.withHour(hourOfDay.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
    if (!target.isAfter(now)) target = target.plusDays(1)

    val request = PeriodicWorkRequestBuilder<W>(1, TimeUnit.DAYS)
        .setInitialDelay(Duration.between(now, target).toMillis(), TimeUnit.MILLISECONDS)
        .build()
    val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
    workManager.enqueueUniquePeriodicWork(workName, policy, request)
}

/** Creates the channel (idempotent) and posts one reminder, if the permission is granted. */
internal fun postReminder(
    context: Context,
    channelId: String,
    channelName: String,
    notificationId: Int,
    title: String,
    text: String,
) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT),
    )
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(context, R.color.primary))
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
