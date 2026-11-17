package com.almog.spotv

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.almog.spotv.spotify.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * A simple DJ: when a new song starts it says a short line over the music, lowering the volume while it talks.
 * This is our own voice (Android text-to-speech); Spotify's real DJ has no public API.
 */
class DjVoice(
    context: Context,
    private val scope: CoroutineScope,
    private val setVolumePercent: (Int) -> Unit,
    private val enabled: () -> Boolean,
) {
    private var ready = false
    private var lastTrack: String? = null
    private var busy = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.US
    }

    fun onState(np: NowPlaying?) {
        val id = np?.trackId?.takeIf { it.isNotEmpty() && !np.isPodcast }
        if (id == null || id == lastTrack) return
        val first = lastTrack == null
        lastTrack = id
        // The first song after start-up is not announced, and nothing is said while paused or already talking.
        if (first || !np.isPlaying || !ready || busy || !enabled()) return
        val line = lines[(System.nanoTime() % lines.size).toInt().let { if (it < 0) -it else it }]
            .format(np.title, np.primaryArtist)
        busy = true
        scope.launch { speak(line, np.volumePercent) }
    }

    private suspend fun speak(line: String, originalVolume: Int?) {
        var ducked = false
        try {
            if (originalVolume != null) {
                setVolumePercent((originalVolume - DUCK).coerceAtLeast(0))
                ducked = true
            }
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(Unit) }
            })
            val result = tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "dj")
            if (result == TextToSpeech.SUCCESS) {
                kotlinx.coroutines.withTimeoutOrNull(12_000) { done.await() }
                delay(300)
            }
        } finally {
            // Restore the exact starting level, avoiding volume drift after repeated announcements.
            if (ducked && originalVolume != null) {
                try { setVolumePercent(originalVolume) } catch (_: Exception) {}
            }
            busy = false
        }
    }

    fun shutdown() { tts.shutdown() }

    private companion object {
        const val DUCK = 25
        val lines = listOf(
            "Now playing %s, by %s.",
            "Here comes %s from %s.",
            "Up next, %s. That's %s.",
            "This one is %s, by %s.",
        )
    }
}
