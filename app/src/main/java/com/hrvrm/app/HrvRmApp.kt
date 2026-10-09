package com.hrvrm.app

import android.app.Application
import com.hrvrm.app.backup.BackupReminderWorker
import com.hrvrm.app.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    }
}
