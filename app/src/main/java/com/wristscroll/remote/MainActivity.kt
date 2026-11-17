package com.wristscroll.remote

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.wristscroll.remote.shared.Protocol
import com.wristscroll.remote.ui.theme.AmberWarn
import com.wristscroll.remote.ui.theme.CardCarbon
import com.wristscroll.remote.ui.theme.CrimsonAlert
import com.wristscroll.remote.ui.theme.ElectricLime
import com.wristscroll.remote.ui.theme.JetBrainsMonoFontFamily
import com.wristscroll.remote.ui.theme.OledBlack
import com.wristscroll.remote.ui.theme.SurfaceSlate
import com.wristscroll.remote.ui.theme.TacticalCyan
import com.wristscroll.remote.ui.theme.TextPrimary
import com.wristscroll.remote.ui.theme.TextSecondary
import com.wristscroll.remote.ui.theme.WristScrollTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private lateinit var gesturePreferences: GesturePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        gesturePreferences = GesturePreferences(applicationContext)

        setContent {
            WristScrollTheme {
                WristScrollCompanionApp(gesturePreferences = gesturePreferences)
            }
        }
    }
}

@Composable
fun WristScrollCompanionApp(gesturePreferences: GesturePreferences) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val settings by gesturePreferences.state.collectAsState(initial = GestureSettingsState())
    val isServiceConnected by CommandBus.isServiceConnected.collectAsState()
    val commandLogs by CommandBus.commandLog.collectAsState()

    var showWatchSimulator by remember { mutableStateOf(false) }

    var isAccessibilityEnabledInSystem by remember {
        mutableStateOf(checkAccessibilityServiceEnabled(context))
    }

    // Recheck accessibility service status every time the screen resumes
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabledInSystem = checkAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Watch discovery & ping state
    var connectedWatchName by remember { mutableStateOf<String?>(null) }
    var connectedWatchId by remember { mutableStateOf<String?>(null) }
    var pingStatusText by remember { mutableStateOf("Idle") }
    var lastOneWayLatencyMs by remember { mutableStateOf<Long?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    var pendingPong by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val capabilityClient = Wearable.getCapabilityClient(appContext)
        val messageClient = Wearable.getMessageClient(appContext)

        fun updateWatchNodes(nodes: Set<Node>) {
            val best = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            connectedWatchId = best?.id
            connectedWatchName = best?.displayName
        }

        val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info: CapabilityInfo ->
            updateWatchNodes(info.nodes)
        }

        val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == Protocol.PATH_PONG) {
                pendingPong?.complete(true)
            }
        }

        runCatching {
            capabilityClient.addListener(capabilityListener, Protocol.CAPABILITY_WATCH_REMOTE)
            messageClient.addListener(messageListener)
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                val info = capabilityClient
                    .getCapability(
                        Protocol.CAPABILITY_WATCH_REMOTE,
                        CapabilityClient.FILTER_REACHABLE
                    )
                    .await()
                withContext(Dispatchers.Main) {
                    updateWatchNodes(info.nodes)
                }
            }
        }

        onDispose {
            runCatching {
                capabilityClient.removeListener(capabilityListener, Protocol.CAPABILITY_WATCH_REMOTE)
                messageClient.removeListener(messageListener)
            }
        }
    }

    // 3-second countdown state for the Gesture Test Pad
    var countdownSecondsLeft by remember { mutableIntStateOf(0) }
    var countdownCommand by remember { mutableStateOf<String?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Requirement 3.2: Only emit when the accessibility service is actually connected
     * (CommandBus.isServiceConnected). CommandBus.emitCommand logs any dropped emit.
     */
    fun dispatchCommandThroughBus(command: String, sourceLabel: String) {
        CommandBus.emitCommand(
            IncomingCommand(
                command = command,
                sourceNodeId = null,
                sourceLabel = sourceLabel
            )
        )
    }

    fun triggerCountdownTest(command: String) {
        countdownJob?.cancel()
        countdownCommand = command
        countdownJob = scope.launch {
            for (sec in 3 downTo 1) {
                countdownSecondsLeft = sec
                delay(1_000L)
            }
            countdownSecondsLeft = 0
            countdownCommand = null
            dispatchCommandThroughBus(command, "Test Pad")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = OledBlack
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header + Interactive Preview Toggle
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "WRISTSCROLL",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.2.sp,
                                    color = ElectricLime
                                )
                                Text(
                                    text = "Eyes-Free Wear OS Gym Feed Remote",
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                            Surface(
                                color = SurfaceSlate,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ElectricLime.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "com.wristscroll.remote",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = ElectricLime,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardCarbon, RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { showWatchSimulator = false },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("tab_phone_companion"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!showWatchSimulator) ElectricLime else CardCarbon,
                                    contentColor = if (!showWatchSimulator) OledBlack else TextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Phone Companion", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { showWatchSimulator = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("tab_watch_simulator"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (showWatchSimulator) TacticalCyan else CardCarbon,
                                    contentColor = if (showWatchSimulator) OledBlack else TextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Watch,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Watch Preview", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (showWatchSimulator) {
                    item {
                        WatchRemoteInteractiveSimulator(
                            isServiceConnected = isServiceConnected,
                            onDispatchWatchCommand = { cmd ->
                                dispatchCommandThroughBus(cmd, "Watch Sim")
                            }
                        )
                    }
                }

                // 1. Service Status Card (Requirements 3.2 & 3.3)
                item {
                    val isEnabledButNotRunning = isAccessibilityEnabledInSystem && !isServiceConnected
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("service_status_card"),
                        colors = CardDefaults.cardColors(containerColor = CardCarbon),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            when {
                                isServiceConnected -> ElectricLime.copy(alpha = 0.45f)
                                isEnabledButNotRunning -> AmberWarn.copy(alpha = 0.6f)
                                else -> CrimsonAlert.copy(alpha = 0.55f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isServiceConnected) Icons.Default.CheckCircle
                                    else Icons.Default.Warning,
                                    contentDescription = stringResource(R.string.service_card_title),
                                    tint = when {
                                        isServiceConnected -> ElectricLime
                                        isEnabledButNotRunning -> AmberWarn
                                        else -> CrimsonAlert
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.service_card_title),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = TextPrimary
                                    )
                                    val statusMessage = when {
                                        isServiceConnected ->
                                            stringResource(R.string.service_enabled)
                                        isEnabledButNotRunning ->
                                            stringResource(R.string.service_enabled_not_running)
                                        else ->
                                            stringResource(R.string.service_disabled)
                                    }
                                    Text(
                                        text = statusMessage,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            isServiceConnected -> ElectricLime
                                            isEnabledButNotRunning -> AmberWarn
                                            else -> CrimsonAlert
                                        },
                                        modifier = Modifier.testTag("service_status_text")
                                    )
                                }
                            }

                            // Requirement 3.3: Setup help for restricted settings when not enabled
                            if (!isAccessibilityEnabledInSystem && !isServiceConnected) {
                                Surface(
                                    color = SurfaceSlate,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("restricted_settings_help_box")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.restricted_settings_help),
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                ).apply {
                                                    data = Uri.fromParts(
                                                        "package",
                                                        context.packageName,
                                                        null
                                                    )
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("open_app_info_button"),
                                            border = BorderStroke(1.dp, TacticalCyan),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = TacticalCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.open_app_info),
                                                color = TacticalCyan,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { context.startActivity(intent) }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_accessibility_settings_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isServiceConnected) SurfaceSlate else ElectricLime,
                                    contentColor = if (isServiceConnected) TextPrimary else OledBlack
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.open_accessibility_settings),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 2. Watch Connection Card (Requirement 3.4)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_connection_card"),
                        colors = CardDefaults.cardColors(containerColor = CardCarbon),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SurfaceSlate)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Watch,
                                    contentDescription = stringResource(R.string.watch_card_title),
                                    tint = if (connectedWatchId != null) ElectricLime else TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.watch_card_title),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = if (connectedWatchName != null) {
                                            stringResource(R.string.watch_connected, connectedWatchName!!)
                                        } else {
                                            stringResource(R.string.watch_disconnected)
                                        },
                                        fontSize = 13.sp,
                                        color = if (connectedWatchId != null) ElectricLime else CrimsonAlert,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val watchNodeId = connectedWatchId
                                        if (watchNodeId == null) {
                                            pingStatusText = "Timeout (No watch node)"
                                            return@OutlinedButton
                                        }
                                        if (isPinging) return@OutlinedButton
                                        isPinging = true
                                        pingStatusText = "Pinging..."
                                        val deferred = CompletableDeferred<Boolean>()
                                        pendingPong = deferred
                                        scope.launch {
                                            val startMs = SystemClock.uptimeMillis()
                                            val success = try {
                                                withContext(Dispatchers.IO) {
                                                    Wearable.getMessageClient(context.applicationContext)
                                                        .sendMessage(
                                                            watchNodeId,
                                                            Protocol.PATH_PING,
                                                            ByteArray(0)
                                                        )
                                                        .await()
                                                }
                                                withTimeoutOrNull(Protocol.PING_TIMEOUT_MS) {
                                                    deferred.await()
                                                } ?: false
                                            } catch (_: Exception) {
                                                false
                                            } finally {
                                                pendingPong = null
                                                isPinging = false
                                            }
                                            val rtt = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L)
                                            if (success) {
                                                lastOneWayLatencyMs = rtt / 2L
                                                pingStatusText = "Pong OK (${rtt} ms RTT)"
                                            } else {
                                                pingStatusText = "Timeout (> 2000 ms)"
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("test_ping_button"),
                                    border = BorderStroke(1.dp, TacticalCyan),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = TacticalCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.test_ping_button),
                                        color = TacticalCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                color = SurfaceSlate,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Heartbeat (/wristscroll/ping):",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = pingStatusText,
                                            fontSize = 12.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                pingStatusText.startsWith("Pong OK") -> ElectricLime
                                                pingStatusText.startsWith("Timeout") -> CrimsonAlert
                                                else -> TacticalCyan
                                            },
                                            modifier = Modifier.testTag("ping_status_text")
                                        )
                                    }

                                    // Requirement 3.4: Show half the round-trip time from last ping
                                    val oneWayMs = lastOneWayLatencyMs
                                    if (oneWayMs != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.watch_one_way_latency,
                                                oneWayMs
                                            ),
                                            fontSize = 12.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ElectricLime,
                                            modifier = Modifier.testTag("watch_one_way_latency_text")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Gesture Test Pad Card (with 3-second countdown)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gesture_test_pad_card"),
                        colors = CardDefaults.cardColors(containerColor = CardCarbon),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SurfaceSlate)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.test_pad_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Tap a gesture, then switch to TikTok, Reels, or YouTube Shorts during the 3s countdown.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )

                            AnimatedVisibility(visible = countdownSecondsLeft > 0) {
                                Surface(
                                    color = TacticalCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, TacticalCyan),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("countdown_banner")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Dispatching ${countdownCommand ?: ""} in ${countdownSecondsLeft}s...",
                                            color = TacticalCyan,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        TextButton(
                                            onClick = {
                                                countdownJob?.cancel()
                                                countdownSecondsLeft = 0
                                                countdownCommand = null
                                            }
                                        ) {
                                            Text("Cancel", color = CrimsonAlert)
                                        }
                                    }
                                }
                            }

                            // Row 1: Scroll Up / Scroll Down
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GestureTestButton(
                                    label = stringResource(R.string.btn_scroll_up),
                                    icon = Icons.Default.KeyboardArrowUp,
                                    testTag = "test_btn_scroll_up",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_NEXT_ITEM) }
                                )
                                GestureTestButton(
                                    label = stringResource(R.string.btn_scroll_down),
                                    icon = Icons.Default.KeyboardArrowDown,
                                    testTag = "test_btn_scroll_down",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_PREV_ITEM) }
                                )
                            }

                            // Row 2: Carousel Left / Carousel Right
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GestureTestButton(
                                    label = stringResource(R.string.btn_carousel_left),
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    testTag = "test_btn_carousel_left",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_SWIPE_LEFT) }
                                )
                                GestureTestButton(
                                    label = stringResource(R.string.btn_carousel_right),
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    testTag = "test_btn_carousel_right",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_SWIPE_RIGHT) }
                                )
                            }

                            // Row 3: Single Tap / Double-Tap Like
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GestureTestButton(
                                    label = stringResource(R.string.btn_single_tap),
                                    icon = Icons.Default.Pause,
                                    testTag = "test_btn_single_tap",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_SINGLE_TAP_PAUSE) }
                                )
                                GestureTestButton(
                                    label = stringResource(R.string.btn_double_tap_like),
                                    icon = Icons.Default.Favorite,
                                    testTag = "test_btn_double_tap_like",
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerCountdownTest(Protocol.CMD_DOUBLE_TAP_LIKE) }
                                )
                            }
                        }
                    }
                }

                // 4. Tuning Card (Duration D, Travel T, Allow in any app)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tuning_card"),
                        colors = CardDefaults.cardColors(containerColor = CardCarbon),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SurfaceSlate)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tuning_card_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Swipe Duration (D)",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "${settings.swipeDurationMs} ms",
                                        fontSize = 13.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricLime
                                    )
                                }
                                Slider(
                                    value = settings.swipeDurationMs.toFloat(),
                                    onValueChange = { newValue ->
                                        scope.launch {
                                            gesturePreferences.setSwipeDurationMs(newValue.roundToInt())
                                        }
                                    },
                                    valueRange = GesturePreferences.MIN_SWIPE_DURATION_MS.toFloat()..
                                        GesturePreferences.MAX_SWIPE_DURATION_MS.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = ElectricLime,
                                        activeTrackColor = ElectricLime,
                                        inactiveTrackColor = SurfaceSlate
                                    ),
                                    modifier = Modifier.testTag("slider_swipe_duration")
                                )
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Swipe Travel (T)",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "${settings.swipeTravelPercent}% of screen height",
                                        fontSize = 13.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = TacticalCyan
                                    )
                                }
                                Slider(
                                    value = settings.swipeTravelPercent.toFloat(),
                                    onValueChange = { newValue ->
                                        scope.launch {
                                            gesturePreferences.setSwipeTravelPercent(newValue.roundToInt())
                                        }
                                    },
                                    valueRange = GesturePreferences.MIN_SWIPE_TRAVEL_PERCENT.toFloat()..
                                        GesturePreferences.MAX_SWIPE_TRAVEL_PERCENT.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = TacticalCyan,
                                        activeTrackColor = TacticalCyan,
                                        inactiveTrackColor = SurfaceSlate
                                    ),
                                    modifier = Modifier.testTag("slider_swipe_travel")
                                )
                            }

                            HorizontalDivider(color = SurfaceSlate)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.allow_any_app_label),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.allow_any_app_subtitle),
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = settings.allowInAnyApp,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            gesturePreferences.setAllowInAnyApp(checked)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OledBlack,
                                        checkedTrackColor = AmberWarn,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = SurfaceSlate
                                    ),
                                    modifier = Modifier.testTag("switch_allow_any_app")
                                )
                            }

                            Surface(
                                color = SurfaceSlate,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Default Allowlist Packages:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextSecondary
                                    )
                                    Protocol.ALLOWED_PACKAGES.forEach { pkg ->
                                        Text(
                                            text = "• $pkg",
                                            fontSize = 11.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = if (settings.allowInAnyApp) TextSecondary else ElectricLime
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Live Command & Latency Log Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("command_log_card"),
                        colors = CardDefaults.cardColors(containerColor = CardCarbon),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SurfaceSlate)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.log_card_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = TextPrimary
                                )
                                if (commandLogs.isNotEmpty()) {
                                    TextButton(
                                        onClick = { CommandBus.clearLog() },
                                        modifier = Modifier.testTag("clear_log_button")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.clear_log),
                                            color = TacticalCyan,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            if (commandLogs.isEmpty()) {
                                Text(
                                    text = "No commands dispatched yet. Use your watch, the Watch Preview tab, or the Gesture Test Pad above.",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            } else {
                                commandLogs.take(8).forEach { entry ->
                                    Surface(
                                        color = SurfaceSlate,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (entry.isSuccess) ElectricLime
                                                                else CrimsonAlert
                                                            )
                                                    )
                                                    Text(
                                                        text = entry.command,
                                                        fontFamily = JetBrainsMonoFontFamily,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = "[${entry.source}]",
                                                        fontSize = 11.sp,
                                                        color = TacticalCyan
                                                    )
                                                }
                                                Text(
                                                    text = "${entry.timestamp} • ${entry.detail}",
                                                    fontSize = 11.sp,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                text = entry.status,
                                                fontSize = 11.sp,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = if (entry.isSuccess) ElectricLime else CrimsonAlert
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureTestButton(
    label: String,
    icon: ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .testTag(testTag),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceSlate,
            contentColor = TextPrimary
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = ElectricLime,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent = ComponentName(context, WristScrollAccessibilityService::class.java)

    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val enabledServices =
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
    if (enabledServices.any {
            val resolveInfo = it.resolveInfo?.serviceInfo
            resolveInfo?.packageName == context.packageName &&
                resolveInfo.name == WristScrollAccessibilityService::class.java.name
        }
    ) {
        return true
    }

    val settingValue = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return settingValue.split(':').any { flat ->
        ComponentName.unflattenFromString(flat) == expectedComponent
    }
}
