package com.wristscroll.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import com.wristscroll.remote.shared.Protocol
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_IS_IN_STEALTH_MODE = "key_is_in_stealth_mode"
        private const val KEY_IS_CALIBRATING_ROTARY = "key_is_calibrating_rotary"
    }

    private lateinit var hapticController: HapticController
    private lateinit var wearableClientManager: WearableClientManager
    private lateinit var watchSettings: WatchSettings

    private var isInStealthMode by mutableStateOf(false)
    private var isCalibratingRotary by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hapticController = HapticController(applicationContext)
        wearableClientManager = WearableClientManager(applicationContext, hapticController)
        watchSettings = WatchSettings(applicationContext)

        if (savedInstanceState != null) {
            isInStealthMode = savedInstanceState.getBoolean(KEY_IS_IN_STEALTH_MODE, false)
            isCalibratingRotary = savedInstanceState.getBoolean(KEY_IS_CALIBRATING_ROTARY, false)
        }

        handleLaunchIntent(intent, isInitialCreate = savedInstanceState == null)

        setContent {
            MaterialTheme {
                val connectionState by wearableClientManager.connectionState.collectAsState()
                val preferences by watchSettings.state.collectAsState(
                    initial = WatchPreferencesState()
                )
                val scope = rememberCoroutineScope()
                var pingResultText by remember { mutableStateOf<String?>(null) }
                var isPinging by remember { mutableStateOf(false) }

                when {
                    isInStealthMode -> {
                        StealthRemoteScreen(
                            isConnected = connectionState.isConnected,
                            rotaryMode = preferences.rotaryMode,
                            sensitivity = preferences.sensitivity,
                            onSendCommand = { command ->
                                wearableClientManager.sendCommand(command)
                            },
                            onExitStealth = {
                                hapticController.playStealthTransition()
                                isInStealthMode = false
                            }
                        )
                    }

                    isCalibratingRotary -> {
                        RotaryCalibrationScreen(
                            onExit = {
                                isCalibratingRotary = false
                            }
                        )
                    }

                    else -> {
                        DashboardScreen(
                            connectionState = connectionState,
                            preferences = preferences,
                            pingResultText = pingResultText,
                            isPinging = isPinging,
                            onToggleRotaryMode = {
                                val nextMode = when (preferences.rotaryMode) {
                                    RotaryMode.FEED -> RotaryMode.CAROUSEL
                                    RotaryMode.CAROUSEL -> RotaryMode.FEED
                                }
                                lifecycleScope.launch {
                                    watchSettings.setRotaryMode(nextMode)
                                }
                            },
                            onSelectSensitivity = { selected ->
                                lifecycleScope.launch {
                                    watchSettings.setSensitivity(selected)
                                }
                            },
                            onEnterStealth = {
                                hapticController.playStealthTransition()
                                isInStealthMode = true
                            },
                            onPingPhone = {
                                isPinging = true
                                pingResultText = "..."
                                scope.launch {
                                    val rttMs = wearableClientManager.pingPhoneRoundTripMs()
                                    pingResultText = if (rttMs != null) {
                                        "$rttMs ms"
                                    } else {
                                        getString(R.string.ping_no_reply)
                                    }
                                    isPinging = false
                                }
                            },
                            onOpenCalibration = {
                                isCalibratingRotary = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_IN_STEALTH_MODE, isInStealthMode)
        outState.putBoolean(KEY_IS_CALIBRATING_ROTARY, isCalibratingRotary)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, isInitialCreate = false)
    }

    private fun handleLaunchIntent(intent: Intent?, isInitialCreate: Boolean) {
        val startInStealth = intent?.getBooleanExtra(Protocol.EXTRA_START_IN_STEALTH, false) == true
        if (startInStealth) {
            if (!isInStealthMode || isInitialCreate) {
                hapticController.playStealthTransition()
            }
            isInStealthMode = true
            isCalibratingRotary = false
            intent?.removeExtra(Protocol.EXTRA_START_IN_STEALTH)
        }
    }

    override fun onStart() {
        super.onStart()
        wearableClientManager.startListening()
    }

    override fun onStop() {
        wearableClientManager.stopListening()
        super.onStop()
    }
}
