package com.almog.spotv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.almog.spotv.lyrics.viewmodel.LyricsViewModel
import com.almog.spotv.spotify.NowPlaying
import com.almog.spotv.spotify.QueueItem
import com.almog.spotv.ui.TvRoot
import com.almog.spotv.recognition.AudioCaptureService
import com.almog.spotv.recognition.RecognizedTrackSource
import com.almog.spotv.recognition.RecognizedTrack
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val tv: TvViewModel by viewModels()
    private val lyricsVm: LyricsViewModel by viewModels()

    private var lastTrackId: String? = null
    private val prefetched = LinkedHashSet<String>()

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestPlaybackCapture()
            else Toast.makeText(this, "Microphone permission is required for fallback recognition.", Toast.LENGTH_LONG).show()
        }

    private val projectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            startAudioCaptureService(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        seedLyricsFontSize()

        setContent { TvRoot(tv, lyricsVm, onStartAudioRecognition = { startAudioRecognition() }) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Logged in: follow the player. Logged out: serve the phone-login page.
                launch {
                    tv.loggedIn.collectLatest { loggedIn ->
                        if (loggedIn) tv.player.runPolling() else pairUntilLoggedIn()
                    }
                }
                launch { tv.player.state.collect { syncLyrics(it) } }
                launch { tv.player.queue.collect { prefetchLyrics(it) } }
                launch { RecognizedTrackSource.track.collectLatest { syncLyrics(tv.player.state.value) } }
                // Local Android media-session metadata takes priority when another TV app is playing.
                launch {
                    MediaSessionSongSource.track.collectLatest {
                        syncLyrics(tv.player.state.value)
                    }
                }
                // A setting changed: re-read the size and timing and re-sync the lyrics.
                launch {
                    tv.settingsVersion.collect {
                        seedLyricsFontSize()
                        syncLyrics(tv.player.state.value)
                    }
                }
            }
        }
    }

    private suspend fun pairUntilLoggedIn() {
        while (!tv.loggedIn.value) {
            tv.runPairing()
            if (!tv.loggedIn.value) delay(5_000L)
        }
    }

    /** Prefer metadata from an actively playing TV app, then fall back to the Spotify Web API. */
    private fun syncLyrics(np: NowPlaying?) {
        val externalTrack = MediaSessionSongSource.track.value
        if (externalTrack != null) {
            syncExternalLyrics(externalTrack)
            return
        }
        val recognizedTrack = RecognizedTrackSource.track.value
        if (recognizedTrack != null) {
            syncRecognizedLyrics(recognizedTrack)
            return
        }

        if (np == null) {
            if (lastTrackId != null) {
                lastTrackId = null
                lyricsVm.clearLyrics()
            }
            return
        }
        if (np.trackId != lastTrackId) {
            lastTrackId = np.trackId
            lyricsVm.clearLyrics()
            if (!np.isPodcast) {
                lyricsVm.loadLyrics(
                    artist = np.primaryArtist,
                    title = np.title,
                    album = np.album,
                    durationMs = np.durationMs.toInt(),
                    mediaSource = "spotify",
                    trackId = np.trackId,
                    isrc = np.isrc,
                )
            }
        }
        val offset = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getInt(Constants.PREF_KEY_LYRICS_OFFSET_MS, 0)
        // The engine glides between anchors on its own and ignores near-identical ones.
        lyricsVm.setPlaybackAnchor(np.estimatedProgressMs() + offset, np.isPlaying, 1f)
    }

    /** Load lyrics for a song published by another Android TV app's media session. */
    private fun syncExternalLyrics(track: ExternalPlayingTrack) {
        if (track.key != lastTrackId) {
            lastTrackId = track.key
            lyricsVm.clearLyrics()
            lyricsVm.loadLyrics(
                artist = track.artist,
                title = track.title,
                album = track.album,
                durationMs = track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                mediaSource = "android-media-session",
                trackId = track.key,
                isrc = "",
            )
        }
        // This first stage only reads metadata. Accurate external-app playback position is not exposed
        // consistently by every TV launcher/player, so leave the lyrics untimed rather than inventing
        // a playback position.
    }


    private fun syncRecognizedLyrics(track: RecognizedTrack) {
        val key = "recognized:${track.artist}:${track.title}"
        if (key != lastTrackId) {
            lastTrackId = key
            lyricsVm.clearLyrics()
            lyricsVm.loadLyrics(
                artist = track.artist,
                title = track.title,
                album = track.album,
                durationMs = track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                mediaSource = "audio-recognition",
                trackId = key,
                isrc = "",
            )
        }
    }

    private fun startAudioRecognition() {
        if (BuildConfig.MUSIC_RECOGNITION_URL.isBlank()) {
            Toast.makeText(this, "Set MUSIC_RECOGNITION_URL in local.properties or GitHub Actions secrets first.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestPlaybackCapture()
        }
    }

    private fun requestPlaybackCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermissionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startAudioCaptureService(resultCode: Int, data: Intent?) {
        val service = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            ContextCompat.startForegroundService(this, service)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start audio recognition on this TV.", Toast.LENGTH_LONG).show()
        }
    }

    /** Fetch the next few songs' lyrics early so skipping starts with lyrics ready. */
    private fun prefetchLyrics(items: List<QueueItem>) {
        for (item in items.take(3)) {
            if (item.id.isEmpty() || item.title.isEmpty() || item.artist.isEmpty()) continue
            if (!prefetched.add(item.id)) continue
            lyricsVm.prefetchQueueTrack(item.artist, item.title, item.album, item.durationMs, item.isrc, item.id)
        }
        while (prefetched.size > 30) prefetched.remove(prefetched.first())
    }

    /**
     * Lyrics size setting for the engine, which already sizes its text from the view width (up to 56sp)
     * and multiplies by this value / 32. 27 at a 540dp-high screen gives ~48sp lines, about 1/11 of the
     * screen height; it follows the screen height, times the size setting.
     */
    private fun seedLyricsFontSize() {
        val dm = resources.displayMetrics
        val heightDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        val percent = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
            .getInt(Constants.PREF_KEY_LYRICS_SIZE_PERCENT, 100).coerceIn(50, 200)
        val sp = (27f * heightDp / 540f * percent / 100f).roundToInt().coerceIn(8, 120)
        getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).edit()
            .putInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, sp)
            .apply()
    }

    /** The remote's own media keys work from anywhere in the app. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val player = tv.player
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> tv.toggleSettings()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> player.playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> player.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.previous()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(Constants.SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-Constants.SEEK_STEP_MS)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
