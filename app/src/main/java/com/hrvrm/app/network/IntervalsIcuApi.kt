package com.hrvrm.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Fields understood by Intervals.icu's per-day wellness entry. Only the HRV-related ones
 * are populated here; unset fields are omitted and left untouched server-side.
 */
@Serializable
data class WellnessUpdate(
    /** rMSSD in ms — this is the field Intervals.icu's own HRV integrations (e.g. HRV4Training) populate. */
    val hrv: Double? = null,
    val hrvSDNN: Double? = null,
    val restingHR: Int? = null,
    /**
     * Custom wellness field on this athlete's Intervals.icu account. Holds the
     * HRV4Training-style ln-scale reading (roughly 6-10), not the app's own 0-100
     * baseline-comparison score — that value is always available, even on the very
     * first measurement, whereas the 0-100 score needs history to mean anything.
     */
    @SerialName("HRVRM")
    val hrvRmValue: Double? = null,
)

interface IntervalsIcuApi {

    @PUT("api/v1/athlete/{athleteId}/wellness/{date}")
    suspend fun updateWellness(
        @Path("athleteId") athleteId: String,
        @Path("date") isoDate: String,
        @Body body: WellnessUpdate,
    ): Response<Unit>

    /**
     * Cheap read-only call used to validate that the API key and athlete ID actually
     * match, before attempting a write. Same endpoint shape as ERG-RM's proven-working
     * calendar read (`GET .../events`) — a zero-day range still hits real auth/athlete
     * checks without needing to model the full event payload.
     */
    @GET("api/v1/athlete/{athleteId}/events")
    suspend fun getEvents(
        @Path("athleteId") athleteId: String,
        @Query("oldest") oldest: String,
        @Query("newest") newest: String,
    ): Response<Unit>
}
