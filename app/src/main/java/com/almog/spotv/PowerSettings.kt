package com.almog.spotv

/**
 * The lyrics renderer caps its frame rate with this. A TV is mains powered, so the cap is just a
 * generous 60 fps ceiling (0 would remove it).
 */
object PowerSettings {
    /** Shortest time between lyrics frames. */
    @JvmField
    @Volatile
    var minFrameNanos: Long = 14_000_000L
}
