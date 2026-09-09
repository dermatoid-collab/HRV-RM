package com.hrvrm.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MeasurementEntity::class], version = 2, exportSchema = false)
abstract class MeasurementDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile
        private var instance: MeasurementDatabase? = null

        fun get(context: Context): MeasurementDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeasurementDatabase::class.java,
                    "hrv-rm.db",
                )
                    // Pre-release app, no user data to preserve across schema changes yet.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
