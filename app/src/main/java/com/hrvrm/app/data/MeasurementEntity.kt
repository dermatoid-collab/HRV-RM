package com.hrvrm.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Wall-clock time the measurement was taken (epoch millis). */
    val timestampEpochMs: Long,
    val durationSec: Int,
    val meanHrBpm: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    val pnn50Percent: Double,
    /** RMSSD/meanRR x 100 — corrects for RMSSD being structurally lower at higher heart rates. */
    val normalizedHrvPercent: Double,
    val beatCount: Int,
    val rejectedBeatCount: Int,
    /** Null while the personal baseline is still being built up (fewer than 7 prior readings). */
    val hrvScore: Int?,
    /** Null while building baseline; true/false once ready — see HrvScoreCalculator.NORMAL_RANGE_SD_MULTIPLIER. */
    val withinNormalRange: Boolean?,
    /** Smoothed value on an HRV4Training-like ln(RMSSD^2) display scale (~6-10 for typical adults). */
    val altiniScaleValue: Double,
    val normalRangeLowAltiniScale: Double?,
    val normalRangeHighAltiniScale: Double?,
    /** Clean IBI series (ms), JSON-encoded, kept for history detail / future re-analysis. */
    val ibiSeriesJson: String,
    val uploadedToIntervals: Boolean = false,
    val uploadError: String? = null,
)
