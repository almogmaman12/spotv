package com.almog.spotv.spotify

import com.almog.spotv.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class NativeDjSession(
    val tracks: List<String>,
    val nextPage: String,
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

            parseDjPage(nativeDjSession("", language))
        }

    /** Fetch another DJ page using the continuation URL returned by Spotify. */
    suspend fun nextDjPage(page: String, language: String = "en"): NativeDjSession =
        withContext(Dispatchers.IO) {
            require(page.startsWith("hm://")) { "Invalid Spotify DJ continuation page." }
            check(isAvailable()) { "The native Spotify engine is unavailable on this device." }
            parseDjPage(nativeDjSession(page, language))
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
        return NativeDjSession(
            tracks = tracks.distinct(),
            nextPage = json.optString("next"),
        )
    }

    @JvmStatic
    private external fun nativeVersion(): Int

    @JvmStatic
    private external fun nativeConnect(accessToken: String, clientId: String): String

    @JvmStatic
    private external fun nativeDjSession(page: String, language: String): String
}
