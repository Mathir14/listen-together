package com.listentogether.backend.transport.http

import com.listentogether.backend.auth.TokenBinding
import com.listentogether.backend.room.RoomRegistry
import com.listentogether.backend.turn.TurnService
import com.listentogether.protocol.ApiError
import com.listentogether.protocol.CreateRoomRequest
import com.listentogether.protocol.JoinRoomRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val rooms: Int,
    val sessions: Int,
)

fun Application.configureHttp(
    registry: RoomRegistry,
    turnService: TurnService,
) {
    routing {
        get("/healthz") {
            call.respond(HealthResponse(status = "ok", rooms = registry.activeRoomCount(), sessions = registry.activeSessionCount()))
        }

        route("/rooms") {
            post {
                val request = call.receive<CreateRoomRequest>()
                call.respond(HttpStatusCode.Created, registry.createRoom(request.role))
            }

            post("/{id}/join") {
                val roomId = call.parameters["id"].orEmpty()
                val request = call.receive<JoinRoomRequest>()
                when (val outcome = registry.joinRoom(roomId, request.inviteSecret)) {
                    is RoomRegistry.JoinOutcome.Joined -> call.respond(HttpStatusCode.OK, outcome.credentials)
                    is RoomRegistry.JoinOutcome.Rejected -> call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError(outcome.code, outcome.message),
                    )
                }
            }

            post("/{id}/leave") {
                val roomId = call.parameters["id"].orEmpty()
                val binding = call.requireBinding(registry, roomId) ?: return@post
                registry.leave(roomId, binding.participantId)
                call.respond(HttpStatusCode.Accepted, ApiError("LEAVE_ACCEPTED", "Leave accepted"))
            }

            get("/{id}/state") {
                val roomId = call.parameters["id"].orEmpty()
                call.requireBinding(registry, roomId) ?: return@get
                val view = registry.roomView(roomId)
                if (view == null) call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "Unknown room"))
                else call.respond(view)
            }

            get("/{id}/session") {
                val roomId = call.parameters["id"].orEmpty()
                call.requireBinding(registry, roomId) ?: return@get
                val view = registry.sessionView(roomId)
                if (view == null) call.respond(HttpStatusCode.NotFound, ApiError("NO_SESSION", "No active session"))
                else call.respond(view)
            }
        }

        get("/turn-credentials") {
            val token = call.bearerToken()
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Bearer token required"))
                return@get
            }
            val roomId = call.request.headers["X-Room-Id"]
            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PAYLOAD", "X-Room-Id header required"))
                return@get
            }
            val binding = registry.authenticate(roomId, token)
            if (binding == null) {
                call.respond(HttpStatusCode.Forbidden, ApiError("NOT_MEMBER", "Invalid credentials"))
                return@get
            }
            val credentials = turnService.credentials(binding)
            if (credentials == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("TURN_UNAVAILABLE", "TURN is not configured"))
            } else {
                call.respond(credentials)
            }
        }
    }
}

fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
}

suspend fun ApplicationCall.requireBinding(registry: RoomRegistry, roomId: String): TokenBinding? {
    val token = bearerToken()
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Bearer token required"))
        return null
    }
    val binding = registry.authenticate(roomId, token)
    if (binding == null) {
        respond(HttpStatusCode.Forbidden, ApiError("NOT_MEMBER", "Invalid credentials"))
        return null
    }
    return binding
}
