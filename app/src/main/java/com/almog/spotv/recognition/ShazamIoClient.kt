package com.almog.spotv.recognition

import com.almog.spotv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Calls the optional ShazamIO bridge. ShazamIO runs on the server, not in the APK. */
class ShazamIoClient(private val baseUrl: String = BuildConfig.MUSIC_RECOGNITION_URL, private val http: OkHttpClient = defaultHttp) {
    suspend fun recognize(sample: ByteArray): RecognizedTrack? = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isBlank() || sample.isEmpty()) return@withContext null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "sample.wav", sample.toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url("$root/v1/recognize").post(body).header("Accept", "application/json").build()
        http.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            if (!json.optBoolean("matched")) return@withContext null
            val track = json.optJSONObject("track") ?: return@withContext null
            val title = track.optString("title").trim()
            val artist = track.optString("artist").trim()
            if (title.isBlank() || artist.isBlank()) return@withContext null
            RecognizedTrack(track.optString("provider", "shazamio"), title, artist,
                track.optString("album"), track.optLong("durationMs").coerceAtLeast(0L),
                track.optString("providerTrackId"), track.optString("artworkUrl"), track.optString("trackUrl"),
                track.optLong("matchOffsetMs").coerceAtLeast(0L))
        }
    }

    companion object {
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    }
}
