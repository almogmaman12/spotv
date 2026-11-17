package com.almog.spotv.spotify

/**
 * Entry point for SpotTV's native librespot playback engine.
 * Loaded lazily so existing installs remain usable during migration.
 */
object NativePlaybackEngine {
    private const val LIBRARY = "spotvcore"

    @Volatile
    private var loaded = false

    @Synchronized
    fun isAvailable(): Boolean = runCatching {
        if (!loaded) {
            System.loadLibrary(LIBRARY)
            loaded = true
        }
        nativeVersion() > 0
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeVersion(): Int
}
