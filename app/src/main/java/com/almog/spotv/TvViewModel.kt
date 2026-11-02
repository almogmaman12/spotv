package com.almog.spotv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almog.spotv.spotify.PairingServer
import com.almog.spotv.spotify.PlayerRepository
import com.almog.spotv.spotify.SpotifyApi
import com.almog.spotv.spotify.SpotifyAuth
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    val player = PlayerRepository(api, viewModelScope) {
        auth.logout()
        _loggedIn.value = false
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

    fun logout() {
        auth.logout()
        _loggedIn.value = false
    }
}
