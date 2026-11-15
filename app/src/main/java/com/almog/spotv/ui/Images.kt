package com.almog.spotv.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.almog.spotv.spotify.TvHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Small pictures for lists (covers, artists), kept in memory so scrolling back is instant. */
object ImageCache {
    private val cache = LruCache<String, ImageBitmap>(120)

    suspend fun load(url: String): ImageBitmap? {
        synchronized(cache) { cache.get(url) }?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = TvHttp.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } ?: return null
        synchronized(cache) { cache.put(url, bmp) }
        return bmp
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) null else ImageCache.load(url)
    }
    return state
}
