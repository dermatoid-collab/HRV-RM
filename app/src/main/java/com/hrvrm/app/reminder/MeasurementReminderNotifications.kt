package com.hrvrm.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hrvrm.app.MainActivity
import com.hrvrm.app.R
import com.hrvrm.app.backup.hasNotificationPermission

internal const val MEASUREMENT_REMINDER_CHANNEL_ID = "measurement_reminder"
private const val MEASUREMENT_REMINDER_NOTIFICATION_ID = 3

/** Request code distinct from BackupNotifications' PendingIntents (which target the same
 * MainActivity) -- PendingIntent.getActivity matching ignores extras, so reusing a request
 * code would make Android treat this and the backup reminder's PendingIntent as the same
 * one, silently repointing whichever fires first. */
private const val MEASUREMENT_REMINDER_REQUEST_CODE = 1

internal fun ensureMeasurementReminderChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            MEASUREMENT_REMINDER_CHANNEL_ID,
            "Measurement reminder",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily nudge to take today's HRV measurement."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

internal fun postMeasurementReminderNotification(context: Context) {
    ensureMeasurementReminderChannel(context)

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        MEASUREMENT_REMINDER_REQUEST_CODE,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val notification = NotificationCompat.Builder(context, MEASUREMENT_REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Time for today's HRV reading")
        .setContentText("Keep still, breathe naturally, and measure before your day gets going.")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    if (hasNotificationPermission(context)) {
        context.getSystemService(NotificationManager::class.java).notify(MEASUREMENT_REMINDER_NOTIFICATION_ID, notification)
    }
}
