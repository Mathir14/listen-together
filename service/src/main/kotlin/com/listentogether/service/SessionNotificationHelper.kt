package com.listentogether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.listentogether.core.model.Role

object SessionNotificationHelper {
    const val CHANNEL_ID = "listen_together_session"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Listen Together Session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Synchronized playback and voice communication"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        runtime: SessionRuntime,
    ): Notification {
        val sessionState = runtime.state.value
        val track = sessionState.playback?.track
        val isPlaying = sessionState.playback?.isPlaying == true
        val role = sessionState.role
        val micMuted = runtime.micMuted.value
        val micBlocked = runtime.micBlocked.value
        val ttsEnabled = runtime.ttsState.value.enabled
        val statusText = runtime.serviceNotificationText.value

        val title = track?.title ?: "Listen Together"
        val text = if (track != null) {
            "${track.artist} · $statusText"
        } else {
            statusText
        }

        val launchIntent = context.packageManager?.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(statusText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Previous track action
        val prevIntent = Intent(context, SessionService::class.java).apply { action = SessionService.ACTION_PREVIOUS }
        val prevPending = PendingIntent.getService(context, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)

        // Play/Pause action
        val playPauseAction = if (isPlaying) SessionService.ACTION_PAUSE else SessionService.ACTION_PLAY
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseIntent = Intent(context, SessionService::class.java).apply { action = playPauseAction }
        val playPausePending = PendingIntent.getService(context, 2, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(playPauseIcon, playPauseTitle, playPausePending)

        // Next track action
        val nextIntent = Intent(context, SessionService::class.java).apply { action = SessionService.ACTION_NEXT }
        val nextPending = PendingIntent.getService(context, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_media_next, "Next", nextPending)

        // Mic toggle action (Speaker role only)
        if (role == Role.SPEAKER) {
            val micTitle = if (micBlocked) "Mic Blocked" else if (micMuted) "Unmute Mic" else "Mute Mic"
            val micIntent = Intent(context, SessionService::class.java).apply { action = SessionService.ACTION_TOGGLE_MIC }
            val micPending = PendingIntent.getService(context, 4, micIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_btn_speak_now, micTitle, micPending)

            // TTS toggle action (Speaker role only)
            val ttsTitle = if (ttsEnabled) "TTS On" else "TTS Off"
            val ttsIntent = Intent(context, SessionService::class.java).apply { action = SessionService.ACTION_TOGGLE_TTS }
            val ttsPending = PendingIntent.getService(context, 5, ttsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_agenda, ttsTitle, ttsPending)
        }

        // Leave action
        val leaveIntent = Intent(context, SessionService::class.java).apply { action = SessionService.ACTION_LEAVE }
        val leavePending = PendingIntent.getService(context, 6, leaveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave", leavePending)

        return builder.build()
    }
}
