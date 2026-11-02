package com.almog.spotv

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What is wrong right now, for the banner: lyrics missing, Spotify unreachable. Items are keyed, so a
 * repeat report only refreshes it and a success clears it. Safe to call from any thread.
 */
object AppStatus {
    const val INFO = 0
    const val WARN = 1
    const val ERROR = 2

    data class Status(val message: String, val severity: Int)

    private class Item(val message: String, val severity: Int, val seq: Long) {
        var expiry: Runnable? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<String, Item>()
    private var counter = 0L
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    /** [ttlMs] 0 keeps it until [clear]. */
    @JvmStatic
    fun report(key: String, message: String, severity: Int, ttlMs: Long) {
        main.post {
            items.remove(key)?.expiry?.let { main.removeCallbacks(it) }
            val item = Item(message, severity, ++counter)
            if (ttlMs > 0) {
                val expiry = Runnable { clearNow(key) }
                item.expiry = expiry
                main.postDelayed(expiry, ttlMs)
            }
            items[key] = item
            publish()
        }
    }

    @JvmStatic
    fun clear(key: String) {
        main.post { clearNow(key) }
    }

    private fun clearNow(key: String) {
        items.remove(key)?.expiry?.let { main.removeCallbacks(it) }
        publish()
    }

    private fun publish() {
        val top = items.values.maxWithOrNull(compareBy<Item>({ it.severity }, { it.seq }))
        _status.value = top?.let { Status(it.message, it.severity) }
    }
}
