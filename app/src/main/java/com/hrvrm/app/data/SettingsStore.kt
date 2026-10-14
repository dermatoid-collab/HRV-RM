package com.hrvrm.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hrvrm.app.ui.theme.AutoThemeMode
import com.hrvrm.app.ui.theme.ThemeMode
import com.hrvrm.app.ui.theme.randomThemeMode
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User-entered Intervals.icu credentials and upload preferences, persisted with DataStore. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("intervals_api_key")
        val ATHLETE_ID = stringPreferencesKey("intervals_athlete_id")
        val AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val KEEP_RAW_DATA = booleanPreferencesKey("keep_raw_data")
        val HRV_SCORE_BACKFILL_V8_DONE = booleanPreferencesKey("hrv_score_backfill_v8_done")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_THEME_MODE = stringPreferencesKey("auto_theme_mode")
        /** Last calendar day (yyyy-MM-dd, device-local) a DAILY auto-theme roll happened — see [rollDailyThemeIfDue]. */
        val AUTO_THEME_LAST_DAY = stringPreferencesKey("auto_theme_last_day")
        val MEASUREMENT_REMINDER_ENABLED = booleanPreferencesKey("measurement_reminder_enabled")
        val MEASUREMENT_REMINDER_HOUR = intPreferencesKey("measurement_reminder_hour")
        val MEASUREMENT_REMINDER_MINUTE = intPreferencesKey("measurement_reminder_minute")
        val BATTERY_OPTIMIZATION_PROMPT_SHOWN = booleanPreferencesKey("battery_optimization_prompt_shown")
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { it[Keys.API_KEY] }
    val athleteId: Flow<String?> = context.dataStore.data.map { it[Keys.ATHLETE_ID] }
    val autoUpload: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPLOAD] ?: true }

    /** Content-tree URI of the user-chosen folder [com.hrvrm.app.backup.FolderSync] mirrors to, or null if unset. */
    val backupFolderUri: Flow<String?> = context.dataStore.data.map { it[Keys.BACKUP_FOLDER_URI] }

    /** Off by default — see [com.hrvrm.app.backup.RawSampleStorage] for why this isn't free. */
    val keepRawData: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_RAW_DATA] ?: false }

    /** See [com.hrvrm.app.data.MeasurementRepository.backfillHrvScoresIfNeeded]. */
    val hrvScoreBackfillV8Done: Flow<Boolean> = context.dataStore.data.map { it[Keys.HRV_SCORE_BACKFILL_V8_DONE] ?: false }

    /** Which colour palette the app renders in — see [com.hrvrm.app.ui.theme.ThemeMode]. Defaults to the app's original look so existing installs don't change appearance until the user picks a new one. */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() } ?: ThemeMode.HRV_RM_LEGACY
    }

    /** Off by default -- manual palette selection (above) keeps working exactly as before until the user turns this on. */
    val autoThemeMode: Flow<AutoThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_THEME_MODE]?.let { name -> runCatching { AutoThemeMode.valueOf(name) }.getOrNull() } ?: AutoThemeMode.OFF
    }

    /** On by default (05:00) -- see [com.hrvrm.app.reminder.MeasurementReminderWorker]. */
    val measurementReminderEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MEASUREMENT_REMINDER_ENABLED] ?: true }
    val measurementReminderHour: Flow<Int> = context.dataStore.data.map { it[Keys.MEASUREMENT_REMINDER_HOUR] ?: 5 }
    val measurementReminderMinute: Flow<Int> = context.dataStore.data.map { it[Keys.MEASUREMENT_REMINDER_MINUTE] ?: 0 }

    /** Whether the one-time "disable battery optimization" prompt (see MainActivity) has already been shown/dismissed. */
    val batteryOptimizationPromptShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BATTERY_OPTIMIZATION_PROMPT_SHOWN] ?: false }

    suspend fun setCredentials(apiKey: String, athleteId: String) {
        // Stored exactly as entered (just trimmed) — no "i" prefix guessing. ERG-RM,
        // which authenticates against the same API successfully, does the same: it
        // trusts the value Intervals.icu itself shows the athlete on their own profile.
        context.dataStore.edit { prefs ->
            prefs[Keys.API_KEY] = apiKey.trim()
            prefs[Keys.ATHLETE_ID] = athleteId.trim()
        }
    }

    suspend fun setAutoUpload(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_UPLOAD] = enabled }
    }

    suspend fun setBackupFolderUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.BACKUP_FOLDER_URI) else prefs[Keys.BACKUP_FOLDER_URI] = uri
        }
    }

    suspend fun setKeepRawData(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.KEEP_RAW_DATA] = enabled }
    }

    suspend fun setHrvScoreBackfillV8Done(done: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.HRV_SCORE_BACKFILL_V8_DONE] = done }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAutoThemeMode(mode: AutoThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_THEME_MODE] = mode.name }
    }

    /**
     * Rolls a new random palette once per calendar day when [autoThemeMode] is
     * [AutoThemeMode.DAILY] -- a no-op the rest of the day, and whenever auto-theme is off or
     * set to roll per-measurement instead. Call on every app start (see HrvRmApp); cheap when
     * it's not due since it only reads preferences.
     */
    suspend fun rollDailyThemeIfDue() {
        if (autoThemeMode.first() != AutoThemeMode.DAILY) return
        val today = LocalDate.now().toString()
        if (context.dataStore.data.first()[Keys.AUTO_THEME_LAST_DAY] == today) return
        val next = randomThemeMode(themeMode.first())
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = next.name
            prefs[Keys.AUTO_THEME_LAST_DAY] = today
        }
    }

    /**
     * Rolls a new random palette when [autoThemeMode] is [AutoThemeMode.PER_MEASUREMENT] -- a
     * no-op otherwise. Call right after a measurement is saved (see MeasurementRepository).
     */
    suspend fun rollThemeAfterMeasurement() {
        if (autoThemeMode.first() != AutoThemeMode.PER_MEASUREMENT) return
        setThemeMode(randomThemeMode(themeMode.first()))
    }

    suspend fun setMeasurementReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.MEASUREMENT_REMINDER_ENABLED] = enabled }
    }

    suspend fun setMeasurementReminderTime(hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MEASUREMENT_REMINDER_HOUR] = hour
            prefs[Keys.MEASUREMENT_REMINDER_MINUTE] = minute
        }
    }

    suspend fun setBatteryOptimizationPromptShown(shown: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BATTERY_OPTIMIZATION_PROMPT_SHOWN] = shown }
    }
}
