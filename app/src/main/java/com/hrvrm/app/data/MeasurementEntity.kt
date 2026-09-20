package com.hrvrm.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * @Serializable so a full history export (see [MeasurementRepository.exportBackupJson]) can
 * reuse this type directly instead of a separate mirror DTO. The four fields added after
 * this app's first backup-eligible release ([meanIbiMs], [sd1Ms], [sd2Ms], [stressIndex])
 * default to 0.0 so an older backup that predates them still decodes.
 */
@Serializable
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
    /** Mean inter-beat interval (ms) — "Mean RR" in most HRV apps. */
    val meanIbiMs: Double = 0.0,
    /** Poincaré plot short-term variability: RMSSD/sqrt(2). */
    val sd1Ms: Double = 0.0,
    /** Poincaré plot long-term variability: sqrt(2*SDNN^2 - SD1^2). */
    val sd2Ms: Double = 0.0,
    /** Baevsky's Stress Index — see HrvMetricsCalculator.stressIndex. */
    val stressIndex: Double = 0.0,
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
