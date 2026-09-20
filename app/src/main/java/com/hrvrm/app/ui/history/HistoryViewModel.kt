package com.hrvrm.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.data.MeasurementEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container get() = (getApplication<Application>() as HrvRmApp).container

    val measurements: StateFlow<List<MeasurementEntity>> = container.measurementRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One point per day (latest measurement wins) for the trend chart, oldest first. */
    val dailyTrend: StateFlow<List<DailyHrvPoint>> = container.measurementRepository
        .observeAll()
        .map(::buildDailyPoints)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Same day-per-latest-measurement rule as [dailyTrend], but for resting heart rate. */
    val dailyRhrTrend: StateFlow<List<DailyRhrPoint>> = container.measurementRepository
        .observeAll()
        .map(::buildDailyRhrPoints)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
