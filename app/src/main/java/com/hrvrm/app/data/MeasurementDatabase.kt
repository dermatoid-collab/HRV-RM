package com.hrvrm.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MeasurementEntity::class], version = 4, exportSchema = false)
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

        // Mean RR, Poincaré SD1/SD2 and Baevsky's Stress Index (see HrvMetrics.kt) added to
        // the result/history-detail screens. Rows saved before this migration get 0.0 for
        // these four (never recomputed from their stored ibiSeriesJson) rather than being
        // dropped, same tradeoff MIGRATION_2_3 made for normalizedHrvPercent.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN meanIbiMs REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN sd1Ms REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN sd2Ms REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE measurements ADD COLUMN stressIndex REAL NOT NULL DEFAULT 0.0")
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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Safety net for any schema version this app has never actually
                    // shipped with (e.g. an old dev install pre-dating version 2).
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
