package com.almog.spotv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import com.almog.spotv.lyrics.mobile.canvas.StackBlur
import com.almog.spotv.spotify.TvHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** An album cover ready to draw: the picture, a tiny version that blurs when scaled up, and its main colour. */
class Cover(
    val image: ImageBitmap,
    val blurred: ImageBitmap,
    val accent: Color,
    /** A few more colours from the picture, for the living background. */
    val palette: List<Color> = listOf(accent),
)

object CoverLoader {
    private val cache = LruCache<String, Cover>(6)

    suspend fun load(url: String): Cover? {
        synchronized(cache) { cache.get(url) }?.let { return it }
        val cover = withContext(Dispatchers.IO) { download(url) } ?: return null
        synchronized(cache) { cache.put(url, cover) }
        return cover
    }

    private fun download(url: String): Cover? = try {
        val bytes: ByteArray? = TvHttp.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
        if (bytes == null) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1280) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap == null) {
                null
            } else {
                // A small copy, box-blurred, then scaled up smoothly: a soft backdrop on every TV GPU.
                val small = StackBlur.blur(Bitmap.createScaledBitmap(bitmap, 96, 96, true), 7, 3)
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val fallback = 0xFF1DB954.toInt()
                val accent = palette.getVibrantColor(palette.getMutedColor(palette.getDominantColor(fallback)))
                val swatches = listOfNotNull(
                    palette.vibrantSwatch, palette.lightVibrantSwatch, palette.darkVibrantSwatch,
                    palette.mutedSwatch, palette.dominantSwatch,
                ).map { Color(it.rgb) }.distinct()
                Cover(bitmap.asImageBitmap(), small.asImageBitmap(), Color(accent), swatches.ifEmpty { listOf(Color(accent)) })
            }
        }
    } catch (e: Exception) {
        null
    }
}
