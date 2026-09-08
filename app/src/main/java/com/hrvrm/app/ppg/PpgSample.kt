package com.hrvrm.app.ppg

/**
 * One raw intensity reading from the camera sensor, used as a PPG proxy.
 *
 * [timestampMs] is relative to the start of the measurement (monotonic, milliseconds).
 * [intensity] is the mean luma of the frame's Y plane: with the fingertip covering the
 * rear camera and torch on, blood volume changes modulate the light reaching the sensor,
 * so this value oscillates with each heartbeat.
 */
data class PpgSample(
    val timestampMs: Long,
    val intensity: Double,
)
