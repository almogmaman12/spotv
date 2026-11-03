package com.almog.spotv

import android.content.Context

/** Settings that belong to the TV app itself (the lyrics engine keeps its own keys). */
object TvPrefs {
    const val LYRICS_STYLE = "lyrics_style"          // "classic" | "kinetic"
    const val BEAT_PULSE = "kinetic_beat_pulse"      // Boolean
    const val POSTER = "kinetic_poster"              // Boolean
    const val DEPTH_BLUR = "kinetic_depth_blur"      // Boolean
    const val LIVING_BG = "kinetic_living_bg"        // Boolean
    const val DRIFT = "ui_drift"                     // Boolean (burn-in protection)
    const val AMBIENT_MINUTES = "ui_ambient_minutes" // Int, 0 = off
    const val INTRO = "ui_song_intro"                // Boolean

    fun prefs(c: Context) = c.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
}
