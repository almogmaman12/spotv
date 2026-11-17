package com.almog.spotv.spotify

import android.os.SystemClock

/** One poll of Spotify's player, plus when it was taken so the position can be extrapolated. */
data class NowPlaying(
    val trackId: String,
    val title: String,
    /** All artists, for display. */
    val artist: String,
    /** First artist only: what the lyric sources match on. */
    val primaryArtist: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val progressMs: Long,
    val isPlaying: Boolean,
    val isrc: String,
    val isPodcast: Boolean,
    val shuffle: Boolean,
    /** "off", "context" or "track". */
    val repeat: String,
    val deviceName: String?,
    val volumePercent: Int?,
    /** [SystemClock.elapsedRealtime] when [progressMs] was true. */
    val takenAt: Long,
) {
    /** Where the song is now. */
    fun estimatedProgressMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) progressMs + (now - takenAt) else progressMs
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}

data class QueueItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Int,
    val isrc: String,
)
