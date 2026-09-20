package com.hrvrm.app.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.hrvrm.app.MainActivity
import com.hrvrm.app.R

internal const val BACKUP_CHANNEL_ID = "backup_reminder"
private const val REMINDER_NOTIFICATION_ID = 1
private const val RESULT_NOTIFICATION_ID = 2

internal fun ensureBackupNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            BACKUP_CHANNEL_ID,
            "Backup",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Weekly backup reminders and export/import confirmations."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

internal fun postReminderNotification(context: Context) {
    ensureBackupNotificationChannel(context)

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_BACKUP, true)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val notification = NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Back up your HRV history")
        .setContentText("It's been a week — export your measurements from Settings.")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    if (hasNotificationPermission(context)) {
        context.getSystemService(NotificationManager::class.java).notify(REMINDER_NOTIFICATION_ID, notification)
    }
}

/**
 * Posted right after a successful export or import (never on failure — the in-Settings
 * error text already covers that) so it's visible even if you've switched away from the
 * app while it finishes. Uses the same channel as the weekly reminder.
 */
fun notifyBackupResult(context: Context, title: String, text: String) {
    ensureBackupNotificationChannel(context)

    val notification = NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .build()

    if (hasNotificationPermission(context)) {
        context.getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }
}
