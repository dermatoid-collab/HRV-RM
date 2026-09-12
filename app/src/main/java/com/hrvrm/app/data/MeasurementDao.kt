package com.hrvrm.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Update
    suspend fun update(measurement: MeasurementEntity)

    @Query("SELECT * FROM measurements ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun getById(id: Long): MeasurementEntity?

    /** Prior readings used to build today's rolling HRV baseline, most recent first. */
    @Query(
        "SELECT * FROM measurements WHERE timestampEpochMs < :beforeEpochMs " +
            "ORDER BY timestampEpochMs DESC LIMIT :limit",
    )
    suspend fun getPriorMeasurements(beforeEpochMs: Long, limit: Int): List<MeasurementEntity>

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
}
