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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hrvrm.app.MainActivity
import com.hrvrm.app.R
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "backup_reminder"
private const val CHANNEL_ID = "backup_reminder"
private const val NOTIFICATION_ID = 1
private val REMINDER_INTERVAL = 7L to TimeUnit.DAYS

/**
 * Not automatic — this app deliberately has no scheduled auto-export (silently writing
 * files in the background is exactly the kind of thing this project has stayed away from
 * around personal health data). Just a weekly nudge, since debug builds sometimes need a
 * full uninstall/reinstall (a signature or schema change can't always update in place),
 * which wipes the local Room database unless the history was exported first.
 */
class BackupReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        postReminderNotification(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupReminderWorker>(REMINDER_INTERVAL.first, REMINDER_INTERVAL.second)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

private fun postReminderNotification(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Backup reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Weekly reminder to export your measurement history."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

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

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Back up your HRV history")
        .setContentText("It's been a week — export your measurements from Settings.")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
