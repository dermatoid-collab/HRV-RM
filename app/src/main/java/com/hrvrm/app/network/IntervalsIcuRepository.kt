package com.hrvrm.app.network

import com.hrvrm.app.data.SettingsStore
import java.time.Instant
import java.time.LocalDate
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
        hrvRmValue: Double,
        restingHrBpm: Int?,
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
            restingHR = restingHrBpm,
            hrvRmValue = roundTo(hrvRmValue, 1),
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

    /**
     * Cheap read-only check that the saved API key and Athlete ID actually match — an
     * intervals.icu API key is scoped to the athlete who generated it, so a 403 on the
     * (write) wellness endpoint is often really "this key isn't valid for this athlete
     * ID" rather than a credentials typo. Hitting a read endpoint first isolates that.
     */
    suspend fun testConnection(): UploadResult {
        val apiKey = settingsStore.apiKey.first()
        val athleteId = settingsStore.athleteId.first()

        if (apiKey.isNullOrBlank() || athleteId.isNullOrBlank()) {
            return UploadResult.MissingCredentials(
                "Set your Intervals.icu API key and Athlete ID first.",
            )
        }

        val today = LocalDate.now().format(dateFormatter)

        return try {
            val api = IntervalsIcuClientFactory.create(apiKey)
            val response = api.getEvents(athleteId, oldest = today, newest = today)
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

    /**
     * Writes a placeholder value to today's HRVRM custom field only (hrv/hrvSDNN/
     * restingHR are left unset, so today's already-uploaded real values aren't
     * touched — PUT only sets the fields present in the body).
     */
    suspend fun sendTestHrvScore(testValue: Double): UploadResult {
        val apiKey = settingsStore.apiKey.first()
        val athleteId = settingsStore.athleteId.first()

        if (apiKey.isNullOrBlank() || athleteId.isNullOrBlank()) {
            return UploadResult.MissingCredentials(
                "Set your Intervals.icu API key and Athlete ID first.",
            )
        }

        val today = LocalDate.now().format(dateFormatter)
        val body = WellnessUpdate(hrvRmValue = testValue)

        return try {
            val api = IntervalsIcuClientFactory.create(apiKey)
            val response = api.updateWellness(athleteId, today, body)
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
