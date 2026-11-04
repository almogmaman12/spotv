package com.almog.spotv.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** AcoustID lookup. A Chromaprint fingerprint and the user's own API key are required. */
class AcoustIdClient(private val http: OkHttpClient = defaultHttp) {
    suspend fun lookup(fingerprint: String, durationSeconds: Int, clientKey: String): AcoustIdMatch? =
        withContext(Dispatchers.IO) {
            if (fingerprint.isBlank() || durationSeconds <= 0 || clientKey.isBlank()) return@withContext null
            val params = listOf("client" to clientKey, "duration" to durationSeconds.toString(),
                "fingerprint" to fingerprint, "meta" to "recordings+releases+tracks+compress")
                .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}" }
            val request = Request.Builder().url("https://api.acoustid.org/v2/lookup?$params")
                .header("User-Agent", "SpotTV/0.1.0").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optString("status") != "ok") return@withContext null
                val results = root.optJSONArray("results") ?: return@withContext null
                var best: AcoustIdMatch? = null
                for (i in 0 until results.length()) {
                    val result = results.optJSONObject(i) ?: continue
                    val recordings = result.optJSONArray("recordings") ?: continue
                    for (j in 0 until recordings.length()) {
                        val recording = recordings.optJSONObject(j) ?: continue
                        val title = recording.optString("title")
                        val artist = recording.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                        if (title.isBlank() || artist.isBlank()) continue
                        val match = AcoustIdMatch(title, artist, result.optDouble("score"), recording.optString("id"))
                        if (best == null || match.score > best.score) best = match
                    }
                }
                best
            }
        }

    companion object {
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
}
data class AcoustIdMatch(val title: String, val artist: String, val score: Double, val musicBrainzRecordingId: String)
