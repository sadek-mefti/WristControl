package com.wristscroll.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.android.gms.wearable.Wearable
import com.wristscroll.remote.shared.Protocol
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * AccessibilityService that verifies screen state and foreground app allowlist,
 * then dispatches swipe or tap gestures with single-flight queuing.
 */
class WristScrollAccessibilityService : AccessibilityService() {

    companion object {
        private const val SINGLE_TAP_DURATION_MS = 40L
        private const val DOUBLE_TAP_STROKE_DURATION_MS = 35L
        private const val DOUBLE_TAP_SECOND_STROKE_START_MS = 90L
        private const val DOUBLE_TAP_OFFSET_PX = 6f
        private const val MAX_SWIPE_JITTER_PX = 15f

        /**
         * Constant allowlist of foreground app package names allowed to receive gestures.
         */
        val ALLOWED_PACKAGES: Set<String> = Protocol.ALLOWED_PACKAGES
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var gesturePreferences: GesturePreferences

    @Volatile
    private var currentSettings = GestureSettingsState()

    private val queueLock = Any()
    private var gestureInFlight: Boolean = false
    private var pendingCommand: IncomingCommand? = null

    override fun onCreate() {
        super.onCreate()
        gesturePreferences = GesturePreferences(applicationContext)

        serviceScope.launch {
            gesturePreferences.state.collectLatest { settings ->
                currentSettings = settings
            }
        }

        serviceScope.launch {
            CommandBus.commands.collect { incoming ->
                enqueueOrRunCommand(incoming)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CommandBus.setServiceConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Foreground package is read on-demand via rootInActiveWindow?.packageName at dispatch time.
    }

    override fun onInterrupt() {
        synchronized(queueLock) {
            gestureInFlight = false
            pendingCommand = null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CommandBus.setServiceConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CommandBus.setServiceConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun enqueueOrRunCommand(incoming: IncomingCommand) {
        val shouldStartNow: Boolean
        synchronized(queueLock) {
            if (gestureInFlight) {
                // Keep at most one pending command (the most recent) and drop older ones
                pendingCommand = incoming
                shouldStartNow = false
            } else {
                gestureInFlight = true
                shouldStartNow = true
            }
        }

        if (shouldStartNow) {
            executeCommand(incoming)
        }
    }

    private fun onGestureFinished() {
        val nextToRun: IncomingCommand?
        synchronized(queueLock) {
            nextToRun = pendingCommand
            pendingCommand = null
            if (nextToRun == null) {
                gestureInFlight = false
            }
        }

        if (nextToRun != null) {
            executeCommand(nextToRun)
        }
    }

    private fun executeCommand(incoming: IncomingCommand) {
        // 1. Check that the screen is on and the phone is unlocked
        if (!isPhoneReady()) {
            CommandBus.recordLog(
                command = incoming.command,
                source = incoming.sourceLabel,
                status = Protocol.ERR_PHONE_NOT_READY,
                isSuccess = false,
                detail = "Screen off or device locked"
            )
            sendErrorToWatch(incoming.sourceNodeId, Protocol.ERR_PHONE_NOT_READY)
            onGestureFinished()
            return
        }

        // 2. Check that the foreground app is allowed (read only rootInActiveWindow?.packageName)
        val settings = currentSettings
        val activePackageName = rootInActiveWindow?.packageName?.toString()
        if (!settings.allowInAnyApp) {
            if (activePackageName == null || !ALLOWED_PACKAGES.contains(activePackageName)) {
                CommandBus.recordLog(
                    command = incoming.command,
                    source = incoming.sourceLabel,
                    status = Protocol.ERR_APP_NOT_ALLOWED,
                    isSuccess = false,
                    detail = "Blocked package: ${activePackageName ?: "unknown"}"
                )
                sendErrorToWatch(incoming.sourceNodeId, Protocol.ERR_APP_NOT_ALLOWED)
                onGestureFinished()
                return
            }
        }

        val (width, height) = readScreenDimensions()
        if (width <= 0f || height <= 0f) {
            onGestureFinished()
            return
        }

        val gesture = buildGestureDescription(
            command = incoming.command,
            w = width,
            h = height,
            settings = settings
        )

        if (gesture == null) {
            onGestureFinished()
            return
        }

        val dispatchLatencyMs = (System.currentTimeMillis() - incoming.receivedAtMs).coerceAtLeast(0L)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    CommandBus.recordLog(
                        command = incoming.command,
                        source = incoming.sourceLabel,
                        status = "DISPATCHED (${dispatchLatencyMs}ms)",
                        isSuccess = true,
                        detail = "App: ${activePackageName ?: "any"}"
                    )
                    onGestureFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // Treat onCancelled the same as onCompleted when deciding to run pending command
                    CommandBus.recordLog(
                        command = incoming.command,
                        source = incoming.sourceLabel,
                        status = "CANCELLED",
                        isSuccess = false,
                        detail = "App: ${activePackageName ?: "any"}"
                    )
                    onGestureFinished()
                }
            },
            null
        )

        if (!dispatched) {
            onGestureFinished()
        }
    }

    private fun isPhoneReady(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = powerManager?.isInteractive == true
        val unlocked = keyguardManager?.isKeyguardLocked == false
        return interactive && unlocked
    }

    private fun readScreenDimensions(): Pair<Float, Float> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 0f to 0f

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().toFloat() to bounds.height().toFloat()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
        }
    }

    private fun buildGestureDescription(
        command: String,
        w: Float,
        h: Float,
        settings: GestureSettingsState
    ): GestureDescription? {
        val durationMs = settings.swipeDurationMs.toLong()
        val travelPx = (settings.swipeTravelPercent / 100f) * h

        return when (command) {
            Protocol.CMD_NEXT_ITEM -> {
                // From (50% w, 50% h + T/2) to (48% w, 50% h - T/2) over D
                buildSwipeGesture(
                    startX = 0.50f * w,
                    startY = 0.50f * h + (travelPx / 2f),
                    endX = 0.48f * w,
                    endY = 0.50f * h - (travelPx / 2f),
                    durationMs = durationMs,
                    w = w,
                    h = h
                )
            }

            Protocol.CMD_PREV_ITEM -> {
                // From (50% w, 50% h - T/2) to (52% w, 50% h + T/2) over D
                buildSwipeGesture(
                    startX = 0.50f * w,
                    startY = 0.50f * h - (travelPx / 2f),
                    endX = 0.52f * w,
                    endY = 0.50f * h + (travelPx / 2f),
                    durationMs = durationMs,
                    w = w,
                    h = h
                )
            }

            Protocol.CMD_SWIPE_LEFT -> {
                // From (82% w, 50% h) to (18% w, 50% h) over D
                buildSwipeGesture(
                    startX = 0.82f * w,
                    startY = 0.50f * h,
                    endX = 0.18f * w,
                    endY = 0.50f * h,
                    durationMs = durationMs,
                    w = w,
                    h = h
                )
            }

            Protocol.CMD_SWIPE_RIGHT -> {
                // From (18% w, 50% h) to (82% w, 50% h) over D
                buildSwipeGesture(
                    startX = 0.18f * w,
                    startY = 0.50f * h,
                    endX = 0.82f * w,
                    endY = 0.50f * h,
                    durationMs = durationMs,
                    w = w,
                    h = h
                )
            }

            Protocol.CMD_SINGLE_TAP_PAUSE -> {
                // A 40 ms tap at (50% w, 50% h)
                val cx = (0.50f * w).coerceIn(1f, w - 1f)
                val cy = (0.50f * h).coerceIn(1f, h - 1f)
                val path = Path().apply { moveTo(cx, cy) }
                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0L,
                    SINGLE_TAP_DURATION_MS
                )
                GestureDescription.Builder().addStroke(stroke).build()
            }

            Protocol.CMD_DOUBLE_TAP_LIKE -> {
                // One GestureDescription holding two strokes:
                // 1) 35 ms tap at centre starting at 0 ms
                // 2) 35 ms tap offset by (+6 px, -6 px) starting at 90 ms
                val cx1 = (0.50f * w).coerceIn(1f, w - 1f)
                val cy1 = (0.50f * h).coerceIn(1f, h - 1f)
                val cx2 = (cx1 + DOUBLE_TAP_OFFSET_PX).coerceIn(1f, w - 1f)
                val cy2 = (cy1 - DOUBLE_TAP_OFFSET_PX).coerceIn(1f, h - 1f)

                val path1 = Path().apply { moveTo(cx1, cy1) }
                val path2 = Path().apply { moveTo(cx2, cy2) }

                val stroke1 = GestureDescription.StrokeDescription(
                    path1,
                    0L,
                    DOUBLE_TAP_STROKE_DURATION_MS
                )
                val stroke2 = GestureDescription.StrokeDescription(
                    path2,
                    DOUBLE_TAP_SECOND_STROKE_START_MS,
                    DOUBLE_TAP_STROKE_DURATION_MS
                )

                GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()
            }

            else -> null
        }
    }

    /**
     * Adds random jitter of up to ±15 px to every swipe endpoint,
     * then clamps the point inside the screen.
     */
    private fun buildSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        w: Float,
        h: Float
    ): GestureDescription {
        val clampedStartX = clampX(startX + randomJitter(), w)
        val clampedStartY = clampY(startY + randomJitter(), h)
        val clampedEndX = clampX(endX + randomJitter(), w)
        val clampedEndY = clampY(endY + randomJitter(), h)

        val path = Path().apply {
            moveTo(clampedStartX, clampedStartY)
            lineTo(clampedEndX, clampedEndY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun randomJitter(): Float {
        return (Random.nextFloat() * 2f - 1f) * MAX_SWIPE_JITTER_PX
    }

    private fun clampX(x: Float, w: Float): Float = x.coerceIn(1f, (w - 1f).coerceAtLeast(1f))

    private fun clampY(y: Float, h: Float): Float = y.coerceIn(1f, (h - 1f).coerceAtLeast(1f))

    private fun sendErrorToWatch(sourceNodeId: String?, errorCode: String) {
        if (sourceNodeId == null) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(
                        sourceNodeId,
                        Protocol.PATH_ERROR,
                        Protocol.encodePayload(errorCode)
                    )
                    .await()
            }
        }
    }
}
