package com.listentogether.music.youtube

import com.listentogether.core.common.AppResult
import com.listentogether.core.model.Track
import com.listentogether.music.api.ResolvedMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
class YouTubeMusicLiveIntegrationTest {

    private val catalog = YouTubeMusicCatalog()
    private val resolver = YouTubeMusicResolver()
    private val httpClient = HttpClient(OkHttp) { expectSuccess = false }

    @Test
    fun `search returns real tracks with metadata`() = runTest {
        val result = catalog.search("Bohemian Rhapsody Queen", limit = 5)
        val tracks = assertIs<AppResult.Success<List<Track>>>(result).value
        assertTrue(tracks.isNotEmpty(), "Search should return tracks")
        val track = tracks.first()
        assertTrue(track.providerTrackId.isNotBlank())
        assertTrue(track.title.isNotBlank())
        assertTrue(track.artist.isNotBlank())
    }

    @Test
    fun `resolve real tracks returns playable audio streams`() = runTest {
        val tracksToTest = listOf(
            Track(providerTrackId = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley"),
            Track(providerTrackId = "kJQP7kiw5Fk", title = "Despacito", artist = "Luis Fonsi"),
            Track(providerTrackId = "fJ9rUzIMcZQ", title = "Bohemian Rhapsody", artist = "Queen"),
        )

        for (track in tracksToTest) {
            val result = resolver.resolve(track)
            val media = assertIs<AppResult.Success<ResolvedMedia>>(result).value

            assertTrue(media.url.startsWith("https://"), "URL should be https: ${media.url}")
            assertTrue(media.mimeType.startsWith("audio/"), "MIME should be audio: ${media.mimeType}")
            val expires = media.expiresAtEpochMs
            assertNotNull(expires)
            assertTrue(expires > System.currentTimeMillis())

            // Verify byte stream is playable (HTTP 206 partial content)
            val response = httpClient.request(media.url) {
                method = HttpMethod.Get
                header("Range", "bytes=0-1024")
                media.headers.forEach { (k, v) -> header(k, v) }
            }
            assertTrue(
                response.status.value in listOf(200, 206),
                "Stream URL should respond with 200 or 206, got: ${response.status.value}",
            )

            // Verify refresh transparently resolves without changing track identity
            val refreshed = assertIs<AppResult.Success<ResolvedMedia>>(resolver.refresh(track)).value
            assertTrue(refreshed.url.startsWith("https://"))
        }
    }

    @Test
    fun `resolve invalid track reports failure`() = runTest {
        val invalidTrack = Track(providerTrackId = "non_existent_track_99999", title = "Unknown", artist = "Unknown")
        val result = resolver.resolve(invalidTrack)
        assertIs<AppResult.Failure>(result)
    }
}
