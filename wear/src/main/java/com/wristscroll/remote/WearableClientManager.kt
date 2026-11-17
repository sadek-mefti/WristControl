package com.wristscroll.remote

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.wristscroll.remote.shared.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class WatchConnectionState(
    val connectedNodeId: String? = null,
    val connectedNodeName: String? = null,
    val isNearby: Boolean = false,
    val lastError: String? = null
) {
    val isConnected: Boolean
        get() = connectedNodeId != null
}

/**
 * Manages Wearable Data Layer discovery, live MessageClient listeners,
 * command sending, and heartbeat pings on the watch.
 */
class WearableClientManager(
    context: Context,
    private val hapticController: HapticController
) : MessageClient.OnMessageReceivedListener, CapabilityClient.OnCapabilityChangedListener {

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(WatchConnectionState())
    val connectionState: StateFlow<WatchConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var pendingPongDeferred: CompletableDeferred<Boolean>? = null

    fun startListening() {
        runCatching {
            messageClient.addListener(this)
            capabilityClient.addListener(this, Protocol.CAPABILITY_PHONE_RECEIVER)
        }
        refreshPhoneNode()
    }

    fun stopListening() {
        runCatching {
            messageClient.removeListener(this)
            capabilityClient.removeListener(this, Protocol.CAPABILITY_PHONE_RECEIVER)
        }
    }

    fun refreshPhoneNode() {
        scope.launch {
            try {
                val capabilityInfo = capabilityClient
                    .getCapability(
                        Protocol.CAPABILITY_PHONE_RECEIVER,
                        CapabilityClient.FILTER_REACHABLE
                    )
                    .await()
                updateNodeFromCapability(capabilityInfo.nodes)
            } catch (_: Exception) {
                _connectionState.update {
                    it.copy(
                        connectedNodeId = null,
                        connectedNodeName = null,
                        isNearby = false
                    )
                }
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        updateNodeFromCapability(capabilityInfo.nodes)
    }

    private fun updateNodeFromCapability(nodes: Set<Node>) {
        // Prefer a nearby node if available
        val bestNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        _connectionState.update { current ->
            current.copy(
                connectedNodeId = bestNode?.id,
                connectedNodeName = bestNode?.displayName,
                isNearby = bestNode?.isNearby == true
            )
        }
    }

    /**
     * Sends a protocol command to the reachable phone node.
     * Plays the success haptic pattern only after sendMessage succeeds.
     * Plays the error haptic pattern if there is no reachable phone or sendMessage fails.
     */
    fun sendCommand(command: String) {
        scope.launch {
            val nodeId = _connectionState.value.connectedNodeId ?: run {
                // Attempt an immediate discovery lookup before failing
                val discovered = try {
                    val info = capabilityClient
                        .getCapability(
                            Protocol.CAPABILITY_PHONE_RECEIVER,
                            CapabilityClient.FILTER_REACHABLE
                        )
                        .await()
                    updateNodeFromCapability(info.nodes)
                    _connectionState.value.connectedNodeId
                } catch (_: Exception) {
                    null
                }
                discovered
            }

            if (nodeId == null) {
                hapticController.playError()
                return@launch
            }

            try {
                messageClient
                    .sendMessage(
                        nodeId,
                        Protocol.PATH_COMMAND,
                        Protocol.encodePayload(command)
                    )
                    .await()

                // Play success haptic only after sendMessage succeeds
                if (command == Protocol.CMD_DOUBLE_TAP_LIKE) {
                    hapticController.playDoubleTapLike()
                } else {
                    hapticController.playCommandSent()
                }
            } catch (_: Exception) {
                hapticController.playError()
                refreshPhoneNode()
            }
        }
    }

    /**
     * Sends an empty /wristscroll/ping message to the phone and awaits /wristscroll/pong
     * within Protocol.PING_TIMEOUT_MS (2000 ms).
     */
    suspend fun pingPhone(): Boolean {
        val nodeId = _connectionState.value.connectedNodeId ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pendingPongDeferred = deferred
        return try {
            messageClient.sendMessage(nodeId, Protocol.PATH_PING, ByteArray(0)).await()
            withTimeoutOrNull(Protocol.PING_TIMEOUT_MS) {
                deferred.await()
            } ?: false
        } catch (_: Exception) {
            false
        } finally {
            pendingPongDeferred = null
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            Protocol.PATH_ERROR -> {
                val errorCode = Protocol.decodePayload(messageEvent.data)
                _connectionState.update { it.copy(lastError = errorCode) }
                hapticController.playError()
            }

            Protocol.PATH_PONG -> {
                pendingPongDeferred?.complete(true)
            }

            Protocol.PATH_PING -> {
                val sourceNodeId = messageEvent.sourceNodeId
                scope.launch {
                    runCatching {
                        messageClient.sendMessage(sourceNodeId, Protocol.PATH_PONG, ByteArray(0)).await()
                    }
                }
            }
        }
    }
}
