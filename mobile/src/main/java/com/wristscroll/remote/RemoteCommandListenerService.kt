package com.wristscroll.remote

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wristscroll.remote.shared.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives incoming Data Layer messages from the Wear OS watch under /wristscroll.
 * - Answers /wristscroll/ping with /wristscroll/pong
 * - Routes /wristscroll/command to WristScrollAccessibilityService via CommandBus,
 *   or replies SERVICE_DISABLED on /wristscroll/error if the service is not running.
 */
class RemoteCommandListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        val sourceNodeId = messageEvent.sourceNodeId

        when (messageEvent.path) {
            Protocol.PATH_PING -> {
                serviceScope.launch {
                    runCatching {
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(sourceNodeId, Protocol.PATH_PONG, ByteArray(0))
                            .await()
                    }
                }
            }

            Protocol.PATH_COMMAND -> {
                val command = Protocol.decodePayload(messageEvent.data)
                if (!Protocol.VALID_COMMANDS.contains(command)) {
                    return
                }

                if (!CommandBus.isServiceConnected.value) {
                    CommandBus.recordLog(
                        command = command,
                        source = "Watch",
                        status = Protocol.ERR_SERVICE_DISABLED,
                        isSuccess = false,
                        detail = "Accessibility service is not connected"
                    )
                    serviceScope.launch {
                        runCatching {
                            Wearable.getMessageClient(applicationContext)
                                .sendMessage(
                                    sourceNodeId,
                                    Protocol.PATH_ERROR,
                                    Protocol.encodePayload(Protocol.ERR_SERVICE_DISABLED)
                                )
                                .await()
                        }
                    }
                    return
                }

                CommandBus.emitCommand(
                    IncomingCommand(
                        command = command,
                        sourceNodeId = sourceNodeId,
                        sourceLabel = "Watch"
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
