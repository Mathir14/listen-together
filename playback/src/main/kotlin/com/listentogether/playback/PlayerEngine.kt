package com.listentogether.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.listentogether.core.model.Track
import com.listentogether.music.api.ResolvedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val isBuffering: Boolean = false,
    val hasEnded: Boolean = false,
    val hasFailed: Boolean = false,
    val durationMs: Long = 0,
    val playbackRate: Float = 1f,
    val volume: Float = 1f,
    val failureDetail: String? = null,
)

/** Narrow player boundary so drift/application logic is unit-testable without Media3. */
interface PlayerEngine {
    val state: StateFlow<PlayerState>

    fun prepare(track: Track, media: ResolvedMedia, startPositionMs: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun setVolume(volume: Float)
    fun positionMs(): Long
    fun clear()
    fun release()
}

/** Optional Media3 surface consumed by `:service` to build a MediaSession. */
interface Media3PlayerProvider {
    val player: Player
}

class ExoPlayerEngine(
    private val exoPlayer: ExoPlayer,
) : PlayerEngine, Media3PlayerProvider {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val player: Player get() = exoPlayer

    private var currentTrackId: String? = null

    init {
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false,
        )
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(hasFailed = true, failureDetail = error.message)
            }
        })
    }

    override fun prepare(
        track: Track,
        media: ResolvedMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        currentTrackId = track.providerTrackId
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.thumbnailUrl?.let(android.net.Uri::parse))
            .setIsPlayable(true)
            .build()
        val item = MediaItem.Builder()
            .setUri(media.url)
            .setMimeType(media.mimeType)
            .setMediaId(track.providerTrackId)
            .setMediaMetadata(metadata)
            .build()

        if (media.headers.isNotEmpty()) {
            val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
            media.headers["User-Agent"]?.let { dataSourceFactory.setUserAgent(it) }
            dataSourceFactory.setDefaultRequestProperties(media.headers)
            val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(item)
            exoPlayer.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0))
        } else {
            exoPlayer.setMediaItem(item, startPositionMs.coerceAtLeast(0))
        }

        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.prepare()
        _state.value = PlayerState(
            trackId = track.providerTrackId,
            isBuffering = true,
            volume = _state.value.volume,
            playbackRate = _state.value.playbackRate,
        )
    }

    override fun play() {
        exoPlayer.play()
        publish()
    }

    override fun pause() {
        exoPlayer.pause()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    override fun setRate(rate: Float) {
        exoPlayer.setPlaybackSpeed(rate)
        _state.value = _state.value.copy(playbackRate = rate)
    }

    override fun setVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    override fun positionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0)

    override fun clear() {
        currentTrackId = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlayerState(volume = _state.value.volume)
    }

    override fun release() {
        exoPlayer.release()
    }

    private fun publish() {
        val duration = exoPlayer.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        _state.value = _state.value.copy(
            trackId = currentTrackId,
            isPlaying = exoPlayer.isPlaying,
            isReady = exoPlayer.playbackState == Player.STATE_READY,
            isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            hasEnded = exoPlayer.playbackState == Player.STATE_ENDED,
            durationMs = duration,
        )
    }
}
