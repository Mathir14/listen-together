package com.listentogether.music.youtube

import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.core.common.SystemClock
import com.listentogether.core.model.Track
import com.listentogether.music.api.ProviderError
import com.listentogether.music.api.ResolvedMedia
import com.listentogether.music.api.TrackResolver
import com.listentogether.music.api.asFailure
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves an audio-only adaptive stream. Some YouTube assets require signature deciphering;
 * those are represented as `ExtractionFailed` rather than guessed at. The resolver caches URLs
 * for their advertised lifetime and refreshes them transparently without changing track identity.
 */
class YouTubeMusicResolver(
    private val client: InnerTubeClient = InnerTubeClient(),
    private val clock: Clock = SystemClock(),
    private val urlTtlMs: Long = DEFAULT_URL_TTL_MS,
) : TrackResolver {
    private val cache = ConcurrentHashMap<String, ResolvedMedia>()

    override suspend fun resolve(track: Track): AppResult<ResolvedMedia> {
        val cached = cache[track.providerTrackId]
        if (cached != null && !cached.isExpired(clock.nowMs())) return AppResult.Success(cached)
        return fetch(track)
    }

    override suspend fun refresh(track: Track): AppResult<ResolvedMedia> = fetch(track)

    override fun invalidate(providerTrackId: String) {
        cache.remove(providerTrackId)
    }

    private suspend fun fetch(track: Track): AppResult<ResolvedMedia> {
        return try {
            val player = client.player(track.providerTrackId)
            val playability = player["playabilityStatus"] as? JsonObject
            val status = (playability?.get("status") as? JsonPrimitive)?.contentOrNull
            if (status != null && status != "OK") {
                val reason = (playability["reason"] as? JsonPrimitive)?.contentOrNull
                return ProviderError.Unavailable.asFailure()
            }

            val formats = ((player["streamingData"] as? JsonObject)?.get("adaptiveFormats") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { format ->
                    (format["mimeType"] as? JsonPrimitive)?.contentOrNull?.startsWith("audio/") == true
                }
                .orEmpty()
            val selected = formats
                .sortedWith(compareBy({ audioPreference(it) }, { -(it.bitrateOrZero()) }))
                .firstOrNull { it["url"] != null }
                ?: return ProviderError.ExtractionFailed.asFailure()

            val url = (selected["url"] as? JsonPrimitive)?.contentOrNull
                ?: return ProviderError.ExtractionFailed.asFailure()
            val mimeType = (selected["mimeType"] as? JsonPrimitive)?.contentOrNull
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.startsWith("audio/") }
                ?: "audio/mp4"
            val expiresIn = (player["streamingData"] as? JsonObject)
                ?.get("expiresInSeconds")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toLongOrNull()
                ?.times(1_000L)
                ?.coerceAtLeast(60_000L)
                ?: urlTtlMs
            val media = ResolvedMedia(
                url = url,
                mimeType = mimeType,
                expiresAtEpochMs = clock.nowMs() + expiresIn,
                headers = mapOf(
                    "User-Agent" to InnerTubeClient.VISIONOS_USER_AGENT,
                    "Referer" to "https://www.youtube.com/",
                ),
                bitrate = (selected["bitrate"] as? JsonPrimitive)?.content?.toIntOrNull(),
            )
            cache[track.providerTrackId] = media
            AppResult.Success(media)
        } catch (error: Throwable) {
            ProviderErrors.map(error).asFailure()
        }
    }

    private fun JsonObject.bitrateOrZero(): Int = (this["bitrate"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /** Prefer MP4/AAC (broad ExoPlayer support), then WebM/Opus as a fallback. */
    private fun audioPreference(format: JsonObject): Int = when {
        (format["mimeType"] as? JsonPrimitive)?.contentOrNull?.contains("mp4") == true -> 0
        (format["mimeType"] as? JsonPrimitive)?.contentOrNull?.contains("webm") == true -> 1
        else -> 2
    }

    companion object {
        const val DEFAULT_URL_TTL_MS = 6 * 60 * 60 * 1_000L // 6 hours
    }
}
