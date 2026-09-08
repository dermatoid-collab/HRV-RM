package com.hrvrm.app.data

import android.content.Context
import com.hrvrm.app.network.IntervalsIcuRepository

/** Simple manual DI container: one instance per app, created in [com.hrvrm.app.HrvRmApp]. */
class AppContainer(context: Context) {

    val settingsStore = SettingsStore(context)

    private val database = MeasurementDatabase.get(context)
    private val intervalsIcuRepository = IntervalsIcuRepository(settingsStore)

    val measurementRepository = MeasurementRepository(
        dao = database.measurementDao(),
        intervalsRepository = intervalsIcuRepository,
        settingsStore = settingsStore,
    )
}
