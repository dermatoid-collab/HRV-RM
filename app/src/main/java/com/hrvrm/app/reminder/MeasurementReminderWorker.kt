package com.hrvrm.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "measurement_reminder"

/**
 * A daily nudge to take today's HRV measurement, at a user-chosen time (05:00 by default,
 * on by default) -- see SettingsStore.measurementReminderEnabled/Hour/Minute and the
 * Settings screen's "Reminder" row. Not an exact alarm: like BackupReminderWorker, this
 * rides WorkManager's regular (Doze-deferrable) periodic work rather than
 * SCHEDULE_EXACT_ALARM, so "05:00" means "around 05:00", not to the minute -- fine for a
 * morning nudge, and keeps this feature from needing Android 12+'s separate exact-alarm
 * permission screen.
 */
class MeasurementReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        postMeasurementReminderNotification(applicationContext)
        return Result.success()
    }

    companion object {
        /**
         * Replaces whatever daily work was previously scheduled with one for [hour]:[minute],
         * or cancels it outright when [enabled] is false. Called on every app start (current
         * settings) and immediately whenever the user changes the toggle or the time in
         * Settings (the new values), so a change takes effect right away rather than waiting
         * for the next natural reschedule.
         */
        fun reschedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<MeasurementReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayUntilNext(hour, minute))
                .build()
            // REPLACE (not KEEP/UPDATE) so a time change is unambiguous: tear down whatever
            // was scheduled and start the 24h cycle fresh from the newly computed delay.
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
        }

        private fun delayUntilNext(hour: Int, minute: Int): Duration {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(hour, minute))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
