package com.hrvrm.app

import android.app.Application
import com.hrvrm.app.backup.BackupReminderWorker
import com.hrvrm.app.data.AppContainer
import com.hrvrm.app.reminder.MeasurementReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HrvRmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        BackupReminderWorker.schedule(this)
        // One-time repair for the HrvScoreCalculator fix -- see backfillHrvScoresIfNeeded's
        // doc comment. Fire-and-forget: idempotent, and every screen reads measurements
        // through the DAO's Flow so the UI updates live as rows get rewritten.
        CoroutineScope(Dispatchers.IO).launch { container.measurementRepository.backfillHrvScoresIfNeeded() }
        // Daily auto-theme roll -- see rollDailyThemeIfDue's doc comment. A no-op unless
        // Settings' Auto theme is set to "Every day" and today's roll hasn't happened yet.
        CoroutineScope(Dispatchers.IO).launch { container.settingsStore.rollDailyThemeIfDue() }
        // (Re)apply the daily measurement-reminder schedule from current settings -- safety
        // net alongside the Settings screen's own immediate reschedule on every toggle/time
        // change, e.g. after a reinstall or if WorkManager's own state ever got cleared.
        CoroutineScope(Dispatchers.IO).launch {
            val settingsStore = container.settingsStore
            MeasurementReminderWorker.reschedule(
                this@HrvRmApp,
                enabled = settingsStore.measurementReminderEnabled.first(),
                hour = settingsStore.measurementReminderHour.first(),
                minute = settingsStore.measurementReminderMinute.first(),
            )
        }
    }
}
