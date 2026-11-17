package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gestureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wristscroll_gesture_preferences"
)

data class GestureSettingsState(
    val swipeDurationMs: Int = GesturePreferences.DEFAULT_SWIPE_DURATION_MS,
    val swipeTravelPercent: Int = GesturePreferences.DEFAULT_SWIPE_TRAVEL_PERCENT,
    val allowInAnyApp: Boolean = false
)

class GesturePreferences(private val context: Context) {

    companion object {
        const val MIN_SWIPE_DURATION_MS = 150
        const val MAX_SWIPE_DURATION_MS = 400
        const val DEFAULT_SWIPE_DURATION_MS = 240

        const val MIN_SWIPE_TRAVEL_PERCENT = 40
        const val MAX_SWIPE_TRAVEL_PERCENT = 70
        const val DEFAULT_SWIPE_TRAVEL_PERCENT = 52
    }

    private object Keys {
        val SWIPE_DURATION_MS = intPreferencesKey("swipe_duration_ms")
        val SWIPE_TRAVEL_PERCENT = intPreferencesKey("swipe_travel_percent")
        val ALLOW_IN_ANY_APP = booleanPreferencesKey("allow_in_any_app")
    }

    val state: Flow<GestureSettingsState> = context.gestureDataStore.data.map { prefs ->
        val duration = (prefs[Keys.SWIPE_DURATION_MS] ?: DEFAULT_SWIPE_DURATION_MS)
            .coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        val travel = (prefs[Keys.SWIPE_TRAVEL_PERCENT] ?: DEFAULT_SWIPE_TRAVEL_PERCENT)
            .coerceIn(MIN_SWIPE_TRAVEL_PERCENT, MAX_SWIPE_TRAVEL_PERCENT)
        val allowAny = prefs[Keys.ALLOW_IN_ANY_APP] ?: false

        GestureSettingsState(
            swipeDurationMs = duration,
            swipeTravelPercent = travel,
            allowInAnyApp = allowAny
        )
    }

    suspend fun setSwipeDurationMs(durationMs: Int) {
        context.gestureDataStore.edit { prefs ->
            prefs[Keys.SWIPE_DURATION_MS] =
                durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        }
    }

    suspend fun setSwipeTravelPercent(travelPercent: Int) {
        context.gestureDataStore.edit { prefs ->
            prefs[Keys.SWIPE_TRAVEL_PERCENT] =
                travelPercent.coerceIn(MIN_SWIPE_TRAVEL_PERCENT, MAX_SWIPE_TRAVEL_PERCENT)
        }
    }

    suspend fun setAllowInAnyApp(allow: Boolean) {
        context.gestureDataStore.edit { prefs ->
            prefs[Keys.ALLOW_IN_ANY_APP] = allow
        }
    }
}
