package com.hrvrm.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MeasurementEntity::class], version = 3, exportSchema = false)
abstract class MeasurementDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        // Real measurement history now accumulates (it feeds the rolling baseline), so
        // this adds the new column in place instead of falling back to a destructive
        // migration like earlier schema changes did.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE measurements ADD COLUMN normalizedHrvPercent REAL NOT NULL DEFAULT 0.0",
                )
            }
        }

        @Volatile
        private var instance: MeasurementDatabase? = null

        fun get(context: Context): MeasurementDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeasurementDatabase::class.java,
                    "hrv-rm.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    // Safety net for any schema version this app has never actually
                    // shipped with (e.g. an old dev install pre-dating version 2).
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
