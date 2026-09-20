package com.hrvrm.app.data

import kotlinx.serialization.Serializable

/**
 * The full local measurement history, computed/derived metrics only — no raw camera
 * samples (those are never persisted to Room in the first place; see
 * MeasurementViewModel.exportRawSamplesFile for that separate, algorithm-debugging export).
 * Meant to survive a reinstall: this app's debug signing lets an update install in place,
 * but a version bump that still requires uninstall/reinstall wipes the local database.
 */
@Serializable
data class MeasurementBackup(
    val exportedAtEpochMs: Long,
    val measurements: List<MeasurementEntity>,
)

/** Outcome of restoring a [MeasurementBackup] — see [MeasurementRepository.importBackupJson]. */
data class BackupImportResult(
    val imported: Int,
    val skippedAlreadyPresent: Int,
)
