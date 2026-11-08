package com.listentogether

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeepLinkHandlerTest {

    @Test
    fun `parses custom scheme listentogether with roomId and inviteSecret`() {
        val uri = "listentogether://join?roomId=room-1234&inviteSecret=secret-abcdef"
        val request = DeepLinkHandler.parse(uri)

        assertNotNull(request)
        assertEquals("room-1234", request?.roomId)
        assertEquals("secret-abcdef", request?.inviteSecret)
    }

    @Test
    fun `parses custom scheme with alternate parameter names room and secret`() {
        val uri = "listentogether://join?room=room-9999&secret=sec-7777"
        val request = DeepLinkHandler.parse(uri)

        assertNotNull(request)
        assertEquals("room-9999", request?.roomId)
        assertEquals("sec-7777", request?.inviteSecret)
    }

    @Test
    fun `parses https app link with query parameters`() {
        val uri = "https://listentogether.app/join?roomId=alpha-room&inviteSecret=key-101"
        val request = DeepLinkHandler.parse(uri)

        assertNotNull(request)
        assertEquals("alpha-room", request?.roomId)
        assertEquals("key-101", request?.inviteSecret)
    }

    @Test
    fun `parses https app link with path segment roomId`() {
        val uri = "https://listentogether.app/join/beta-room?secret=key-202"
        val request = DeepLinkHandler.parse(uri)

        assertNotNull(request)
        assertEquals("beta-room", request?.roomId)
        assertEquals("key-202", request?.inviteSecret)
    }

    @Test
    fun `rejects invalid or missing query parameters`() {
        assertNull(DeepLinkHandler.parse("listentogether://join?roomId=room-only"))
        assertNull(DeepLinkHandler.parse("listentogether://join?inviteSecret=secret-only"))
        assertNull(DeepLinkHandler.parse("https://unknown.com/join?roomId=1&secret=2"))
        assertNull(DeepLinkHandler.parse("listentogether://other?roomId=1&secret=2"))
        assertNull(DeepLinkHandler.parse(null as String?))
        assertNull(DeepLinkHandler.parse(""))
    }
}
