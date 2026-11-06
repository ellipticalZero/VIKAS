package com.example.vikas.data.settings

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class AppTheme(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark"), SEPIA("Sepia");

    // SYSTEM becomes Light or Dark depending on the phone's current mode
    fun resolve(systemDark: Boolean): AppTheme =
        if (this == SYSTEM) (if (systemDark) DARK else LIGHT) else this
}

data class Settings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val fontPercent: Int = 115
)

// What the reader screens need, with the theme already resolved
data class ReaderStyle(val theme: AppTheme, val fontPercent: Int)

val LocalReaderStyle = compositionLocalOf { ReaderStyle(AppTheme.LIGHT, 115) }

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val themeKey = stringPreferencesKey("theme")
    private val fontKey = intPreferencesKey("font_percent")

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            theme = runCatching { AppTheme.valueOf(p[themeKey] ?: "SYSTEM") }
                .getOrDefault(AppTheme.SYSTEM),
            fontPercent = p[fontKey] ?: 115
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        store.edit { it[themeKey] = theme.name }
    }

    suspend fun setFontPercent(percent: Int) {
        store.edit { it[fontKey] = percent.coerceIn(80, 200) }
    }
}