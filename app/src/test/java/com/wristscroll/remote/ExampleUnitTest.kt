package com.wristscroll.remote

import com.wristscroll.remote.shared.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun protocolConstantsAndPayloads_areConsistent() {
        for (cmd in Protocol.VALID_COMMANDS) {
            val encoded = Protocol.encodePayload(cmd)
            val decoded = Protocol.decodePayload(encoded)
            assertEquals(cmd, decoded)
        }
        assertTrue(Protocol.ALLOWED_PACKAGES.contains("com.zhiliaoapp.musically"))
        assertTrue(Protocol.ALLOWED_PACKAGES.contains("com.ss.android.ugc.trill"))
        assertTrue(Protocol.ALLOWED_PACKAGES.contains("com.instagram.android"))
        assertTrue(Protocol.ALLOWED_PACKAGES.contains("com.google.android.youtube"))
        assertEquals(4, Protocol.ALLOWED_PACKAGES.size)
    }

    @Test
    fun commandBus_dropsAndLogsWhenServiceNotConnected() {
        CommandBus.clearLog()
        CommandBus.setServiceConnected(false)

        val emitted = CommandBus.emitCommand(
            IncomingCommand(
                command = Protocol.CMD_NEXT_ITEM,
                sourceNodeId = null,
                sourceLabel = "Test Pad"
            )
        )
        assertFalse(emitted)
        val logs = CommandBus.commandLog.value
        assertEquals(1, logs.size)
        assertEquals(Protocol.ERR_SERVICE_DISABLED, logs.first().status)
    }
}
