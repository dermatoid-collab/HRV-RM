package com.hrvrm.app.network

import com.hrvrm.app.data.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

sealed class UploadResult {
    data object Success : UploadResult()
    data class MissingCredentials(val message: String) : UploadResult()
    data class Failure(val message: String) : UploadResult()
}

/** Pushes a single day's HRV reading to the athlete's Intervals.icu wellness log. */
class IntervalsIcuRepository(
    private val settingsStore: SettingsStore,
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun uploadHrv(
        measurementEpochMs: Long,
        rmssdMs: Double,
        sdnnMs: Double,
        hrvScore: Int?,
    ): UploadResult {
        val apiKey = settingsStore.apiKey.first()
        val athleteId = settingsStore.athleteId.first()

        if (apiKey.isNullOrBlank() || athleteId.isNullOrBlank()) {
            return UploadResult.MissingCredentials(
                "Set your Intervals.icu API key and Athlete ID in Settings.",
            )
        }

        val isoDate = Instant.ofEpochMilli(measurementEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)

        val body = WellnessUpdate(
            hrv = roundTo(rmssdMs, 2),
            hrvSDNN = roundTo(sdnnMs, 2),
            hrvScore = hrvScore,
        )

        return try {
            val api = IntervalsIcuClientFactory.create(apiKey)
            val response = api.updateWellness(athleteId, isoDate, body)
            if (response.isSuccessful) {
                UploadResult.Success
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                UploadResult.Failure("HTTP ${response.code()}: $errorBody")
            }
        } catch (e: Exception) {
            UploadResult.Failure(e.message ?: e.toString())
        }
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * factor) / factor
    }
}
