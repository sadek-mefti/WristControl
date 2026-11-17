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
 * Background WearableListenerService on the watch that replies to /wristscroll/ping
 * with /wristscroll/pong even when the watch UI is closed, ensuring the phone's
 * Test Ping button always works.
 */
class PingListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path == Protocol.PATH_PING) {
            val sourceNodeId = messageEvent.sourceNodeId
            serviceScope.launch {
                runCatching {
                    Wearable.getMessageClient(applicationContext)
                        .sendMessage(sourceNodeId, Protocol.PATH_PONG, ByteArray(0))
                        .await()
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
