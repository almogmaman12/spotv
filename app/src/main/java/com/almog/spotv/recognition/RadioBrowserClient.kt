package com.almog.spotv.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Free Radio Browser directory search. Live track data is only available if the stream publishes it. */
class RadioBrowserClient(private val http: OkHttpClient = defaultHttp) {
    suspend fun searchStations(query: String, limit: Int = 20): List<RadioStation> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val url = "https://all.api.radio-browser.info/json/stations/search?name=$encoded&limit=${limit.coerceIn(1, 50)}&hidebroken=true"
        val request = Request.Builder().url(url).header("User-Agent", "SpotTV/0.1.0 (https://github.com/almogmaman12/spotv)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                    if (stream.isBlank()) continue
                    add(RadioStation(item.optString("stationuuid"), item.optString("name"), stream,
                        item.optString("homepage"), item.optString("country"), item.optString("tags"),
                        item.optString("codec"), item.optInt("bitrate")))
                }
            }
        }
    }
    companion object {
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
}
