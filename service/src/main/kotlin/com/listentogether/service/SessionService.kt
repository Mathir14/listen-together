package com.listentogether.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.listentogether.playback.RoomMediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SessionService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var isForeground = false
    private val observerJobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()
        SessionNotificationHelper.createNotificationChannel(this)

        val runtime = SessionRuntimeHolder.get()
        if (runtime != null) {
            val player = runtime.mediaPlayer?.player
            if (player != null) {
                val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let {
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        it,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                }
                mediaSession = RoomMediaSession.create(
                    context = this,
                    player = player,
                    sessionActivity = launchIntent,
                )
            }
            observeRuntime(runtime)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = SessionRuntimeHolder.get()
        if (runtime != null) {
            when (intent?.action) {
                ACTION_PLAY -> runtime.play()
                ACTION_PAUSE -> runtime.pause()
                ACTION_PREVIOUS -> runtime.previous()
                ACTION_NEXT -> runtime.next()
                ACTION_TOGGLE_MIC -> runtime.setMicMuted(!runtime.micMuted.value)
                ACTION_TOGGLE_TTS -> runtime.setTtsEnabled(!runtime.ttsState.value.enabled)
                ACTION_LEAVE -> runtime.leave()
                ACTION_START -> updateForeground(runtime)
                ACTION_STOP -> stopForegroundService()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun observeRuntime(runtime: SessionRuntime) {
        observerJobs.forEach(Job::cancel)
        observerJobs.clear()

        observerJobs += serviceScope.launch {
            runtime.serviceRequired.collect { required ->
                if (required) {
                    updateForeground(runtime)
                } else {
                    stopForegroundService()
                }
            }
        }

        observerJobs += serviceScope.launch {
            combine(
                runtime.serviceNotificationText,
                runtime.micTransmitting,
                runtime.state,
                runtime.ttsState,
                runtime.micMuted,
            ) { _, _, _, _, _ -> }.collect {
                if (runtime.serviceRequired.value) {
                    updateForeground(runtime)
                }
            }
        }
    }

    fun updateForeground(runtime: SessionRuntime) {
        val notification = SessionNotificationHelper.buildNotification(this, runtime)
        val isMicTransmitting = runtime.micTransmitting.value

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (isMicTransmitting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                ServiceCompat.startForeground(
                    this,
                    SessionNotificationHelper.NOTIFICATION_ID,
                    notification,
                    fgsType,
                )
                isForeground = true
            } catch (e: SecurityException) {
                // If microphone FGS is rejected (Android 14+ background start or revoked mic permission)
                runtime.onMicrophoneUnavailable()
                ServiceCompat.startForeground(
                    this,
                    SessionNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                isForeground = true
            }
        } else {
            startForeground(SessionNotificationHelper.NOTIFICATION_ID, notification)
            isForeground = true
        }
    }

    private fun stopForegroundService() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        observerJobs.forEach(Job::cancel)
        observerJobs.clear()
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.listentogether.service.action.START"
        const val ACTION_STOP = "com.listentogether.service.action.STOP"
        const val ACTION_PLAY = "com.listentogether.service.action.PLAY"
        const val ACTION_PAUSE = "com.listentogether.service.action.PAUSE"
        const val ACTION_PREVIOUS = "com.listentogether.service.action.PREVIOUS"
        const val ACTION_NEXT = "com.listentogether.service.action.NEXT"
        const val ACTION_TOGGLE_MIC = "com.listentogether.service.action.TOGGLE_MIC"
        const val ACTION_TOGGLE_TTS = "com.listentogether.service.action.TOGGLE_TTS"
        const val ACTION_LEAVE = "com.listentogether.service.action.LEAVE"

        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
