package com.almog.spotv

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Metadata published by another app's Android media session. */
data class ExternalPlayingTrack(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
) {
    val key: String
        get() = "$packageName:$artist:$title"
}

/**
 * Reads active Android media sessions after the user enables SpotTV as a notification listener.
 *
 * This is metadata access, not audio capture. It does not record, upload, or recognise audio.
 */
class MediaSessionAccessService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            publishCurrentTrack()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        MediaSessionSongSource.publish(null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Notification changes can accompany track changes. The short poll handles the session state.
        publishCurrentTrack()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishCurrentTrack()
    }

    private fun publishCurrentTrack() {
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions = try {
            manager.getActiveSessions(ComponentName(this, MediaSessionAccessService::class.java))
        } catch (_: SecurityException) {
            MediaSessionSongSource.publish(null)
            return
        }

        val selected = sessions.firstNotNullOfOrNull { controller ->
            val playback = controller.playbackState
            if (playback?.state != android.media.session.PlaybackState.STATE_PLAYING) return@firstNotNullOfOrNull null
            controller.toExternalTrack(playback.position, playback.playbackSpeed)
        }
        MediaSessionSongSource.publish(selected)
    }

    private fun MediaController.toExternalTrack(positionMs: Long, playbackSpeed: Float): ExternalPlayingTrack? {
        val metadata: MediaMetadata = metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            )?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) return null

        return ExternalPlayingTrack(
            packageName = packageName,
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = positionMs.coerceAtLeast(0L),
            playbackSpeed = playbackSpeed,
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_500L
    }
}

/** Process-wide stream consumed by MainActivity. */
object MediaSessionSongSource {
    private val _track = MutableStateFlow<ExternalPlayingTrack?>(null)
    val track: StateFlow<ExternalPlayingTrack?> = _track

    internal fun publish(track: ExternalPlayingTrack?) {
        _track.value = track
    }
}
