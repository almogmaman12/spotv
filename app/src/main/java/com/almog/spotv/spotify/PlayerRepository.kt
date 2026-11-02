package com.almog.spotv.spotify

import android.os.SystemClock
import com.almog.spotv.AppStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.almog.spotv.Constants

/**
 * What the TV shows (polled from Spotify) and the commands the remote can send.
 * Commands update the screen at once and are confirmed by the next poll.
 */
class PlayerRepository(
    private val api: SpotifyApi,
    private val scope: CoroutineScope,
    private val onAuthLost: () -> Unit,
) {
    private val _state = MutableStateFlow<NowPlaying?>(null)

    /** The current song, or null when nothing is playing. */
    val state: StateFlow<NowPlaying?> = _state

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())

    /** The next songs, nearest first. */
    val queue: StateFlow<List<QueueItem>> = _queue

    private val _connected = MutableStateFlow(false)

    /** True once a poll has got an answer from Spotify (even "nothing playing"). */
    val connected: StateFlow<Boolean> = _connected

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var lastQueueTrack: String? = null
    private var lastQueueAt = 0L

    /** Polls until cancelled. Run it while the screen is visible. */
    suspend fun runPolling() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var wait = Constants.POLL_IDLE_MS
            when (val result = api.playback()) {
                is PlaybackResult.Active -> {
                    failures = 0
                    _connected.value = true
                    AppStatus.clear("spotify")
                    _state.value = result.state
                    wait = if (result.state.isPlaying) Constants.POLL_PLAYING_MS else Constants.POLL_PAUSED_MS
                    refreshQueueIfNeeded(result.state)
                }
                PlaybackResult.Idle -> {
                    failures = 0
                    _connected.value = true
                    AppStatus.clear("spotify")
                    _state.value = null
                    _queue.value = emptyList()
                    lastQueueTrack = null
                }
                PlaybackResult.AuthLost -> {
                    onAuthLost()
                    return
                }
                is PlaybackResult.RateLimited -> {
                    AppStatus.report("spotify", "Spotify is limiting requests, slowing down", AppStatus.WARN, 20_000L)
                    wait = result.retryAfterMs
                }
                PlaybackResult.Failed -> {
                    failures++
                    if (failures >= 2) AppStatus.report("spotify", "Can't reach Spotify", AppStatus.WARN, 20_000L)
                    wait = (Constants.POLL_IDLE_MS * minOf(failures, 4)).coerceAtMost(30_000L)
                }
            }
            // A command wakes the loop early so the screen confirms it quickly.
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    private suspend fun refreshQueueIfNeeded(np: NowPlaying) {
        val now = SystemClock.elapsedRealtime()
        if (np.trackId == lastQueueTrack && now - lastQueueAt < 20_000L) return
        lastQueueTrack = np.trackId
        lastQueueAt = now
        _queue.value = api.queue()
    }

    private fun wake(afterMs: Long = 500L) {
        scope.launch {
            delay(afterMs)
            kick.trySend(Unit)
        }
    }

    // ---- remote commands ---------------------------------------------------

    fun playPause() {
        val np = _state.value ?: return
        val resume = !np.isPlaying
        _state.update { it?.copy(isPlaying = resume, progressMs = it.estimatedProgressMs(), takenAt = SystemClock.elapsedRealtime()) }
        scope.launch {
            if (resume) api.resume() else api.pause()
            wake()
        }
    }

    fun pause() {
        if (_state.value?.isPlaying == true) playPause()
    }

    fun play() {
        if (_state.value?.isPlaying == false) playPause()
    }

    fun next() {
        scope.launch {
            api.next()
            wake(700L)
        }
    }

    fun previous() {
        scope.launch {
            api.previous()
            wake(700L)
        }
    }

    fun seekBy(deltaMs: Long) {
        val np = _state.value ?: return
        seekTo(np.estimatedProgressMs() + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val np = _state.value ?: return
        val target = if (np.durationMs > 0) positionMs.coerceIn(0L, np.durationMs) else positionMs.coerceAtLeast(0L)
        _state.update { it?.copy(progressMs = target, takenAt = SystemClock.elapsedRealtime()) }
        scope.launch {
            api.seek(target)
            wake()
        }
    }

    fun toggleShuffle() {
        val np = _state.value ?: return
        val on = !np.shuffle
        _state.update { it?.copy(shuffle = on) }
        scope.launch {
            api.shuffle(on)
            wake()
        }
    }

    fun cycleRepeat() {
        val np = _state.value ?: return
        val mode = when (np.repeat) {
            "off" -> "context"
            "context" -> "track"
            else -> "off"
        }
        _state.update { it?.copy(repeat = mode) }
        scope.launch {
            api.repeat(mode)
            wake()
        }
    }

    fun volumeBy(delta: Int) {
        val np = _state.value ?: return
        val current = np.volumePercent ?: return
        val target = (current + delta).coerceIn(0, 100)
        _state.update { it?.copy(volumePercent = target) }
        scope.launch { api.volume(target) }
    }
}
