package de.aploi.sussurrobyeyed.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sussurro_settings")

/**
 * Thin wrapper around [DataStore] for Sussurro's user preferences.
 *
 * Everything is exposed as Kotlin flows; mutation methods are suspending. The
 * mapping back to [Settings] is intentionally lenient — unknown enum strings
 * fall back to defaults instead of crashing.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            themeMode = prefs[Keys.THEME]?.toThemeMode() ?: Settings().themeMode,
            language = prefs[Keys.LANGUAGE] ?: Settings().language,
            lowercaseOutput = prefs[Keys.LOWERCASE] ?: Settings().lowercaseOutput,
            trimTrailingPunctuation = prefs[Keys.TRIM] ?: Settings().trimTrailingPunctuation,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setLanguage(code: String) = context.dataStore.edit { it[Keys.LANGUAGE] = code }
    suspend fun setLowercaseOutput(value: Boolean) = context.dataStore.edit { it[Keys.LOWERCASE] = value }
    suspend fun setTrimTrailingPunctuation(value: Boolean) = context.dataStore.edit { it[Keys.TRIM] = value }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val LOWERCASE = booleanPreferencesKey("lowercase_output")
        val TRIM = booleanPreferencesKey("trim_trailing_punctuation")
    }
}

private fun String.toThemeMode(): ThemeMode? = ThemeMode.entries.firstOrNull { it.name == this }
