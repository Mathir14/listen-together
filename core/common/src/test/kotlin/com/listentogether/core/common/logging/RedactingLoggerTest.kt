package com.listentogether.core.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactingLoggerTest {
    @Test
    fun `sensitive fields are redacted`() {
        val captured = mutableMapOf<String, Map<String, Any?>>()
        val logger = RedactingLogger(sink = { name, fields -> captured[name] = fields })

        logger.event(
            "chat.received",
            mapOf(
                "room_id" to "r1",
                "text" to "secret message",
                "participant_token" to "abc",
                "sdp" to "v=0...",
                "content" to "hello",
            ),
        )

        val fields = captured.getValue("chat.received")
        assertEquals("r1", fields["room_id"])
        assertEquals(RedactingLogger.REDACTED, fields["text"])
        assertEquals(RedactingLogger.REDACTED, fields["participant_token"])
        assertEquals(RedactingLogger.REDACTED, fields["sdp"])
        assertEquals(RedactingLogger.REDACTED, fields["content"])
        assertFalse(fields.values.any { it == "secret message" })
    }

    @Test
    fun `errors do not leak throwable messages`() {
        val captured = mutableMapOf<String, Map<String, Any?>>()
        val logger = RedactingLogger(sink = { name, fields -> captured[name] = fields })
        logger.error("socket.failed", IllegalStateException("token=abc chat=leak"))
        val fields = captured.getValue("socket.failed")
        assertEquals("IllegalStateException", fields["error_type"])
        assertFalse(fields.toString().contains("leak"))
    }
}
