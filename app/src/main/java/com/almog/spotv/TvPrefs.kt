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
    const val AUTOPLAY = "autoplay_enabled"          // Boolean, default true: keep music going when the queue ends
    const val DJ = "dj_voice_enabled"                // Boolean, default false
    const val SPEAKER = "speaker_enabled"            // Boolean (experimental in-app speaker)

    fun prefs(c: Context) = c.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
}
