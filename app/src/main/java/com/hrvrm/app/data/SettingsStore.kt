package com.hrvrm.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User-entered Intervals.icu credentials and upload preferences, persisted with DataStore. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("intervals_api_key")
        val ATHLETE_ID = stringPreferencesKey("intervals_athlete_id")
        val AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { it[Keys.API_KEY] }
    val athleteId: Flow<String?> = context.dataStore.data.map { it[Keys.ATHLETE_ID] }
    val autoUpload: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPLOAD] ?: true }

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
}
