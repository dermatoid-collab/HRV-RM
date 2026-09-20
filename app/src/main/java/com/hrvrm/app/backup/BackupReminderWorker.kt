package com.hrvrm.app.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "backup_reminder"
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
