package com.listentogether.music.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal InnerTube transport for catalogue and player resolution. Isolated to this module:
 * no InnerTube type may leak past [YouTubeMusicCatalog] or [YouTubeMusicResolver].
 */
class InnerTubeClient(
    private val http: HttpClient = HttpClient(OkHttp) { expectSuccess = false },
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val youtubeBaseUrl: String = if (baseUrl != DEFAULT_BASE_URL) baseUrl else DEFAULT_YOUTUBE_BASE_URL,
    private val clientVersion: String = DEFAULT_CLIENT_VERSION,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Volatile
    private var cachedVisitorData: String? = null

    suspend fun search(query: String): JsonObject {
        val body = execute("$baseUrl/youtubei/v1/search", buildJsonObject {
            put("query", query)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
        }, USER_AGENT, baseUrl)
        return json.parseToJsonElement(body) as? JsonObject ?: error("InnerTube search returned non-object")
    }

    suspend fun player(videoId: String): JsonObject {
        val initialVd = cachedVisitorData
        val firstAttempt = fetchVisionOsPlayer(videoId, initialVd)
        val firstStatus = ((firstAttempt["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
        if (firstStatus == "OK") {
            return firstAttempt
        }

        // If status was not OK (e.g. LOGIN_REQUIRED), fetch fresh visitorData and retry
        val freshVd = refreshVisitorData()
        if (freshVd != null && freshVd != initialVd) {
            val secondAttempt = fetchVisionOsPlayer(videoId, freshVd)
            val secondStatus = ((secondAttempt["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
            if (secondStatus == "OK") {
                return secondAttempt
            }
        }

        // Fallback: try ANDROID_VR client
        val vrAttempt = fetchAndroidVrPlayer(videoId)
        val vrStatus = ((vrAttempt["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
        if (vrStatus == "OK") {
            return vrAttempt
        }

        return firstAttempt
    }

    private suspend fun fetchVisionOsPlayer(videoId: String, visitorData: String?): JsonObject {
        val payload = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", VISIONOS_CLIENT_NAME)
                    put("clientVersion", VISIONOS_CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("platform", "MOBILE")
                    put("deviceMake", "Apple")
                    put("deviceModel", VISIONOS_DEVICE_MODEL)
                    put("osName", "visionOS")
                    put("osVersion", VISIONOS_OS_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                    if (visitorData != null) {
                        put("visitorData", visitorData)
                    }
                }
            }
        }
        val url = "$youtubeBaseUrl/youtubei/v1/player?prettyPrint=false&id=$videoId"
        val body = execute(url, payload, VISIONOS_USER_AGENT, youtubeBaseUrl, formatVersion = "2")
        return json.parseToJsonElement(body) as? JsonObject ?: error("InnerTube player returned non-object")
    }

    private suspend fun fetchAndroidVrPlayer(videoId: String): JsonObject {
        val payload = buildJsonObject {
            put("videoId", videoId)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", "1.56.21")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
        }
        val url = "$youtubeBaseUrl/youtubei/v1/player?prettyPrint=false&id=$videoId"
        val body = execute(url, payload, ANDROID_VR_USER_AGENT, youtubeBaseUrl)
        return json.parseToJsonElement(body) as? JsonObject ?: error("InnerTube player returned non-object")
    }

    suspend fun refreshVisitorData(): String? = try {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", VISIONOS_CLIENT_NAME)
                    put("clientVersion", VISIONOS_CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("platform", "MOBILE")
                    put("deviceMake", "Apple")
                    put("deviceModel", VISIONOS_DEVICE_MODEL)
                    put("osName", "visionOS")
                    put("osVersion", VISIONOS_OS_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                }
            }
        }
        val url = "$youtubeBaseUrl/youtubei/v1/visitor_id?prettyPrint=false"
        val body = execute(url, payload, VISIONOS_USER_AGENT, youtubeBaseUrl, formatVersion = "2")
        val parsed = json.parseToJsonElement(body) as? JsonObject
        val vd = (parsed?.get("responseContext") as? JsonObject)?.get("visitorData")?.jsonPrimitive?.contentOrNull
        if (!vd.isNullOrBlank()) {
            cachedVisitorData = vd
            vd
        } else null
    } catch (_: Throwable) {
        null
    }

    private suspend fun execute(
        url: String,
        payload: JsonObject,
        userAgent: String,
        origin: String,
        formatVersion: String? = null,
    ): String {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            header("User-Agent", userAgent)
            header("Origin", origin)
            if (formatVersion != null) {
                header("X-Goog-Api-Format-Version", formatVersion)
            }
            setBody(payload.toString())
        }
        if (response.status.value !in 200..299) {
            error("InnerTube call to $url failed with HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://music.youtube.com"
        const val DEFAULT_YOUTUBE_BASE_URL = "https://www.youtube.com"
        const val DEFAULT_CLIENT_VERSION = "1.20240101.00.00"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0 Safari/537.36"

        const val VISIONOS_CLIENT_NAME = "VISIONOS"
        const val VISIONOS_CLIENT_VERSION = "1.04"
        const val VISIONOS_DEVICE_MODEL = "RealityDevice17,1"
        const val VISIONOS_OS_VERSION = "26.6.0.23O770"
        const val VISIONOS_USER_AGENT =
            "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; en)"

        const val ANDROID_VR_USER_AGENT = "Mozilla/5.0"
    }
}

internal object ProviderErrors {
    fun map(error: Throwable): com.listentogether.music.api.ProviderError = when (error) {
        is kotlinx.coroutines.CancellationException -> throw error
        is java.io.IOException -> com.listentogether.music.api.ProviderError.Network
        else -> com.listentogether.music.api.ProviderError.InvalidResponse(error.message ?: "provider failure")
    }
}
