package com.listentogether.music.youtube

import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.music.api.ProviderError
import com.listentogether.music.api.ResolvedMedia
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeMusicTest {
    private class FakeClock(var now: Long = 1_000_000) : Clock {
        override fun nowMs(): Long = now
        override fun monotonicMs(): Long = now
    }

    private fun headers() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) = InnerTubeClient(
        http = io.ktor.client.HttpClient(engine) { expectSuccess = false },
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headers(),
    )

    @Test
    fun `search parses metadata from inner tube response`() = runTest {
        val engine = MockEngine {
            respondJson(
                """
                {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":
                {"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
                  {"musicResponsiveListItemRenderer":{
                    "flexColumns":[
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song A"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                        {"text":"Artist A"},{"text":" • "},{"text":"Album A"},{"text":" • "},{"text":"3:45"}
                      ]}}}
                    ],
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                      {"url":"https://img/60.jpg","width":60},
                      {"url":"https://img/120.jpg","width":120}
                    ]}}},
                    "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":
                      {"playNavigationEndpoint":{"watchEndpoint":{"videoId":"vid-1"}}}}}}
                  }}
                ]}}]}}}}]}}}
                """.trimIndent(),
            )
        }
        val catalog = YouTubeMusicCatalog(client(engine), retryDelayMs = 0)
        val result = catalog.search("song", limit = 10)
        val tracks = assertIs<AppResult.Success<List<com.listentogether.core.model.Track>>>(result).value
        assertEquals(1, tracks.size)
        with(tracks.single()) {
            assertEquals("vid-1", providerTrackId)
            assertEquals("Song A", title)
            assertEquals("Artist A", artist)
            assertEquals("Album A", album)
            assertEquals("https://img/120.jpg", thumbnailUrl)
            assertEquals(225_000, durationMs)
        }
    }

    @Test
    fun `blank query short circuits without network`() = runTest {
        val engine = MockEngine { error("should not be called") }
        val catalog = YouTubeMusicCatalog(client(engine), retryDelayMs = 0)
        assertEquals(AppResult.Success(emptyList()), catalog.search("   "))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `server failure is retried then mapped to provider error`() = runTest {
        val engine = MockEngine { respondJson("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }
        val catalog = YouTubeMusicCatalog(client(engine), retryDelayMs = 0)
        val failure = assertIs<AppResult.Failure>(catalog.search("song"))
        assertEquals("PROVIDER_INVALID_RESPONSE", failure.error.code)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `resolver prefers mp4 and caches until expiry`() = runTest {
        val clock = FakeClock()
        val playerBody = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"300","adaptiveFormats":[
              {"mimeType":"audio/webm; codecs=opus","bitrate":160000,"url":"https://stream/webm"},
              {"mimeType":"audio/mp4; codecs=mp4a.40.2","bitrate":128000,"url":"https://stream/mp4"}
            ]}}
        """.trimIndent()
        val engine = MockEngine { respondJson(playerBody) }
        val resolver = YouTubeMusicResolver(client(engine), clock)
        val track = com.listentogether.core.model.Track(providerTrackId = "vid-1", title = "Song A", artist = "Artist A")

        val first = assertIs<AppResult.Success<ResolvedMedia>>(resolver.resolve(track)).value
        assertEquals("https://stream/mp4", first.url)
        assertEquals("audio/mp4", first.mimeType)
        assertEquals(clock.now + 300_000, first.expiresAtEpochMs)

        val cached = assertIs<AppResult.Success<ResolvedMedia>>(resolver.resolve(track)).value
        assertEquals(first, cached)
        assertEquals(1, engine.requestHistory.size)

        clock.now += 300_000
        resolver.resolve(track)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `unavailable track reports stable error`() = runTest {
        val engine = MockEngine {
            respondJson("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
        }
        val resolver = YouTubeMusicResolver(client(engine))
        val track = com.listentogether.core.model.Track(providerTrackId = "vid-2", title = "B", artist = "C")
        val failure = assertIs<AppResult.Failure>(resolver.resolve(track))
        assertEquals("PROVIDER_UNAVAILABLE", failure.error.code)
    }

    @Test
    fun `ciphered stream is not guessed`() = runTest {
        val engine = MockEngine {
            respondJson(
                """
                {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
                  {"mimeType":"audio/mp4","bitrate":128000,"signatureCipher":"s=abc&url=https%3A%2F%2Fstream"}
                ]}}
                """.trimIndent(),
            )
        }
        val resolver = YouTubeMusicResolver(client(engine))
        val track = com.listentogether.core.model.Track(providerTrackId = "vid-3", title = "C", artist = "D")
        val failure = assertIs<AppResult.Failure>(resolver.resolve(track))
        assertEquals("PROVIDER_EXTRACTION_FAILED", failure.error.code)
        assertTrue(failure.error.message.isNotBlank())
    }
}
