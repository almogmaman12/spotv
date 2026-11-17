package com.almog.spotv.spotify

import com.almog.spotv.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NativeDjNarrationLine(
    val ssml: String,
    val voice: String,
    val provider: String,
    val loudness: Double?,
    val truePeak: Double?,
)

data class NativeDjTrackNarration(
    val intro: NativeDjNarrationLine?,
    val jump: NativeDjNarrationLine?,
    val outro: NativeDjNarrationLine?,
)

data class NativeDjSession(
    val tracks: List<String>,
    val nextPage: String,
    val narration: Map<String, NativeDjTrackNarration>,
    val narrationScheduled: Int,
)

/**
 * Entry point for SpotV's native Spotify session and future Connect playback.
 *
 * DJ sessions are requested through the authenticated native session. The
 * current milestone retrieves Spotify's real DJ track pages; narration
 * synthesis and mixed audio output are deliberately not advertised yet.
 */
object NativePlaybackEngine {
    private const val LIBRARY = "spotvcore"

    @Volatile
    private var loaded = false

    @Synchronized
    fun isAvailable(): Boolean = runCatching {
        if (!loaded) {
            System.loadLibrary(LIBRARY)
            loaded = true
        }
        nativeVersion() > 0
    }.getOrDefault(false)

    /** Connect once and fetch the first real Spotify DJ session page. */
    suspend fun startDj(auth: SpotifyAuth, language: String = "en"): NativeDjSession =
        withContext(Dispatchers.IO) {
            val token = auth.accessToken()
                ?: throw IllegalStateException("Spotify login has expired. Sign in again.")
            check(isAvailable()) { "The native Spotify engine is unavailable on this device." }

            val connection = nativeConnect(token, Constants.SPOTIFY_CLIENT_ID)
            check(connection == "connected") { connection }

            prefetchNarration(parseDjPage(nativeDjSession("", language)))
        }

    /** Clear the native session and its authenticated connection when the account signs out. */
    fun disconnect() {
        if (loaded) runCatching { nativeDisconnect() }
    }

    /** Fetch another DJ page using the continuation URL returned by Spotify. */
    suspend fun nextDjPage(page: String, language: String = "en"): NativeDjSession =
        withContext(Dispatchers.IO) {
            require(page.startsWith("hm://")) { "Invalid Spotify DJ continuation page." }
            check(isAvailable()) { "The native Spotify engine is unavailable on this device." }
            prefetchNarration(parseDjPage(nativeDjSession(page, language)))
        }

    private fun prefetchNarration(session: NativeDjSession): NativeDjSession {
        if (session.tracks.isEmpty()) return session
        val result = runCatching {
            nativePrefetchDj(JSONArray(session.tracks).toString())
        }.getOrDefault("scheduled:0")
        val scheduled = result.substringAfter("scheduled:", "0").toIntOrNull() ?: 0
        return session.copy(narrationScheduled = scheduled)
    }

    private fun parseDjPage(raw: String): NativeDjSession {
        val json = JSONObject(raw)
        val error = json.optString("error")
        check(error.isEmpty()) { error }
        val tracksJson = json.optJSONArray("tracks")
        val tracks = if (tracksJson == null) emptyList() else
            (0 until tracksJson.length()).mapNotNull { index ->
                tracksJson.optString(index).takeIf { it.startsWith("spotify:track:") }
            }
        val narrationJson = json.optJSONObject("narration") ?: JSONObject()
        val narration = buildMap {
            val keys = narrationJson.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                val lines = narrationJson.optJSONObject(uri) ?: continue
                put(uri, NativeDjTrackNarration(
                    intro = parseLine(lines.optJSONObject("intro")),
                    jump = parseLine(lines.optJSONObject("jump")),
                    outro = parseLine(lines.optJSONObject("outro")),
                ))
            }
        }
        return NativeDjSession(
            tracks = tracks.distinct(),
            nextPage = json.optString("next"),
            narration = narration,
            narrationScheduled = 0,
        )
    }

    private fun parseLine(json: JSONObject?): NativeDjNarrationLine? {
        if (json == null) return null
        val ssml = json.optString("ssml").takeIf { it.isNotBlank() } ?: return null
        return NativeDjNarrationLine(
            ssml = ssml,
            voice = json.optString("voice"),
            provider = json.optString("provider"),
            loudness = json.optString("loudness").toDoubleOrNull(),
            truePeak = json.optString("true_peak").toDoubleOrNull(),
        )
    }

    @JvmStatic
    private external fun nativeVersion(): Int

    @JvmStatic
    private external fun nativeConnect(accessToken: String, clientId: String): String

    @JvmStatic
    private external fun nativeDjSession(page: String, language: String): String

    @JvmStatic
    private external fun nativePrefetchDj(tracksJson: String): String

    @JvmStatic
    private external fun nativeDisconnect()
}
