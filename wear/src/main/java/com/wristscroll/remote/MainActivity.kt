package com.wristscroll.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import com.wristscroll.remote.shared.Protocol
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var hapticController: HapticController
    private lateinit var wearableClientManager: WearableClientManager
    private lateinit var watchSettings: WatchSettings

    private var isInStealthMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hapticController = HapticController(applicationContext)
        wearableClientManager = WearableClientManager(applicationContext, hapticController)
        watchSettings = WatchSettings(applicationContext)

        handleLaunchIntent(intent, isInitialCreate = true)

        setContent {
            MaterialTheme {
                val connectionState by wearableClientManager.connectionState.collectAsState()
                val preferences by watchSettings.state.collectAsState(
                    initial = WatchPreferencesState()
                )

                if (isInStealthMode) {
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
                } else {
                    DashboardScreen(
                        connectionState = connectionState,
                        preferences = preferences,
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
                        }
                    )
                }
            }
        }
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
