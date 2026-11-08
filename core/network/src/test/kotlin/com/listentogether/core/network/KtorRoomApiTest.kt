package com.listentogether.core.network

import com.listentogether.core.common.AppResult
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import com.listentogether.protocol.ProtocolJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorRoomApiTest {
    private fun api(engine: MockEngine): KtorRoomApi =
        KtorRoomApi(NetworkConfig("https://backend.test/"), engine)

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `create room posts role and parses credentials`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://backend.test/rooms", request.url.toString())
            respond(
                content = """
                    {"room_id":"room-1","participant_id":"user-1","participant_token":"token-1",
                     "role":"LISTENER","invite_secret":"invite-1"}
                """.trimIndent(),
                status = HttpStatusCode.Created,
                headers = jsonHeaders(),
            )
        }
        val api = api(engine)
        val result = api.createRoom(Role.LISTENER)
        val credentials = assertIs<AppResult.Success<*>>(result).value
        assertEquals("room-1", (credentials as com.listentogether.core.model.RoomCredentials).roomId)
        assertEquals(Role.LISTENER, credentials.role)
        val recorded = engine.requestHistory.single()
        assertEquals("https://backend.test/rooms", recorded.url.toString())
        assertTrue(recorded.body.contentType?.match(io.ktor.http.ContentType.Application.Json) == true)
        assertTrue((recorded.body as io.ktor.http.content.TextContent).text.contains("\"role\":\"LISTENER\""))
        api.close()
    }

    @Test
    fun `join failure maps structured api error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"INVALID_INVITE","message":"Invite is not valid"}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders(),
            )
        }
        val api = api(engine)
        val result = api.joinRoom("room-1", "wrong")
        val failure = assertIs<AppResult.Failure>(result)
        assertEquals("INVALID_INVITE", failure.error.code)
        assertEquals("Invite is not valid", failure.error.message)
        assertTrue(!failure.error.retryable)
        api.close()
    }

    @Test
    fun `server errors are retryable even without json body`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val api = api(engine)
        val failure = assertIs<AppResult.Failure>(api.roomState("room-1", "token"))
        assertEquals("HTTP_500", failure.error.code)
        assertTrue(failure.error.retryable)
        api.close()
    }

    @Test
    fun `transport failure becomes network error`() = runTest {
        val engine = MockEngine { throw IllegalStateException("socket closed") }
        val api = api(engine)
        val failure = assertIs<AppResult.Failure>(api.roomState("room-1", "token"))
        assertEquals("NETWORK", failure.error.code)
        assertTrue(failure.error.retryable)
        api.close()
    }

    @Test
    fun `active session returns null when none exists`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "no session") }
        val api = api(engine)
        assertNull((api.activeSession("room-1", "token") as AppResult.Success).value)
        api.close()
    }

    @Test
    fun `room state uses bearer token`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer token-1", request.headers[HttpHeaders.Authorization])
            respond(
                content = """
                    {"room_id":"room-1","participants":[
                      {"user_id":"user-1","role":"SPEAKER","status":"CONNECTED"}
                    ],"status":"WAITING"}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val api = api(engine)
        val success = assertIs<AppResult.Success<*>>(api.roomState("room-1", "token-1"))
        val room = success.value as com.listentogether.core.model.RoomView
        assertEquals("room-1", room.roomId)
        assertEquals(ParticipantView("user-1", Role.SPEAKER, PresenceStatus.CONNECTED), room.participants.single())
        assertEquals(RoomStatus.WAITING, room.status)
        api.close()
    }

    @Test
    fun `turn credentials send room header`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("room-1", request.headers["X-Room-Id"])
            respond(
                content = """{"username":"u","credential":"c","ttl_seconds":600,"urls":["turn:turn.test:3478"]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val api = api(engine)
        val success = assertIs<AppResult.Success<*>>(api.turnCredentials("room-1", "token"))
        val credentials = success.value as com.listentogether.core.model.TurnCredentials
        assertEquals(600, credentials.ttlSeconds)
        assertEquals(listOf("turn:turn.test:3478"), credentials.urls)
        api.close()
    }

    @Test
    fun `leave accepts 202`() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Accepted) }
        val api = api(engine)
        assertEquals(AppResult.Success(Unit), api.leaveRoom("room-1", "token"))
        api.close()
    }

}
