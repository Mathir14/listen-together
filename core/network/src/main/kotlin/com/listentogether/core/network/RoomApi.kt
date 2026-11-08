package com.listentogether.core.network

import com.listentogether.core.common.AppError
import com.listentogether.core.common.AppResult
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.TurnCredentials
import com.listentogether.protocol.ApiError
import com.listentogether.protocol.CreateRoomRequest
import com.listentogether.protocol.JoinRoomRequest
import com.listentogether.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

interface RoomApi {
    suspend fun createRoom(role: Role): AppResult<RoomCredentials>
    suspend fun joinRoom(roomId: String, inviteSecret: String): AppResult<RoomCredentials>
    suspend fun leaveRoom(roomId: String, token: String): AppResult<Unit>
    suspend fun roomState(roomId: String, token: String): AppResult<RoomView>
    suspend fun activeSession(roomId: String, token: String): AppResult<SessionView?>
    suspend fun turnCredentials(roomId: String, token: String): AppResult<TurnCredentials>
    fun close()
}

class KtorRoomApi(
    private val config: NetworkConfig,
    engine: HttpClientEngine? = null,
) : RoomApi {
    private val client: HttpClient = if (engine == null) {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(ProtocolJson.json) }
        }
    } else {
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(ProtocolJson.json) }
        }
    }

    override suspend fun createRoom(role: Role): AppResult<RoomCredentials> = call {
        client.post("${config.normalizedBaseUrl}/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest(role))
        }
    }

    override suspend fun joinRoom(roomId: String, inviteSecret: String): AppResult<RoomCredentials> = call {
        client.post("${config.normalizedBaseUrl}/rooms/$roomId/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinRoomRequest(inviteSecret))
        }
    }

    override suspend fun leaveRoom(roomId: String, token: String): AppResult<Unit> {
        return try {
            val response = client.post("${config.normalizedBaseUrl}/rooms/$roomId/leave") {
                bearerAuth(token)
            }
            if (response.status.isSuccess()) AppResult.Success(Unit)
            else AppResult.Failure(response.toAppError())
        } catch (error: Throwable) {
            AppResult.Failure(AppError.network(error.message ?: "Leave failed"))
        }
    }

    override suspend fun roomState(roomId: String, token: String): AppResult<RoomView> = call {
        client.get("${config.normalizedBaseUrl}/rooms/$roomId/state") { bearerAuth(token) }
    }

    override suspend fun activeSession(roomId: String, token: String): AppResult<SessionView?> {
        return try {
            val response = client.get("${config.normalizedBaseUrl}/rooms/$roomId/session") { bearerAuth(token) }
            if (response.status == HttpStatusCode.NotFound) AppResult.Success(null)
            else if (response.status.isSuccess()) AppResult.Success(response.body<SessionView>())
            else AppResult.Failure(response.toAppError())
        } catch (error: Throwable) {
            AppResult.Failure(AppError.network(error.message ?: "Session lookup failed"))
        }
    }

    override suspend fun turnCredentials(roomId: String, token: String): AppResult<TurnCredentials> = call {
        client.get("${config.normalizedBaseUrl}/turn-credentials") {
            bearerAuth(token)
            header("X-Room-Id", roomId)
        }
    }

    private suspend inline fun <reified T> call(crossinline block: suspend () -> HttpResponse): AppResult<T> {
        return try {
            val response = block()
            if (response.status.isSuccess()) AppResult.Success(response.body<T>())
            else AppResult.Failure(response.toAppError())
        } catch (error: Throwable) {
            AppResult.Failure(AppError.network(error.message ?: "Network request failed"))
        }
    }

    private suspend fun HttpResponse.toAppError(): AppError {
        val apiError = runCatching { body<ApiError>() }.getOrNull()
        return AppError(
            code = apiError?.code ?: "HTTP_${status.value}",
            message = apiError?.message ?: status.description,
            retryable = status.value >= 500,
        )
    }

    override fun close() {
        client.close()
    }
}
