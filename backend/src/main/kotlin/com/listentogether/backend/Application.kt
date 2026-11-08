package com.listentogether.backend

import com.listentogether.backend.config.BackendConfig
import com.listentogether.backend.room.RoomRegistry
import com.listentogether.backend.transport.http.configureHttp
import com.listentogether.backend.transport.ws.configureWebSocket
import com.listentogether.backend.turn.TurnService
import com.listentogether.core.common.SystemClock
import com.listentogether.core.common.logging.RedactingLogger
import com.listentogether.protocol.ProtocolJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val config = BackendConfig()
    embeddedServer(Netty, port = config.port, host = config.host) {
        listenTogetherModule(config)
    }.start(wait = true)
}

fun Application.listenTogetherModule(config: BackendConfig = BackendConfig()) {
    val clock = SystemClock()
    val logger = RedactingLogger()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registry = RoomRegistry(config, clock, scope, logger)
    val turnService = TurnService(config, clock)

    if (config.deploymentEnvironment.equals("production", ignoreCase = true) && !config.turnEnabled) {
        logger.event(
            "turn.configuration.warning",
            mapOf("message" to "Production environment running without TURN relay configured. Symmetric NAT traversal will fail."),
        )
    }

    install(ContentNegotiation) {
        json(ProtocolJson.json)
    }
    install(WebSockets) {
        pingPeriod = (config.heartbeatStaleMs / 3).milliseconds
        timeout = config.heartbeatStaleMs.milliseconds
        maxFrameSize = config.maxFrameBytes.toLong()
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/healthz").not() }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("http.unhandled", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                com.listentogether.protocol.ApiError("INTERNAL", "Internal server error"),
            )
        }
    }

    configureHttp(registry, turnService)
    configureWebSocket(registry, config, clock, logger, scope)

    monitor.subscribe(ApplicationStopped) {
        logger.event("application.stopping")
        registry.shutdown()
    }
}
