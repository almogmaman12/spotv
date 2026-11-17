package com.almog.spotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotv.AppStatus
import com.almog.spotv.TvPrefs
import com.almog.spotv.TvViewModel
import com.almog.spotv.lyrics.viewmodel.LyricsViewModel
import com.almog.spotv.spotify.NowPlaying
import com.almog.spotv.spotify.PlayerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PlayerGreen = Color(0xFF1DB954)
private val PlayerSurface = Color(0xFF181818)
private val PlayerMuted = Color(0xFFB3B3B3)

/**
 * A player-first TV layout: artwork and track identity stay prominent, while the
 * transport, queue, lyrics, radio and DJ actions are reachable with a remote.
 */
@Composable
internal fun SpotifyStylePlayerScreen(
    np: NowPlaying,
    cover: ImageBitmap?,
    blurredCover: ImageBitmap?,
    accent: Color,
    player: PlayerRepository,
    lyricsVm: LyricsViewModel,
    tv: TvViewModel,
    upNext: String?,
    onOpenBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    onReturnToLyrics: () -> Unit,
) {
    val queue by player.queue.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var djEnabled by remember { mutableStateOf(TvPrefs.prefs(context).getBoolean(TvPrefs.DJ, false)) }
    var autoplayEnabled by remember { mutableStateOf(TvPrefs.prefs(context).getBoolean(TvPrefs.AUTOPLAY, true)) }
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var liked by remember(np.trackId) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    var position by remember(np.trackId) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var positionMs by remember(np.trackId) { mutableStateOf(np.estimatedProgressMs()) }

    LaunchedEffect(np.trackId) {
        liked = if (np.isPodcast || np.trackId.isBlank()) null else tv.catalog.isSaved("spotify:track:${np.trackId}")
    }

    BackHandler {
        when {
            showQueue -> showQueue = false
            showLyrics -> showLyrics = false
            else -> onReturnToLyrics()
        }
    }

    LaunchedEffect(np.trackId, np.progressMs, np.takenAt, np.isPlaying) {
        while (true) {
            positionMs = np.estimatedProgressMs()
            if (!dragging && np.durationMs > 0) position = (positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        if (blurredCover != null) {
            Image(
                bitmap = blurredCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.34f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.28f), Color(0xFF101010).copy(alpha = 0.92f), Color(0xFF090909))
                )
            )
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 38.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♫", color = PlayerGreen, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("SPOTV", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    np.deviceName?.let { "Listening on $it" } ?: "Spotify Connect",
                    color = PlayerMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(22.dp))
                PlayerAction("Full lyrics", onReturnToLyrics)
                PlayerAction("Browse", onOpenBrowse)
                PlayerAction("Settings", onOpenSettings)
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                Column(
                    Modifier.weight(0.92f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cover != null) {
                            Image(
                                bitmap = cover,
                                contentDescription = "Album artwork for ${np.title}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("♫", color = Color.White.copy(alpha = 0.5f), fontSize = 100.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        np.title,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        np.artist,
                        color = PlayerMuted,
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    )
                    Text(
                        np.album,
                        color = PlayerMuted.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    )
                }

                Column(
                    Modifier.weight(1.08f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showLyrics) "LYRICS" else "NOW PLAYING",
                            color = PlayerGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        PlayerAction(if (showLyrics) "Album view" else "Lyrics", { showLyrics = !showLyrics })
                        PlayerAction(if (showQueue) "Hide queue" else "Queue", { showQueue = !showQueue })
                    }
                    Spacer(Modifier.height(12.dp))
                    if (showLyrics) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.28f)).padding(14.dp),
                        ) {
                            com.almog.spotv.lyrics.ui.LyricsView(
                                viewModel = lyricsVm,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Text(
                            "Made for the moment.",
                            color = Color.White,
                            fontSize = 31.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            upNext?.let { "UP NEXT  •  $it" } ?: "Your music, uninterrupted",
                            color = PlayerMuted,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(22.dp))
                        if (showQueue) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)).padding(14.dp),
                            ) {
                                Text("Next in queue", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (queue.isEmpty()) {
                                    Text("The Spotify queue is empty", color = PlayerMuted, fontSize = 15.sp)
                                } else {
                                    queue.take(5).forEachIndexed { index, item ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("${index + 1}", color = PlayerGreen, fontSize = 14.sp)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(item.artist, color = PlayerMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(playerTime(positionMs), color = PlayerMuted, fontSize = 13.sp)
                        Slider(
                            value = position,
                            onValueChange = { position = it; dragging = true },
                            onValueChangeFinished = {
                                player.seekTo((position * np.durationMs).toLong())
                                dragging = false
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(playerTime(np.durationMs), color = PlayerMuted, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerAction(if (np.shuffle) "🔀 On" else "Shuffle", { player.toggleShuffle() }, active = np.shuffle)
                        PlayerAction("|◀", { player.previous() })
                        Button(
                            onClick = { player.playPause() },
                            modifier = Modifier.padding(horizontal = 10.dp).size(width = 92.dp, height = 58.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PlayerGreen, contentColor = Color.Black),
                        ) {
                            Text(if (np.isPlaying) "Ⅱ" else "▶", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        }
                        PlayerAction("▶|", { player.next() })
                        PlayerAction(
                            when (np.repeat) { "track" -> "↻¹"; "context" -> "↻ On"; else -> "Repeat" },
                            { player.cycleRepeat() },
                            active = np.repeat != "off",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (liked != null) PlayerAction(if (liked == true) "♥ Liked" else "♡ Like", {
                            val want = liked != true
                            liked = want
                            scope.launch {
                                val code = tv.catalog.setSaved("spotify:track:${np.trackId}", want)
                                if (code !in 200..299) {
                                    liked = !want
                                    AppStatus.report("like", "Couldn't update Liked Songs (Spotify said $code)", AppStatus.WARN, 4000)
                                }
                            }
                        }, active = liked == true)
                        PlayerAction(if (djEnabled) "DJ On" else "DJ Off", {
                            djEnabled = !djEnabled
                            TvPrefs.prefs(context).edit().putBoolean(TvPrefs.DJ, djEnabled).apply()
                            tv.settingsChanged()
                            AppStatus.report("dj", if (djEnabled) "SPOTV DJ enabled" else "SPOTV DJ disabled", AppStatus.INFO, 2500)
                        }, active = djEnabled)
                        PlayerAction("Start radio", {
                            tv.autoQueue.startRadio(np)
                            AppStatus.report("radio", "Adding similar songs to your queue", AppStatus.INFO, 3000)
                        })
                        PlayerAction(if (autoplayEnabled) "Autoplay On" else "Autoplay Off", {
                            autoplayEnabled = !autoplayEnabled
                            TvPrefs.prefs(context).edit().putBoolean(TvPrefs.AUTOPLAY, autoplayEnabled).apply()
                            tv.settingsChanged()
                        }, active = autoplayEnabled)
                        PlayerAction("Volume −", { player.volumeBy(-5) })
                        Text("${np.volumePercent ?: "?"}%", color = PlayerMuted, fontSize = 13.sp)
                        PlayerAction("Volume +", { player.volumeBy(5) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Spotify playback controls  •  Remote: arrows to navigate, OK to select, Back to close panels",
                color = PlayerMuted.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun PlayerAction(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                when {
                    focused -> Color.White
                    active -> PlayerGreen.copy(alpha = 0.18f)
                    else -> PlayerSurface.copy(alpha = 0.92f)
                }
            )
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color.Black else if (active) PlayerGreen else Color.White,
            fontSize = 13.sp,
            fontWeight = if (focused || active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun playerTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
