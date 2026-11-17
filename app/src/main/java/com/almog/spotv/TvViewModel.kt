package com.almog.spotv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almog.spotv.spotify.Catalog
import com.almog.spotv.spotify.PairingServer
import com.almog.spotv.spotify.PlayerRepository
import com.almog.spotv.spotify.SpotifyApi
import com.almog.spotv.spotify.SpotifyAuth
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Login state, the phone-login page and the player. Survives screen rotation / activity recreation. */
class TvViewModel(app: Application) : AndroidViewModel(app) {
    val auth = SpotifyAuth(app)
    private val api = SpotifyApi(auth)

    private val _loggedIn = MutableStateFlow(auth.hasLogin())
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** Address of the login page while waiting for the phone; null when none (offline or logged in). */
    private val _pairingUrl = MutableStateFlow<String?>(null)
    val pairingUrl: StateFlow<String?> = _pairingUrl

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError

    val catalog = Catalog(api)

    val autoQueue = com.almog.spotv.spotify.AutoQueue(catalog, viewModelScope) {
        TvPrefs.prefs(app).getBoolean(TvPrefs.AUTOPLAY, true)
    }

    val player = PlayerRepository(api, viewModelScope) {
        auth.logout()
        _loggedIn.value = false
    }

    val dj = DjVoice(app, viewModelScope, { player.volumeBy(it) }) {
        TvPrefs.prefs(app).getBoolean(TvPrefs.DJ, false)
    }

    override fun onCleared() {
        dj.shutdown()
        super.onCleared()
    }

    /** Serves the phone-login page until a phone has logged in. Cancel it to stop. */
    suspend fun runPairing() {
        val server = PairingServer(auth)
        try {
            _pairingError.value = null
            server.start()
            _pairingUrl.value = server.url
            server.loggedIn.await()
            _loggedIn.value = true
        } catch (e: IOException) {
            _pairingError.value = e.message ?: "Can't start the login page"
        } finally {
            server.stop()
            _pairingUrl.value = null
        }
    }

    private val _settingsOpen = MutableStateFlow(false)

    /** Whether the settings panel is showing. */
    val settingsOpen: StateFlow<Boolean> = _settingsOpen

    private val _settingsVersion = MutableStateFlow(0)

    /** Goes up by one whenever a setting changes, so the screen and lyrics engine re-read them. */
    val settingsVersion: StateFlow<Int> = _settingsVersion

    fun openSettings() { _settingsOpen.value = true }
    fun closeSettings() { _settingsOpen.value = false }
    fun toggleSettings() { _settingsOpen.value = !_settingsOpen.value }
    fun settingsChanged() { _settingsVersion.value = _settingsVersion.value + 1 }

    private val _browseOpen = MutableStateFlow(false)

    /** Whether the library / search screen is showing. */
    val browseOpen: StateFlow<Boolean> = _browseOpen

    /** When set, the browse screen opens straight on this artist / album / playlist ("go to artist"). */
    private val _browseTarget = MutableStateFlow<com.almog.spotv.spotify.Entry?>(null)
    val browseTarget: StateFlow<com.almog.spotv.spotify.Entry?> = _browseTarget

    fun openBrowse(target: com.almog.spotv.spotify.Entry? = null) {
        _browseTarget.value = target
        _browseOpen.value = true
    }

    private val _sleepAt = MutableStateFlow<Long?>(null)

    /** Wall-clock time when the music will pause by itself, or null when no sleep timer is set. */
    val sleepAt: StateFlow<Long?> = _sleepAt
    private var sleepJob: kotlinx.coroutines.Job? = null

    fun setSleepMinutes(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepAt.value = null
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            player.pause()
            _sleepAt.value = null
            AppStatus.report("sleep", "Sleep timer: music paused", AppStatus.INFO, 6000)
        }
    }
    fun closeBrowse() { _browseOpen.value = false; _browseTarget.value = null }

    /**
     * Start something from the library. With no active Spotify device (Spotify says 404) the music is moved
     * to the first device we can find, so a song can be started from the TV even when nothing was playing.
     */
    /** Play an album / playlist / artist in shuffled order. Shuffle is set again after the start, as a fresh device may reset it. */
    fun shuffleFromBrowse(contextUri: String, label: String) {
        viewModelScope.launch {
            api.shuffle(true)
            playFromBrowse(contextUri = contextUri, label = label)
            kotlinx.coroutines.delay(1500)
            api.shuffle(true)
            player.refreshSoon()
        }
    }

    fun playFromBrowse(
        contextUri: String? = null,
        uris: List<String>? = null,
        offsetUri: String? = null,
        label: String = "",
    ) {
        viewModelScope.launch {
            var code = catalog.play(contextUri, uris, offsetUri)
            if (code == 404) {
                val own = com.almog.spotv.speaker.SpeakerState.deviceId.value
                val device = catalog.devices().let { list -> list.firstOrNull { it.active } ?: list.firstOrNull { it.id == own } ?: list.firstOrNull() }
                if (device == null) {
                    AppStatus.report("play", "Open Spotify on a device first (phone, speaker or this TV)", AppStatus.WARN, 6000)
                    return@launch
                }
                code = catalog.play(contextUri, uris, offsetUri, device.id)
            }
            if (code in 200..299) {
                if (label.isNotEmpty()) AppStatus.report("play", "Playing $label", AppStatus.INFO, 2500)
                _browseOpen.value = false
                player.refreshSoon()
            } else {
                AppStatus.report("play", "Couldn't start that (Spotify said $code)", AppStatus.WARN, 5000)
            }
        }
    }

    fun addToQueue(uri: String, label: String) {
        viewModelScope.launch {
            val code = catalog.addToQueue(uri)
            if (code in 200..299) {
                AppStatus.report("queue", "Added to queue: $label", AppStatus.INFO, 2500)
            } else {
                AppStatus.report("queue", "Couldn't add to the queue (Spotify said $code)", AppStatus.WARN, 4000)
            }
        }
    }

    fun transferTo(deviceId: String, name: String) {
        viewModelScope.launch {
            val code = catalog.transfer(deviceId)
            if (code in 200..299) {
                AppStatus.report("device", "Playing on $name", AppStatus.INFO, 2500)
                player.refreshSoon()
            } else {
                AppStatus.report("device", "Couldn't switch to $name (Spotify said $code)", AppStatus.WARN, 4000)
            }
        }
    }

    fun logout() {
        auth.logout()
        _settingsOpen.value = false
        _browseOpen.value = false
        _loggedIn.value = false
    }
}
