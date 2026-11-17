package com.almog.spotv.spotify

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the music going: when the queue is about to run dry, adds a song by the same artist or from your
 * Liked Songs (Spotify no longer gives apps like this its recommendations, so this is our own "radio").
 */
class AutoQueue(
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    private val history = LinkedHashSet<String>()
    private var queuedFor: String? = null
    private var liked: List<Entry>? = null
    private var busy = false

    /** Call on every player update. */
    fun onState(np: NowPlaying?, queue: List<QueueItem>) {
        if (np == null || np.isPodcast || np.trackId.isEmpty()) return
        history.add(np.trackId)
        while (history.size > 80) history.remove(history.first())
        if (!enabled() || busy || queuedFor == np.trackId) return
        // Only at the very end: nothing is waiting, and not on repeat.
        if (queue.isNotEmpty() || np.repeat != "off" || np.durationMs <= 0) return
        if (np.durationMs - np.estimatedProgressMs() > 30_000L) return
        queuedFor = np.trackId
        add(np, 1)
    }

    /** "Start radio": queue a handful of songs like this one right now. */
    fun startRadio(np: NowPlaying, count: Int = 6) = add(np, count)

    private fun add(np: NowPlaying, count: Int) {
        busy = true
        scope.launch {
            try {
                val fromArtist = ArrayList<Entry>()
                if (np.artistId.isNotEmpty()) {
                    for (album in catalog.artistAlbums(np.artistId).items.shuffled().take(3)) {
                        fromArtist += catalog.albumTracks(album).items
                    }
                }
                val likedSongs = liked ?: catalog.savedTracks().items.also { liked = it }
                repeat(count) {
                    val pick = pick(fromArtist, likedSongs, history, Random) ?: return@repeat
                    history.add(pick.id)
                    catalog.addToQueue(pick.uri)
                }
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** Mostly the same artist, sometimes your own likes; never something heard recently. */
        fun pick(fromArtist: List<Entry>, liked: List<Entry>, history: Set<String>, rnd: Random): Entry? {
            val a = fromArtist.filter { it.id !in history }
            val b = liked.filter { it.id !in history }
            val first = if (a.isNotEmpty() && (b.isEmpty() || rnd.nextFloat() < 0.6f)) a else b
            return first.randomOrNull(rnd) ?: (a + b).randomOrNull(rnd)
        }
    }
}
