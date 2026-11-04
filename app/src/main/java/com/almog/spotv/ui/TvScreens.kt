package com.almog.spotv.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotv.AppStatus
import com.almog.spotv.Constants
import com.almog.spotv.TvViewModel
import com.almog.spotv.lyrics.ui.LyricsView
import com.almog.spotv.TvPrefs
import com.almog.spotv.lyrics.kinetic.Beats
import com.almog.spotv.lyrics.kinetic.FlatBeatClock
import com.almog.spotv.lyrics.kinetic.KineticLyrics
import com.almog.spotv.lyrics.kinetic.rememberLyricsClock
import com.almog.spotv.lyrics.viewmodel.LyricsViewModel
import com.almog.spotv.spotify.NowPlaying
import com.almog.spotv.spotify.PlayerRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

private val SpotifyGreen = Color(0xFF1DB954)

@Composable
fun TvRoot(tv: TvViewModel, lyricsVm: LyricsViewModel) {
    // The TV layout never mirrors, whatever the device language (lyrics lines handle their own direction).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        TvRootContent(tv, lyricsVm)
    }
}

@Composable
private fun TvRootContent(tv: TvViewModel, lyricsVm: LyricsViewModel) {
    val loggedIn by tv.loggedIn.collectAsStateWithLifecycle()
    val playing by tv.player.state.collectAsStateWithLifecycle()
    val queue by tv.player.queue.collectAsStateWithLifecycle()
    val connected by tv.player.connected.collectAsStateWithLifecycle()
    val status by AppStatus.status.collectAsStateWithLifecycle()
    val settingsOpen by tv.settingsOpen.collectAsStateWithLifecycle()
    val settingsVersion by tv.settingsVersion.collectAsStateWithLifecycle()

    // Keep the last cover until the next one has loaded, so the backdrop never flashes black.
    var cover by remember { mutableStateOf<Cover?>(null) }
    val coverUrl = playing?.coverUrl
    LaunchedEffect(coverUrl) {
        if (coverUrl != null) CoverLoader.load(coverUrl)?.let { cover = it }
    }

    // Back closes the settings first.
    BackHandler(enabled = settingsOpen && loggedIn) { tv.closeSettings() }

    val current = playing
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(settingsVersion) { TvPrefs.prefs(ctx) }
    val driftOn = remember(settingsVersion) { prefs.getBoolean(TvPrefs.DRIFT, true) }
    val ambientMinutes = remember(settingsVersion) { prefs.getInt(TvPrefs.AMBIENT_MINUTES, 5) }

    // Ambient mode: music paused for a while -> a big clock and the cover, until any button is pressed.
    var ambient by remember { mutableStateOf(false) }
    val isPlaying = current?.isPlaying == true
    LaunchedEffect(isPlaying, current != null, ambientMinutes, settingsOpen) {
        ambient = false
        if (current != null && !isPlaying && ambientMinutes > 0 && !settingsOpen) {
            delay(ambientMinutes * 60_000L)
            ambient = true
        }
    }
    LaunchedEffect(isPlaying) { if (isPlaying) ambient = false }

    // Burn-in protection: the whole picture wanders a few pixels, very slowly.
    val drift = androidx.compose.animation.core.rememberInfiniteTransition(label = "drift")
    val driftPhase by drift.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(
            tween(240_000, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "driftPhase",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (ambient && e.type == KeyEventType.KeyDown) {
                    ambient = false
                    true // the first press only wakes the screen
                } else {
                    false
                }
            }
            .graphicsLayer {
                if (driftOn) {
                    scaleX = 1.02f; scaleY = 1.02f
                    translationX = kotlin.math.sin(driftPhase) * 14.dp.toPx()
                    translationY = kotlin.math.cos(driftPhase * 0.8f) * 10.dp.toPx()
                }
            }
    ) {
        when {
            !loggedIn -> LoginScreen(tv)
            current == null -> IdleScreen(connected, onOpenSettings = { tv.openSettings() })
            else -> NowPlayingScreen(
                np = current,
                upNext = queue.firstOrNull()?.let { if (it.artist.isBlank()) it.title else "${it.title} — ${it.artist}" },
                cover = cover,
                lyricsVm = lyricsVm,
                player = tv.player,
                settingsOpen = settingsOpen,
                settingsVersion = settingsVersion,
                onOpenSettings = { tv.openSettings() },
            )
        }
        if (settingsOpen && loggedIn) {
            // A panel over the right-hand side, so lyric changes show live beside it.
            SettingsPanel(
                screenHeightDp = maxHeight.value,
                onChanged = { tv.settingsChanged() },
                onLogout = { tv.logout() },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.55f),
            )
        }
        if (ambient && current != null) AmbientScreen(current, cover, maxHeight.value)
        status?.let { StatusBanner(it, Modifier.align(Alignment.TopCenter)) }
    }
}

/** A big clock beside the cover on black, drifting slowly so no pixel stays lit. */
@Composable
private fun AmbientScreen(np: NowPlaying, cover: Cover?, heightDp: Float) {
    val s = heightDp / 540f
    var time by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = java.time.LocalTime.now()
            delay(10_000)
        }
    }
    val phase by androidx.compose.animation.core.rememberInfiniteTransition(label = "ambient").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(tween(120_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ambientPhase",
    )
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Row(
            Modifier.graphicsLayer {
                translationX = kotlin.math.sin(phase) * 60.dp.toPx()
                translationY = kotlin.math.cos(phase * 0.7f) * 40.dp.toPx()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(cover, (200f * s).dp)
            Spacer(Modifier.width((40f * s).dp))
            Column {
                Text(
                    "%02d:%02d".format(time.hour, time.minute),
                    color = Color.White.copy(alpha = 0.85f), fontSize = (110f * s).sp, fontWeight = FontWeight.Light,
                )
                Text(np.title, color = Color.White.copy(alpha = 0.45f), fontSize = (22f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = (520f * s).dp))
                Text(np.artist, color = Color.White.copy(alpha = 0.3f), fontSize = (16f * s).sp, maxLines = 1)
            }
        }
    }
}

// ---- now playing ------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingScreen(
    np: NowPlaying,
    upNext: String?,
    cover: Cover?,
    lyricsVm: LyricsViewModel,
    player: PlayerRepository,
    settingsOpen: Boolean,
    settingsVersion: Int,
    onOpenSettings: () -> Unit,
) {
    val accent by androidx.compose.animation.animateColorAsState(
        cover?.accent ?: SpotifyGreen, animationSpec = tween(1500), label = "accent",
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val tvPrefs = remember(settingsVersion) { TvPrefs.prefs(context) }
    val kinetic = remember(settingsVersion) { tvPrefs.getString(TvPrefs.LYRICS_STYLE, "classic") == "kinetic" }
    val livingBg = remember(settingsVersion) { tvPrefs.getBoolean(TvPrefs.LIVING_BG, true) }
    val introOn = remember(settingsVersion) { tvPrefs.getBoolean(TvPrefs.INTRO, true) }

    // The controls stay hidden until a remote button is pressed, then slide up and hide again.
    var lastInput by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var controlsShown by remember { mutableStateOf(false) }
    LaunchedEffect(lastInput, controlsShown) {
        if (controlsShown) {
            delay(Constants.CONTROLS_HIDE_MS)
            controlsShown = false
        }
    }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) controlsShown = false
    }
    val playFocus = remember { FocusRequester() }
    // Whenever the controls come up (or go away), focus is back on play / pause.
    LaunchedEffect(controlsShown, settingsOpen) {
        if (settingsOpen) return@LaunchedEffect
        try {
            playFocus.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
    val reveal by androidx.compose.animation.core.animateFloatAsState(
        if (controlsShown) 1f else 0f, animationSpec = tween(350), label = "controlsReveal",
    )

    // Position, redrawn a few times a second.
    var positionMs by remember { mutableLongStateOf(np.estimatedProgressMs()) }
    LaunchedEffect(np) {
        while (true) {
            positionMs = np.estimatedProgressMs()
            delay(250)
        }
    }

    // The play button holds focus even while hidden, so the remote always reaches the controls.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) return@LaunchedEffect
        try {
            playFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // Not attached yet; the first D-pad press will focus something.
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || settingsOpen) return@onPreviewKeyEvent false
                val wasHidden = !controlsShown
                lastInput = SystemClock.elapsedRealtime()
                controlsShown = true
                // The first press on hidden controls only brings them up, so nothing happens unseen.
                wasHidden && (event.key == Key.DirectionCenter || event.key == Key.Enter ||
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                    event.key == Key.DirectionUp || event.key == Key.DirectionDown)
            }
    ) {
        // Everything is sized from the screen height (1.0 = a 540dp-high screen), so it looks the
        // same on a 1080p and a 4K TV whatever density the box reports.
        val s = maxHeight.value / 540f
        fun d(v: Float): Dp = (v * s).dp
        fun t(v: Float): TextUnit = (v * s).sp

        Backdrop(cover)

        // Lyrics fill the whole screen; the title bar and controls sit on top of them.
        Box(Modifier.fillMaxSize()) {
            // Re-created when a setting changes, so size / font / translation apply at once.
            key(settingsVersion) {
                if (kinetic) {
                    val lyricState by lyricsVm.uiState.collectAsStateWithLifecycle()
                    val clock = rememberLyricsClock(lyricsVm)
                    val beat = remember(lyricState.track) {
                        lyricState.track?.let { Beats.fromLyrics(it) } ?: FlatBeatClock()
                    }
                    val timeline = remember(lyricState.track) {
                        lyricState.track?.let { com.almog.spotv.lyrics.kinetic.KineticTimelineBuilder.build(it) }
                    }
                    val section by remember(timeline) { derivedStateOf { timeline?.stanzaAt(clock()) ?: 0 } }
                    if (livingBg && cover != null) {
                        LivingBackground(
                            section = section,
                            palette = cover.palette, beat = beat, positionMs = clock,
                            pulseEnabled = tvPrefs.getBoolean(TvPrefs.BEAT_PULSE, false),
                        )
                    }
                    KineticLyrics(
                        viewModel = lyricsVm, beat = beat, positionMs = clock, accent = accent,
                        baseSp = d(54f).value * tvPrefs.getInt(com.almog.spotv.Constants.PREF_KEY_LYRICS_SIZE_PERCENT, 100) / 100f,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LyricsView(viewModel = lyricsVm, modifier = Modifier.fillMaxSize())
                }
            }
            NoLyricsHint(lyricsVm, np.isPodcast, t(26f))
        }

        // Title card: a small see-through dark box, so the lyrics behind it stay calm.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = d(48f), top = d(26f))
                .widthIn(max = maxWidth * 0.5f)
                .clip(RoundedCornerShape(d(16f)))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(d(10f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(cover, d(64f))
            Spacer(Modifier.width(d(16f)))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    np.title,
                    color = Color.White,
                    fontSize = t(24f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee(initialDelayMillis = 2000, velocity = 40.dp),
                )
                Text(
                    np.artist,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = t(16f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!np.isPlaying) {
                // Shown even when the controls are hidden, so a paused song is obvious.
                Spacer(Modifier.width(d(16f)))
                Canvas(Modifier.size(d(26f))) { drawPauseIcon(Color.White.copy(alpha = 0.85f)) }
            }
        }

        // Song change: a big title card that shrinks into the corner card.
        if (introOn) {
            val p = remember(np.trackId) { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(np.trackId) {
                p.snapTo(0f)
                delay(2200)
                p.animateTo(1f, tween(900))
            }
            if (p.value < 1f) {
                val big = d(220f)
                val mw = maxWidth
                val mh = maxHeight
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            val k = p.value
                            val e = k * k * (3f - 2f * k)
                            alpha = 1f - e
                            scaleX = 1f - 0.7f * e
                            scaleY = 1f - 0.7f * e
                            translationX = -(mw.toPx() / 2f - 120.dp.toPx() * s) * e
                            translationY = -(mh.toPx() / 2f - 70.dp.toPx() * s) * e
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverArt(cover, big)
                    Spacer(Modifier.height(d(16f)))
                    Text(np.title, color = Color.White, fontSize = t(34f), fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = mw * 0.6f))
                    Text(np.artist, color = Color.White.copy(alpha = 0.75f), fontSize = t(20f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // A thin progress line stays at the bottom while the controls are hidden.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (np.durationMs > 0) (positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f)
                .height(d(3f))
                .alpha(1f - reveal)
                .background(accent.copy(alpha = 0.9f))
        )

        // Controls: slide up from the bottom over everything, on a see-through dark band.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - reveal) * size.height * 0.5f
                    alpha = reveal
                }
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.62f))
                    )
                )
                .padding(start = d(48f), end = d(48f), top = d(48f), bottom = d(30f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (upNext != null) {
                Text(
                    "Up next: $upNext",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = t(15f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d(8f)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(positionMs), color = Color.White.copy(alpha = 0.8f), fontSize = t(15f))
                Spacer(Modifier.width(d(14f)))
                SeekBar(
                    fraction = if (np.durationMs > 0) positionMs.toFloat() / np.durationMs else 0f,
                    accent = accent,
                    onSeekBy = { player.seekBy(it) },
                    onActivate = { player.playPause() },
                    modifier = Modifier.weight(1f),
                    thin = d(5f),
                    thick = d(10f),
                )
                Spacer(Modifier.width(d(14f)))
                Text(formatTime(np.durationMs), color = Color.White.copy(alpha = 0.8f), fontSize = t(15f))
            }
            Spacer(Modifier.height(d(10f)))
            Row(
                horizontalArrangement = Arrangement.spacedBy(d(26f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvButton(d(50f), onClick = { player.toggleShuffle() }) { fg ->
                    drawShuffleIcon(if (np.shuffle) accentOn(fg) else fg)
                }
                TvButton(d(58f), onClick = { player.previous() }) { fg -> drawPreviousIcon(fg) }
                TvButton(d(76f), onClick = { player.playPause() }, focusRequester = playFocus, primary = true, ringColor = accent) { fg ->
                    if (np.isPlaying) drawPauseIcon(fg) else drawPlayIcon(fg)
                }
                TvButton(d(58f), onClick = { player.next() }) { fg -> drawNextIcon(fg) }
                TvButton(d(50f), onClick = { player.cycleRepeat() }) { fg ->
                    drawRepeatIcon(if (np.repeat != "off") accentOn(fg) else fg, one = np.repeat == "track")
                }
                TvButton(d(44f), onClick = onOpenSettings) { fg -> drawGearIcon(fg) }
            }
        }
    }
}

/** Shuffle / repeat when on: the cover's colour would vanish on the white focus ring, so use green. */
private fun accentOn(@Suppress("UNUSED_PARAMETER") fg: Color): Color = SpotifyGreen

@Composable
private fun NoLyricsHint(lyricsVm: LyricsViewModel, podcast: Boolean, size: TextUnit) {
    val ui by lyricsVm.uiState.collectAsStateWithLifecycle()
    val empty = ui.track == null || ui.track?.lines.isNullOrEmpty()
    val message = when {
        podcast -> "Podcast — no lyrics"
        ui.isLoading -> "Finding lyrics…"
        empty -> "No lyrics for this song"
        else -> null
    }
    if (message != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, color = Color.White.copy(alpha = 0.45f), fontSize = size, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Backdrop(cover: Cover?) {
    // Slightly more saturated than the cover, so the backdrop has colour instead of grey mush.
    val vivid = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) }) }
    Crossfade(targetState = cover, animationSpec = tween(1000), label = "backdrop") { c ->
        Box(Modifier.fillMaxSize()) {
            if (c != null) {
                Image(
                    bitmap = c.blurred,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    colorFilter = vivid,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Darken for legibility: from the top (tinted by the cover) to the bottom, and behind the text on the left.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            (c?.accent ?: Color(0xFF222222)).copy(alpha = 0.18f),
                            Color.Black.copy(alpha = 0.70f),
                        )
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent),
                    )
                )
            )
        }
    }
}

@Composable
private fun CoverArt(cover: Cover?, size: Dp) {
    Crossfade(targetState = cover, animationSpec = tween(500), label = "cover") { c ->
        Box(
            Modifier
                .size(size)
                .shadow(size * 0.12f, RoundedCornerShape(size * 0.04f), clip = false)
                .clip(RoundedCornerShape(size * 0.04f))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            if (c != null) {
                Image(
                    bitmap = c.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ---- login and idle ----------------------------------------------------------------------------

@Composable
private fun LoginScreen(tv: TvViewModel) {
    val url by tv.pairingUrl.collectAsStateWithLifecycle()
    val error by tv.pairingError.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        val s = maxHeight.value / 540f
        Row(
            Modifier.fillMaxSize().padding(horizontal = (80f * s).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((64f * s).dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Connect Spotify", color = SpotifyGreen, fontSize = (40f * s).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height((20f * s).dp))
                if (!tv.auth.isConfigured) {
                    Text(
                        "This build has no Spotify client ID. Add SPOTIFY_CLIENT_ID to local.properties and rebuild.",
                        color = Color.White, fontSize = (24f * s).sp,
                    )
                } else {
                    Step("1", "Connect your phone to the same Wi‑Fi as this TV", s)
                    Step("2", "Scan the code with your phone camera", s)
                    Step("3", "Log in to Spotify and tap Agree", s)
                    Spacer(Modifier.height((16f * s).dp))
                    Text(
                        text = error ?: url?.let { "or open $it" } ?: "Waiting for the network…",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (18f * s).sp,
                    )
                }
            }
            val link = url
            if (link != null && tv.auth.isConfigured) {
                Box(
                    Modifier.size((300f * s).dp).clip(RoundedCornerShape((16f * s).dp)).background(Color.White).padding((14f * s).dp),
                ) {
                    val qr = remember(link) { qrBitmap(link, 512) }
                    Image(
                        bitmap = qr,
                        contentDescription = "Login QR code",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String, s: Float) {
    Row(Modifier.padding(vertical = (6f * s).dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size((40f * s).dp).clip(RoundedCornerShape(50)).background(SpotifyGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Color.Black, fontSize = (22f * s).sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width((16f * s).dp))
        Text(text, color = Color.White, fontSize = (24f * s).sp)
    }
}

@Composable
private fun IdleScreen(connected: Boolean, onOpenSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
        val s = maxHeight.value / 540f
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = (80f * s).dp)) {
            Text(
                if (connected) "Nothing is playing" else "Connecting to Spotify…",
                color = Color.White, fontSize = (40f * s).sp, fontWeight = FontWeight.Bold,
            )
            if (connected) {
                Spacer(Modifier.height((12f * s).dp))
                Text(
                    "Start a song in Spotify on your phone or this TV and it will show up here.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = (22f * s).sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height((28f * s).dp))
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try {
                        focus.requestFocus()
                    } catch (_: IllegalStateException) {
                    }
                }
                TvButton((56f * s).dp, onClick = onOpenSettings, focusRequester = focus) { fg -> drawGearIcon(fg) }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: AppStatus.Status, modifier: Modifier) {
    val color = when (status.severity) {
        AppStatus.ERROR -> Color(0xFFB3261E)
        AppStatus.WARN -> Color(0xFF8A6D00)
        else -> Color(0xFF333333)
    }
    Box(
        modifier
            .padding(top = 20.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Text(status.message, color = Color.White, fontSize = 18.sp)
    }
}

// ---- helpers -----------------------------------------------------------------------------------

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun qrBitmap(text: String, px: Int): ImageBitmap {
    val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(px * px)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until px) for (x in 0 until px) pixels[y * px + x] = if (matrix.get(x, y)) black else white
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
}
