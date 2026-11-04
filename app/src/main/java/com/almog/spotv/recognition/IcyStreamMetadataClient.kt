package com.almog.spotv.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RadioNowPlaying(val artist: String, val title: String, val rawStreamTitle: String)

/** Reads ICY StreamTitle metadata from stations that support the Icy-MetaData request header. */
class IcyStreamMetadataClient(private val http: OkHttpClient = defaultHttp) {
    suspend fun readCurrentTrack(station: RadioStation): RadioNowPlaying? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(station.streamUrl)
            .header("Icy-MetaData", "1")
            .header("User-Agent", "SpotTV/0.1.0")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val interval = response.header("icy-metaint")?.toIntOrNull() ?: return@withContext null
            val stream = response.body?.byteStream() ?: return@withContext null
            var raw: String? = null
            repeat(5) {
                if (raw == null) raw = IcyMetadataParser.readNextTitle(stream, interval)
            }
            val value = raw ?: return@withContext null
            val split = value.split(" - ", limit = 2)
            if (split.size == 2) RadioNowPlaying(split[0].trim(), split[1].trim(), value)
            else RadioNowPlaying("", value.trim(), value)
        }
    }

    companion object {
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
    }
}
