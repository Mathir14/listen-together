package com.listentogether.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * The only place that talks to Android audio focus. Playback, voice and TTS must route through
 * [AudioArbiter] instead (architecture §12).
 */
class AndroidFocusController(
    context: Context,
    private val onFocusChange: (ExternalFocusChange) -> Unit,
) : FocusController {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { change ->
            onFocusChange(
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> ExternalFocusChange.GAIN
                    AudioManager.AUDIOFOCUS_LOSS -> ExternalFocusChange.LOSS
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> ExternalFocusChange.LOSS_TRANSIENT
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ExternalFocusChange.LOSS_TRANSIENT_CAN_DUCK
                    else -> return@setOnAudioFocusChangeListener
                },
            )
        }
        .build()

    override fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(request)
    }
}

class AndroidRouteController(context: Context) : RouteController {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun enterCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull()?.let { device ->
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }
    }

    override fun exitCommunicationMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
