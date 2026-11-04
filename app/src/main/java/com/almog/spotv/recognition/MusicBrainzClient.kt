package com.almog.spotv.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Free MusicBrainz recording lookup, serialized to stay below one request per second. */
class MusicBrainzClient(private val http: OkHttpClient = defaultHttp) {
    suspend fun enrich(title: String, artist: String): EnrichedTrack? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        requestMutex.withLock {
            val wait = (1_100L - (System.currentTimeMillis() - lastRequestAt)).coerceAtLeast(0L)
            if (lastRequestAt != 0L && wait > 0L) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            val query = "recording:\"$title\" AND artist:\"$artist\""
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val request = Request.Builder().url("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=5")
                .header("User-Agent", "SpotTV/0.1.0 (https://github.com/almogmaman12/spotv)").build()
            http.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext null
                val recordings = JSONObject(response.body?.string().orEmpty()).optJSONArray("recordings") ?: return@withContext null
                var selected: JSONObject? = null
                var best = -1
                val wantedTitle = normalize(title)
                val wantedArtist = normalize(artist)
                for (i in 0 until recordings.length()) {
                    val item = recordings.optJSONObject(i) ?: continue
                    val credits = item.optJSONArray("artist-credit")
                    val itemArtist = buildString {
                        if (credits != null) for (j in 0 until credits.length()) {
                            val credit = credits.optJSONObject(j) ?: continue
                            append(credit.optJSONObject("artist")?.optString("name").orEmpty())
                        }
                    }
                    val score = (if (normalize(item.optString("title")) == wantedTitle) 2 else 0) +
                        (if (normalize(itemArtist).contains(wantedArtist)) 2 else 0) + item.optInt("score") / 100
                    if (score > best) { best = score; selected = item }
                }
                val item = selected ?: return@withContext null
                val credits = item.optJSONArray("artist-credit")
                val resolvedArtist = buildString {
                    if (credits != null) for (j in 0 until credits.length()) {
                        val credit = credits.optJSONObject(j) ?: continue
                        append(credit.optJSONObject("artist")?.optString("name").orEmpty())
                        append(credit.optString("joinphrase"))
                    }
                }.trim().ifBlank { artist }
                val album = item.optJSONArray("releases")?.optJSONObject(0)?.optString("title").orEmpty()
                EnrichedTrack(item.optString("title", title), resolvedArtist, album,
                    item.optLong("length").coerceAtLeast(0L), item.optString("id"))
            }
        }
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    companion object {
        private val requestMutex = Mutex()
        @Volatile private var lastRequestAt = 0L
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
}
