package com.wristscroll.remote

import com.wristscroll.remote.shared.Protocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IncomingCommand(
    val command: String,
    val sourceNodeId: String? = null,
    val sourceLabel: String = if (sourceNodeId != null) "Watch" else "Test Pad",
    val receivedAtMs: Long = System.currentTimeMillis()
)

data class CommandLogEntry(
    val id: Long,
    val timestamp: String,
    val command: String,
    val source: String,
    val status: String,
    val isSuccess: Boolean,
    val detail: String
)

/**
 * Singleton command bus connecting RemoteCommandListenerService and the Phone Test Pad
 * to WristScrollAccessibilityService using a MutableSharedFlow with a buffer of one
 * and BufferOverflow.DROP_OLDEST.
 */
object CommandBus {

    private var nextLogId = 1L

    private val _commands = MutableSharedFlow<IncomingCommand>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: SharedFlow<IncomingCommand> = _commands.asSharedFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _commandLog = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    val commandLog: StateFlow<List<CommandLogEntry>> = _commandLog.asStateFlow()

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
    }

    /**
     * Emits a command only when the AccessibilityService is actually connected.
     * If an emit is dropped for any reason, writes a log entry saying so.
     */
    fun emitCommand(incoming: IncomingCommand): Boolean {
        if (!_isServiceConnected.value) {
            recordLog(
                command = incoming.command,
                source = incoming.sourceLabel,
                status = Protocol.ERR_SERVICE_DISABLED,
                isSuccess = false,
                detail = "Emit dropped: Accessibility service is not connected"
            )
            return false
        }

        val emitted = _commands.tryEmit(incoming)
        if (!emitted) {
            recordLog(
                command = incoming.command,
                source = incoming.sourceLabel,
                status = "DROPPED",
                isSuccess = false,
                detail = "Emit dropped: SharedFlow buffer rejected command"
            )
        }
        return emitted
    }

    fun recordLog(
        command: String,
        source: String,
        status: String,
        isSuccess: Boolean,
        detail: String
    ) {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val entry = CommandLogEntry(
            id = nextLogId++,
            timestamp = formatter.format(Date()),
            command = command,
            source = source,
            status = status,
            isSuccess = isSuccess,
            detail = detail
        )
        _commandLog.update { current ->
            (listOf(entry) + current).take(30)
        }
    }

    fun clearLog() {
        _commandLog.value = emptyList()
    }
}
