package com.hrvrm.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Path

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
    /** Custom wellness field on this athlete's Intervals.icu account. */
    @SerialName("HRVRM")
    val hrvScore: Int? = null,
)

interface IntervalsIcuApi {

    @PUT("api/v1/athlete/{athleteId}/wellness/{date}")
    suspend fun updateWellness(
        @Path("athleteId") athleteId: String,
        @Path("date") isoDate: String,
        @Body body: WellnessUpdate,
    ): Response<Unit>
}
