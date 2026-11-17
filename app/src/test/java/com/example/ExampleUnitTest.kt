package com.example

import com.wristscroll.remote.shared.Protocol
import org.junit.Assert.assertEquals
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
    fun commandBus_recordsLogsAndMaintainsCapacity() {
        CommandBus.clearLog()
        CommandBus.recordLog(
            command = Protocol.CMD_NEXT_ITEM,
            source = "Watch",
            status = "DISPATCHED (4ms)",
            isSuccess = true,
            detail = "App: com.instagram.android"
        )
        val logs = CommandBus.commandLog.value
        assertEquals(1, logs.size)
        assertEquals(Protocol.CMD_NEXT_ITEM, logs.first().command)
    }
}
