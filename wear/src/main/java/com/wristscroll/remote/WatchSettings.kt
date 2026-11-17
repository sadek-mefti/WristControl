package com.wristscroll.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.watchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wristscroll_watch_settings"
)

/**
 * All three rotary sensitivity thresholds (in scroll pixels) kept together in one place
 * so they can be easily tuned on a Galaxy Watch bezel or Pixel Watch crown.
 */
object RotaryThresholds {
    const val HIGH_PX: Float = 24f
    const val MEDIUM_PX: Float = 48f
    const val LOW_PX: Float = 80f
}

enum class RotaryMode {
    FEED,
    CAROUSEL
}

enum class RotarySensitivity(val thresholdPx: Float, val label: String) {
    LOW(RotaryThresholds.LOW_PX, "Low"),
    MEDIUM(RotaryThresholds.MEDIUM_PX, "Med"),
    HIGH(RotaryThresholds.HIGH_PX, "High")
}

data class WatchPreferencesState(
    val rotaryMode: RotaryMode = RotaryMode.FEED,
    val sensitivity: RotarySensitivity = RotarySensitivity.MEDIUM
)

class WatchSettings(private val context: Context) {

    private object Keys {
        val ROTARY_MODE = stringPreferencesKey("rotary_mode")
        val ROTARY_SENSITIVITY = stringPreferencesKey("rotary_sensitivity")
    }

    val state: Flow<WatchPreferencesState> = context.watchDataStore.data.map { prefs ->
        val mode = prefs[Keys.ROTARY_MODE]?.let { raw ->
            runCatching { RotaryMode.valueOf(raw) }.getOrNull()
        } ?: RotaryMode.FEED

        val sensitivity = prefs[Keys.ROTARY_SENSITIVITY]?.let { raw ->
            runCatching { RotarySensitivity.valueOf(raw) }.getOrNull()
        } ?: RotarySensitivity.MEDIUM

        WatchPreferencesState(
            rotaryMode = mode,
            sensitivity = sensitivity
        )
    }

    suspend fun setRotaryMode(mode: RotaryMode) {
        context.watchDataStore.edit { prefs ->
            prefs[Keys.ROTARY_MODE] = mode.name
        }
    }

    suspend fun setSensitivity(sensitivity: RotarySensitivity) {
        context.watchDataStore.edit { prefs ->
            prefs[Keys.ROTARY_SENSITIVITY] = sensitivity.name
        }
    }
}
