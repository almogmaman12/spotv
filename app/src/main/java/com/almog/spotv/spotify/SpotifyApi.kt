package com.almog.spotv.spotify

import android.os.SystemClock
import com.almog.spotv.Constants
import com.almog.spotv.DebugLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed class PlaybackResult {
    data class Active(val state: NowPlaying) : PlaybackResult()

    /** Logged in, but nothing is playing (no active device, an ad, or nothing loaded). */
    object Idle : PlaybackResult()

    /** Spotify rejected our login for good. */
    object AuthLost : PlaybackResult()

    data class RateLimited(val retryAfterMs: Long) : PlaybackResult()

    /** Network or server trouble; try again later. */
    object Failed : PlaybackResult()
}

/** The few Spotify Web API calls this app needs. */
class SpotifyApi(private val auth: SpotifyAuth) {

    private class Raw(val code: Int, val body: String, val retryAfterSec: Long, val rttMs: Long)

    private suspend fun call(
        method: String,
        path: String,
        query: String? = null,
    ): Raw? = withContext(Dispatchers.IO) {
        var token = auth.accessToken() ?: return@withContext null
        var result: Raw? = null
        for (attempt in 0..1) {
            val url = Constants.SPOTIFY_API + path + (if (query != null) "?$query" else "")
            val body = if (method == "GET") null else ByteArray(0).toRequestBody(null)
            val request = Request.Builder().url(url).method(method, body)
                .header("Authorization", "Bearer $token").build()
            val started = SystemClock.elapsedRealtime()
            val raw = try {
                TvHttp.client.newCall(request).execute().use { resp ->
                    Raw(
                        resp.code,
                        resp.body?.string().orEmpty(),
                        resp.header("Retry-After")?.toLongOrNull() ?: 0L,
                        SystemClock.elapsedRealtime() - started,
                    )
                }
            } catch (e: IOException) {
                DebugLog.w(TAG, "$method $path failed: ${e.message}")
                return@withContext null
            }
            if (raw.code == 401 && attempt == 0) {
                // The token may have been revoked early: refresh once and retry.
                token = auth.accessToken(forceRefresh = true) ?: return@withContext Raw(401, "", 0L, 0L)
                continue
            }
            result = raw
            break
        }
        result
    }

    suspend fun playback(): PlaybackResult {
        val raw = call("GET", "/me/player", "additional_types=track,episode")
            ?: return if (auth.hasLogin()) PlaybackResult.Failed else PlaybackResult.AuthLost
        return when {
            raw.code == 200 -> parsePlayback(raw.body, raw.rttMs) ?: PlaybackResult.Idle
            raw.code == 204 -> PlaybackResult.Idle
            raw.code == 401 -> if (auth.hasLogin()) PlaybackResult.Failed else PlaybackResult.AuthLost
            raw.code == 429 -> PlaybackResult.RateLimited(maxOf(raw.retryAfterSec, 2L) * 1000L)
            else -> PlaybackResult.Failed
        }
    }

    private fun parsePlayback(text: String, rttMs: Long): PlaybackResult? {
        return try {
            val json = JSONObject(text)
            val item = json.optJSONObject("item") ?: return null
            val isEpisode = item.optString("type") == "episode"
            val artists = item.optJSONArray("artists")
            val names = ArrayList<String>()
            if (artists != null) for (i in 0 until artists.length()) names.add(artists.getJSONObject(i).optString("name"))
            val show = item.optJSONObject("show")
            val album = item.optJSONObject("album")
            val images = (album ?: item).optJSONArray("images") ?: show?.optJSONArray("images")
            val cover = if (images != null && images.length() > 0) images.getJSONObject(0).optString("url") else null
            val device = json.optJSONObject("device")
            val isrc = item.optJSONObject("external_ids")?.optString("isrc").orEmpty()
            // The reply took rtt to get here, so the song is about half of that further along.
            val playing = json.optBoolean("is_playing", false)
            val progress = json.optLong("progress_ms", 0L) + if (playing) rttMs / 2 else 0L
            PlaybackResult.Active(
                NowPlaying(
                    trackId = item.optString("id"),
                    title = item.optString("name"),
                    artist = if (isEpisode) show?.optString("name").orEmpty() else names.joinToString(", "),
                    primaryArtist = if (isEpisode) show?.optString("name").orEmpty() else names.firstOrNull().orEmpty(),
                    album = if (isEpisode) show?.optString("name").orEmpty() else album?.optString("name").orEmpty(),
                    coverUrl = cover?.takeIf { it.isNotEmpty() },
                    durationMs = item.optLong("duration_ms", 0L),
                    progressMs = progress,
                    isPlaying = playing,
                    isrc = isrc,
                    isPodcast = isEpisode,
                    shuffle = json.optBoolean("shuffle_state", false),
                    repeat = json.optString("repeat_state", "off"),
                    deviceName = device?.optString("name")?.takeIf { it.isNotEmpty() },
                    volumePercent = if (device != null && !device.isNull("volume_percent")) device.optInt("volume_percent") else null,
                    takenAt = SystemClock.elapsedRealtime(),
                )
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "Could not read the player reply: ${e.message}")
            null
        }
    }

    /** The next songs in the queue, for fetching their lyrics early and for "Up next". */
    suspend fun queue(): List<QueueItem> {
        val raw = call("GET", "/me/player/queue") ?: return emptyList()
        if (raw.code != 200) return emptyList()
        return try {
            val arr = JSONObject(raw.body).optJSONArray("queue") ?: return emptyList()
            val out = ArrayList<QueueItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("type") == "episode" || item.has("show")) continue
                val artists = item.optJSONArray("artists")
                out.add(
                    QueueItem(
                        id = item.optString("id"),
                        title = item.optString("name"),
                        artist = if (artists != null && artists.length() > 0) artists.getJSONObject(0).optString("name") else "",
                        album = item.optJSONObject("album")?.optString("name").orEmpty(),
                        durationMs = item.optInt("duration_ms", 0),
                        isrc = item.optJSONObject("external_ids")?.optString("isrc").orEmpty(),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun resume() = command("PUT", "/me/player/play")
    suspend fun pause() = command("PUT", "/me/player/pause")
    suspend fun next() = command("POST", "/me/player/next")
    suspend fun previous() = command("POST", "/me/player/previous")
    suspend fun seek(positionMs: Long) = command("PUT", "/me/player/seek", "position_ms=${positionMs.coerceAtLeast(0L)}")
    suspend fun shuffle(on: Boolean) = command("PUT", "/me/player/shuffle", "state=$on")
    suspend fun repeat(mode: String) = command("PUT", "/me/player/repeat", "state=$mode")
    suspend fun volume(percent: Int) = command("PUT", "/me/player/volume", "volume_percent=${percent.coerceIn(0, 100)}")

    /** Fire a player command; returns the HTTP code, or -1 when it could not be sent. */
    private suspend fun command(method: String, path: String, query: String? = null): Int {
        val raw = call(method, path, query) ?: return -1
        if (raw.code >= 400) DebugLog.w(TAG, "$method $path -> ${raw.code} ${raw.body.take(200)}")
        return raw.code
    }

    companion object {
        private const val TAG = "SpotifyApi"
    }
}
