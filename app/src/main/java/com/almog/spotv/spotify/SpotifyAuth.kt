package com.almog.spotv.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.almog.spotv.Constants
import com.almog.spotv.DebugLog
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * Spotify login with PKCE (no client secret in the app) and token storage.
 * The login itself happens on the viewer's phone, see [PairingServer].
 */
class SpotifyAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    val isConfigured: Boolean get() = Constants.SPOTIFY_CLIENT_ID.isNotBlank()

    fun hasLogin(): Boolean = !prefs.getString(KEY_REFRESH, null).isNullOrEmpty()

    fun logout() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_EXPIRY).remove(KEY_REFRESH).apply()
    }

    /** A valid access token, refreshed when it is about to expire; null when logged out. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val token = prefs.getString(KEY_ACCESS, null)
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        if (!forceRefresh && token != null && System.currentTimeMillis() < expiry - 60_000L) return token
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        withContext(Dispatchers.IO) { refreshNow(refresh) }
    }

    private fun refreshNow(refreshToken: String): String? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", Constants.SPOTIFY_CLIENT_ID)
            .build()
        return try {
            TvHttp.client.newCall(Request.Builder().url(Constants.SPOTIFY_TOKEN_URL).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (resp.isSuccessful && json != null && json.has("access_token")) {
                    store(json)
                    json.getString("access_token")
                } else {
                    DebugLog.e(TAG, "Token refresh failed: HTTP ${resp.code} $text")
                    // Only a rejected refresh token means the login is gone; a hiccup keeps it.
                    if (json?.optString("error") == "invalid_grant") logout()
                    null
                }
            }
        } catch (e: java.io.IOException) {
            DebugLog.e(TAG, "Token refresh network error: ${e.message}")
            null
        }
    }

    /** Trades the code the phone got for tokens. Blocking: call off the main thread. */
    fun exchangeCodeBlocking(code: String, verifier: String): Boolean {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", Constants.SPOTIFY_REDIRECT_URI)
            .add("client_id", Constants.SPOTIFY_CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        return try {
            TvHttp.client.newCall(Request.Builder().url(Constants.SPOTIFY_TOKEN_URL).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (resp.isSuccessful && json != null && json.has("access_token") && json.has("refresh_token")) {
                    store(json)
                    true
                } else {
                    DebugLog.e(TAG, "Code exchange failed: HTTP ${resp.code} $text")
                    false
                }
            }
        } catch (e: java.io.IOException) {
            DebugLog.e(TAG, "Code exchange network error: ${e.message}")
            false
        }
    }

    private fun store(json: JSONObject) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRY, System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
        if (json.has("refresh_token")) editor.putString(KEY_REFRESH, json.getString("refresh_token"))
        editor.apply()
    }

    companion object {
        private const val TAG = "SpotifyAuth"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_EXPIRY = "token_expiry"
        private const val KEY_REFRESH = "refresh_token"

        fun newVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return b64(bytes)
        }

        fun challengeFor(verifier: String): String =
            b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun b64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun authorizeUrl(state: String, challenge: String): String =
            Uri.parse(Constants.SPOTIFY_AUTH_BASE_URL).buildUpon()
                .appendQueryParameter("client_id", Constants.SPOTIFY_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", Constants.SPOTIFY_REDIRECT_URI)
                .appendQueryParameter("scope", Constants.SPOTIFY_SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .build().toString()
    }
}
