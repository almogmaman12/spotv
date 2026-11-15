package com.almog.spotv.speaker

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.almog.spotv.AppStatus
import com.almog.spotv.DebugLog
import com.almog.spotv.spotify.SpotifyAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the in-app speaker is doing, for the settings screen and for picking where to play. */
object SpeakerState {
    private val _deviceId = MutableStateFlow<String?>(null)

    /** The Spotify device id of this TV's own speaker once it is ready; null when off or not connected. */
    val deviceId: StateFlow<String?> = _deviceId

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status

    internal fun set(deviceId: String?, status: String) {
        _deviceId.value = deviceId
        _status.value = status
    }
}

/**
 * EXPERIMENTAL: runs Spotify's Web Playback SDK in a hidden WebView so this TV shows up as a Spotify
 * device ("SpoTV (model)") and plays music itself. It needs Spotify Premium and a WebView with Widevine
 * (most Android TVs have one); where that is missing it reports why and the rest of the app keeps working.
 */
class SpeakerController(
    private val activity: Activity,
    private val auth: SpotifyAuth,
    private val scope: CoroutineScope,
) {
    private var web: WebView? = null
    private var refreshJob: Job? = null

    @Volatile
    private var token: String = ""

    val running: Boolean get() = web != null

    fun start() {
        if (web != null) return
        SpeakerState.set(null, "Starting…")
        scope.launch {
            val t = auth.accessToken()
            if (t == null) {
                SpeakerState.set(null, "Log in first")
                return@launch
            }
            token = t
            try {
                createWebView()
            } catch (e: Exception) {
                DebugLog.e(TAG, "WebView failed: ${e.message}")
                SpeakerState.set(null, "This TV has no usable WebView")
                AppStatus.report("speaker", "In-app speaker isn't available on this TV", AppStatus.WARN, 6000)
                return@launch
            }
            refreshJob = scope.launch {
                while (true) {
                    delay(20 * 60_000L)
                    auth.accessToken(forceRefresh = true)?.let { token = it }
                }
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        SpeakerState.set(null, "Off")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val w = WebView(activity)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // The SDK refuses unknown browsers: look like desktop Chrome.
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        w.webChromeClient = object : WebChromeClient() {
            // Spotify's audio is DRM-protected: allow the WebView to use it.
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
        w.addJavascriptInterface(Bridge(), "Android")
        activity.addContentView(w, ViewGroup.LayoutParams(1, 1))
        val html = activity.assets.open("speaker.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://spotv.local/", html, "text/html", "utf-8", null)
        web = w
    }

    private inner class Bridge {
        @JavascriptInterface
        fun name(): String = "SpoTV (${Build.MODEL})"

        @JavascriptInterface
        fun token(): String = token

        @JavascriptInterface
        fun ready(deviceId: String) {
            SpeakerState.set(deviceId, "Ready")
            AppStatus.report("speaker", "In-app speaker is ready: choose “SpoTV” under Devices", AppStatus.INFO, 5000)
        }

        @JavascriptInterface
        fun notReady() {
            SpeakerState.set(null, "Disconnected")
        }

        @JavascriptInterface
        fun error(kind: String, message: String) {
            DebugLog.w(TAG, "speaker $kind: $message")
            val text = when (kind) {
                "account_error" -> "Needs Spotify Premium"
                "authentication_error" -> "Log in again to allow the speaker"
                "initialization_error" -> "This TV's WebView can't play Spotify (no DRM?)"
                else -> message.ifBlank { kind }
            }
            SpeakerState.set(null, text)
            if (kind != "playback_error") AppStatus.report("speaker", "In-app speaker: $text", AppStatus.WARN, 8000)
        }
    }

    companion object {
        private const val TAG = "SpeakerController"
    }
}
