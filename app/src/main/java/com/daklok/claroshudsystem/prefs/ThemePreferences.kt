package com.daklok.claroshudsystem.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Three theme modes the user can pick in Settings.
 *  - SYSTEM: follow OS / device dark mode setting (default)
 *  - LIGHT:  always light
 *  - DARK:   always dark
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class PuckModel { ARROW, SPORTS_CAR, REGULAR_CAR }

private const val PREFS_FILE = "claros_prefs"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_PUCK_MODEL = "puck_model"

private fun Context.prefs(): SharedPreferences =
    getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

/**
 * Reads and writes the user-selected ThemeMode using SharedPreferences.
 * Exposed as a StateFlow so Compose recomposes immediately when the user
 * changes it from the Settings screen.
 */
object ThemePreferences {

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _puckModel = MutableStateFlow(PuckModel.ARROW)
    val puckModel: StateFlow<PuckModel> = _puckModel.asStateFlow()

    private var initialized = false

    /** Read the persisted mode once on app startup. Safe to call multiple times. */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        
        val p = context.prefs()
        
        val rawTheme = p.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        _mode.value = runCatching { ThemeMode.valueOf(rawTheme ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

        val rawPuck = p.getString(KEY_PUCK_MODEL, PuckModel.ARROW.name)
        _puckModel.value = runCatching { PuckModel.valueOf(rawPuck ?: PuckModel.ARROW.name) }
            .getOrDefault(PuckModel.ARROW)
    }

    fun setMode(context: Context, newMode: ThemeMode) {
        _mode.value = newMode
        context.prefs().edit().putString(KEY_THEME_MODE, newMode.name).apply()
    }

    fun setPuckModel(context: Context, newModel: PuckModel) {
        _puckModel.value = newModel
        context.prefs().edit().putString(KEY_PUCK_MODEL, newModel.name).apply()
    }
}
