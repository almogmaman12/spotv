package com.almog.spotv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.spotv.BuildConfig
import com.almog.spotv.Constants
import com.almog.spotv.TvPrefs
import com.almog.spotv.lyrics.mobile.LyricsFonts
import com.almog.spotv.lyrics.mobile.MobileLyricsSources
import com.almog.spotv.lyrics.mobile.MobileTranslation
import com.almog.spotv.lyrics.repository.LyricsRepository

private val Green = Color(0xFF1DB954)

private val TranslationLanguages = listOf(
    "" to "Off", "en" to "English", "he" to "Hebrew", "ar" to "Arabic", "es" to "Spanish",
    "fr" to "French", "de" to "German", "ru" to "Russian", "pt" to "Portuguese", "it" to "Italian",
    "tr" to "Turkish", "hi" to "Hindi",
)

/**
 * The settings, as a panel over the right-hand side of the screen so lyric changes show live next to it.
 * Up / down move between rows; left / right change a value; OK toggles or picks. Back closes it.
 */
@Composable
fun SettingsPanel(
    screenHeightDp: Float,
    onChanged: () -> Unit,
    onLogout: () -> Unit,
    onStartAudioRecognition: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE) }
    val s = screenHeightDp / 540f
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Retry briefly: the screen behind may still be handing focus around as the panel opens.
        repeat(5) {
            try {
                first.requestFocus()
            } catch (_: IllegalStateException) {
            }
            kotlinx.coroutines.delay(120)
        }
    }

    // Current values, mirrored from the preferences so rows redraw when they change.
    var sizePct by remember { mutableStateOf(prefs.getInt(Constants.PREF_KEY_LYRICS_SIZE_PERCENT, 100)) }
    var fontKey by remember { mutableStateOf(prefs.getString(LyricsFonts.PREF_LYRICS_FONT, LyricsFonts.DEFAULT_KEY) ?: LyricsFonts.DEFAULT_KEY) }
    var offsetMs by remember { mutableStateOf(prefs.getInt(Constants.PREF_KEY_LYRICS_OFFSET_MS, 0)) }
    var target by remember { mutableStateOf(prefs.getString(MobileTranslation.PREF_TRANSLATE_TARGET, "") ?: "") }
    var replace by remember { mutableStateOf(prefs.getString(MobileTranslation.PREF_TRANSLATION_MODE, "under") == "replace") }
    var romanize by remember { mutableStateOf(prefs.getBoolean(MobileTranslation.PREF_ROMANIZE, false)) }
    var notePct by remember { mutableStateOf(prefs.getInt(LyricsFonts.PREF_NOTE_SIZE, 100)) }

    var style by remember { mutableStateOf(prefs.getString(TvPrefs.LYRICS_STYLE, "classic") ?: "classic") }
    var beatPulse by remember { mutableStateOf(prefs.getBoolean(TvPrefs.BEAT_PULSE, false)) }
    var poster by remember { mutableStateOf(prefs.getBoolean(TvPrefs.POSTER, false)) }
    var depthBlur by remember { mutableStateOf(prefs.getBoolean(TvPrefs.DEPTH_BLUR, false)) }
    var livingBg by remember { mutableStateOf(prefs.getBoolean(TvPrefs.LIVING_BG, true)) }
    var drift by remember { mutableStateOf(prefs.getBoolean(TvPrefs.DRIFT, true)) }
    var intro by remember { mutableStateOf(prefs.getBoolean(TvPrefs.INTRO, true)) }
    var ambient by remember { mutableStateOf(prefs.getInt(TvPrefs.AMBIENT_MINUTES, 5)) }

    fun flip(key: String, now: Boolean) { prefs.edit().putBoolean(key, now).apply(); onChanged() }

    val sources = remember { MobileLyricsSources.sources() }
    val enabled = remember { mutableStateMapOf<String, Boolean>().apply { sources.forEach { put(it.id, it.enabled) } } }
    val disclosed = remember { mutableStateMapOf<String, Boolean>() }
    var consentFor by remember { mutableStateOf<String?>(null) }

    var confirmLogout by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    fun <T> step(list: List<T>, current: T, delta: Int): T {
        val i = list.indexOf(current).coerceAtLeast(0)
        return list[(i + delta + list.size) % list.size]
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.90f))
            .padding(horizontal = (36f * s).dp, vertical = (28f * s).dp)
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Settings", color = Color.White, fontSize = (30f * s).sp, fontWeight = FontWeight.Bold)
            Text(
                "◀ ▶ change   OK select   Back close",
                color = Color.White.copy(alpha = 0.5f), fontSize = (14f * s).sp,
            )

            Header("Lyrics", s)
            fun toggleStyle() {
                style = if (style == "kinetic") "classic" else "kinetic"
                prefs.edit().putString(TvPrefs.LYRICS_STYLE, style).apply()
                onChanged()
            }
            SettingRow(
                "Lyrics style", if (style == "kinetic") "Kinetic" else "Classic", s, focusRequester = first,
                note = "Kinetic: one huge line at a time; each word lights up as it is sung.",
                onLeft = { toggleStyle() }, onRight = { toggleStyle() },
            )
            if (style == "kinetic") {
                SettingRow("Beat pulse", if (beatPulse) "On" else "Off", s,
                    onLeft = { beatPulse = !beatPulse; flip(TvPrefs.BEAT_PULSE, beatPulse) },
                    onRight = { beatPulse = !beatPulse; flip(TvPrefs.BEAT_PULSE, beatPulse) })
                SettingRow("Living background", if (livingBg) "On" else "Off", s,
                    onLeft = { livingBg = !livingBg; flip(TvPrefs.LIVING_BG, livingBg) },
                    onRight = { livingBg = !livingBg; flip(TvPrefs.LIVING_BG, livingBg) })
            }
            SettingRow(
                "Text size", "$sizePct%", s,
                onLeft = { sizePct = (sizePct - 10).coerceAtLeast(50); prefs.edit().putInt(Constants.PREF_KEY_LYRICS_SIZE_PERCENT, sizePct).apply(); onChanged() },
                onRight = { sizePct = (sizePct + 10).coerceAtMost(200); prefs.edit().putInt(Constants.PREF_KEY_LYRICS_SIZE_PERCENT, sizePct).apply(); onChanged() },
            )
            val fontKeys = LyricsFonts.OPTIONS.map { it.key }
            fun setFont(delta: Int) {
                fontKey = step(fontKeys, fontKey, delta)
                prefs.edit().putString(LyricsFonts.PREF_LYRICS_FONT, fontKey).apply()
                onChanged()
            }
            SettingRow(
                "Font", LyricsFonts.find(fontKey).label, s,
                onLeft = { setFont(-1) }, onRight = { setFont(1) },
            )
            SettingRow(
                "Timing", offsetLabel(offsetMs), s,
                note = "If lyrics come too late for your TV or soundbar, move them earlier.",
                onLeft = { offsetMs = (offsetMs - 100).coerceAtLeast(-3000); prefs.edit().putInt(Constants.PREF_KEY_LYRICS_OFFSET_MS, offsetMs).apply(); onChanged() },
                onRight = { offsetMs = (offsetMs + 100).coerceAtMost(3000); prefs.edit().putInt(Constants.PREF_KEY_LYRICS_OFFSET_MS, offsetMs).apply(); onChanged() },
            )
            val codes = TranslationLanguages.map { it.first }
            fun setTarget(delta: Int) {
                target = step(codes, target, delta)
                prefs.edit().putString(MobileTranslation.PREF_TRANSLATE_TARGET, target).apply()
                onChanged()
            }
            SettingRow(
                "Translate to", TranslationLanguages.firstOrNull { it.first == target }?.second ?: target, s,
                note = "Sends the lyrics text to Google Translate when on.",
                onLeft = { setTarget(-1) }, onRight = { setTarget(1) },
            )
            fun toggleReplace() {
                replace = !replace
                prefs.edit().putString(MobileTranslation.PREF_TRANSLATION_MODE, if (replace) "replace" else "under").apply()
                onChanged()
            }
            SettingRow(
                "Translation shows", if (replace) "Instead of the line" else "Under the line", s,
                onLeft = { toggleReplace() }, onRight = { toggleReplace() },
            )
            fun toggleRomanize() {
                romanize = !romanize
                prefs.edit().putBoolean(MobileTranslation.PREF_ROMANIZE, romanize).apply()
                onChanged()
            }
            SettingRow(
                "Romanize", if (romanize) "On" else "Off", s,
                note = "Latin letters for Japanese, Korean, Chinese, Russian, Greek...",
                onLeft = { toggleRomanize() }, onRight = { toggleRomanize() },
            )
            SettingRow(
                "Translation text size", "$notePct%", s,
                onLeft = { notePct = (notePct - 10).coerceAtLeast(50); prefs.edit().putInt(LyricsFonts.PREF_NOTE_SIZE, notePct).apply(); onChanged() },
                onRight = { notePct = (notePct + 10).coerceAtMost(150); prefs.edit().putInt(LyricsFonts.PREF_NOTE_SIZE, notePct).apply(); onChanged() },
            )

            Header("Music recognition", s)
            SettingRow(
                "Cross-app song metadata", "Open", s,
                note = "Allow SpotTV in Android Notification access to read song details from other TV apps.",
                onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
                },
            )
            SettingRow(
                "Start audio recognition", "Start", s,
                note = "Requests permission to capture TV audio. If an app blocks internal capture, SpotTV falls back to the microphone. Requires a configured free recognition bridge.",
                onClick = onStartAudioRecognition,
            )

            if (sources.isNotEmpty()) {
                Header("Lyrics sources", s)
                for (src in sources) {
                    val on = enabled[src.id] ?: src.enabled
                    val needsConsent = src.needsConsent && disclosed[src.id] != true
                    val asking = consentFor == src.id
                    val note = when {
                        asking -> (src.note ?: "This source gets the song's title and artist.") + " Press OK again to allow."
                        else -> src.types.joinToString(" · ").ifEmpty { null }
                    }
                    SettingRow(
                        src.name, if (on) "On" else "Off", s, note = note,
                        onClick = {
                            if (on) {
                                enabled[src.id] = false
                                MobileLyricsSources.setSourceEnabled(src.id, false)
                                consentFor = null
                                onChanged()
                            } else if (needsConsent && !asking) {
                                consentFor = src.id
                            } else {
                                if (needsConsent) {
                                    MobileLyricsSources.markDisclosed(src.id)
                                    disclosed[src.id] = true
                                }
                                enabled[src.id] = true
                                MobileLyricsSources.setSourceEnabled(src.id, true)
                                consentFor = null
                                onChanged()
                            }
                        },
                    )
                }
            }

            Header("Screen", s)
            SettingRow("Song change card", if (intro) "On" else "Off", s,
                note = "A big title card when a new song starts.",
                onLeft = { intro = !intro; flip(TvPrefs.INTRO, intro) }, onRight = { intro = !intro; flip(TvPrefs.INTRO, intro) })
            SettingRow("Burn-in protection", if (drift) "On" else "Off", s,
                note = "Slowly drifts the whole picture a few pixels (OLED TVs).",
                onLeft = { drift = !drift; flip(TvPrefs.DRIFT, drift) }, onRight = { drift = !drift; flip(TvPrefs.DRIFT, drift) })
            val minutes = listOf(0, 1, 2, 5, 10, 20)
            fun setAmbient(d: Int) { ambient = step(minutes, ambient, d); prefs.edit().putInt(TvPrefs.AMBIENT_MINUTES, ambient).apply(); onChanged() }
            SettingRow("Clock after pause", if (ambient == 0) "Off" else "$ambient min", s,
                note = "Shows a big clock and the cover when music stays paused.",
                onLeft = { setAmbient(-1) }, onRight = { setAmbient(1) })

            Header("Account and data", s)
            SettingRow(
                if (confirmLogout) "Press OK again to log out of Spotify" else "Log out of Spotify",
                "", s,
                onClick = {
                    if (confirmLogout) onLogout() else confirmLogout = true
                },
            )
            SettingRow(
                "Clear lyrics cache", if (cacheCleared) "Cleared" else "", s,
                onClick = {
                    LyricsRepository.clearAllCache()
                    cacheCleared = true
                },
            )
            SettingRow(
                "Reset lyrics settings", "", s,
                onClick = {
                    prefs.edit()
                        .remove(Constants.PREF_KEY_LYRICS_SIZE_PERCENT)
                        .remove(LyricsFonts.PREF_LYRICS_FONT)
                        .remove(Constants.PREF_KEY_LYRICS_OFFSET_MS)
                        .remove(MobileTranslation.PREF_TRANSLATE_TARGET)
                        .remove(MobileTranslation.PREF_TRANSLATION_MODE)
                        .remove(MobileTranslation.PREF_ROMANIZE)
                        .remove(LyricsFonts.PREF_NOTE_SIZE)
                        .apply()
                    sizePct = 100; fontKey = LyricsFonts.DEFAULT_KEY; offsetMs = 0
                    target = ""; replace = false; romanize = false; notePct = 100
                    onChanged()
                },
            )
            Spacer(Modifier.height((20f * s).dp))
            Text("SpotTV ${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.35f), fontSize = (13f * s).sp)
        }
    }
}

private fun offsetLabel(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> "+$ms ms (earlier)"
    else -> "$ms ms (later)"
}

@Composable
private fun Header(text: String, s: Float) {
    Spacer(Modifier.height((18f * s).dp))
    Text(text.uppercase(), color = Green, fontSize = (14f * s).sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height((4f * s).dp))
}

/**
 * One row. Left / right call [onLeft] / [onRight]; OK calls [onClick], or [onRight] when there is none.
 * Left / right are always swallowed so focus never leaves the panel sideways.
 */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    s: Float,
    note: String? = null,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Column(
        base
            .fillMaxWidth()
            .padding(vertical = (2f * s).dp)
            .clip(RoundedCornerShape((10f * s).dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> { onLeft?.invoke(); true }
                        Key.DirectionRight -> { onRight?.invoke(); true }
                        else -> false
                    }
                }
            }
            .clickable(interactionSource = source, indication = null) {
                (onClick ?: onRight)?.invoke()
            }
            .padding(horizontal = (14f * s).dp, vertical = (9f * s).dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = (20f * s).sp)
            if (value.isNotEmpty()) {
                val arrows = focused && onLeft != null && onRight != null
                Text(
                    if (arrows) "◀  $value  ▶" else value,
                    color = if (focused) Green else Color.White.copy(alpha = 0.7f),
                    fontSize = (20f * s).sp,
                )
            }
        }
        if (note != null) {
            Text(note, color = Color.White.copy(alpha = 0.5f), fontSize = (13f * s).sp)
        }
    }
}
