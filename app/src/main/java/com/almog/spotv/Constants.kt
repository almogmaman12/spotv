package com.almog.spotv

/** App-wide settings. Secrets come from local.properties through BuildConfig (see app/build.gradle). */
object Constants {
    /** SharedPreferences file shared by the lyrics engine and the Spotify tokens. */
    const val PREF_NAME = "SpotifyPrefs"

    /** Active lyric font size in sp; the lyrics engine scales its own sizes by value / 32. */
    const val PREF_KEY_LYRICS_FONT_SIZE = "lyrics_font_size_sp"

    /** Lyrics size as a percentage of the size that fits the screen (50-200). Set in the settings panel. */
    const val PREF_KEY_LYRICS_SIZE_PERCENT = "lyrics_size_percent"

    /** Shifts lyrics against the audio (ms). Positive shows lyrics earlier. Handy for TVs with audio delay. */
    const val PREF_KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"

    val SPOTIFY_CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID
    val SPOTIFY_REDIRECT_URI: String = BuildConfig.SPOTIFY_REDIRECT_URI
    val SPICY_LYRICS_API_KEY: String = BuildConfig.SPICY_LYRICS_API_KEY

    const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val SPOTIFY_AUTH_BASE_URL = "https://accounts.spotify.com/authorize"
    const val SPOTIFY_API = "https://api.spotify.com/v1"
    const val SPOTIFY_SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state"

    /** Port range tried for the phone-login page the TV serves on the local network. */
    const val PAIRING_PORT_FIRST = 8765
    const val PAIRING_PORT_LAST = 8775

    const val POLL_PLAYING_MS = 3_000L
    const val POLL_PAUSED_MS = 6_000L
    const val POLL_IDLE_MS = 8_000L
    const val SEEK_STEP_MS = 10_000L
    const val CONTROLS_HIDE_MS = 8_000L
}
