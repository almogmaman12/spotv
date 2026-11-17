package com.almog.spotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.spotv.TvViewModel
import com.almog.spotv.spotify.Entry
import com.almog.spotv.spotify.Kind
import com.almog.spotv.spotify.Listing
import com.almog.spotv.spotify.SearchResults
import com.almog.spotv.spotify.SpotifyDevice
import kotlinx.coroutines.delay

private val Green = Color(0xFF1DB954)
private val Panel = Color(0xFF121212)

/** Spotify's own DJ playlist. Whether it can be started from here depends on the account / region. */
private enum class Section(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    PLAYLISTS("Playlists"),
    LIKED("Liked songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    QUEUE("Queue"),
    DEVICES("Devices"),
}

/** A page opened from a list: an album, playlist or artist. */
private class Detail(val entry: Entry)

/**
 * The built-in Spotify browser: search, playlists, liked songs, albums, artists and devices.
 * Left/right moves between the menu and the lists; OK plays or opens; holding OK on a song queues it.
 */
@Composable
fun BrowseScreen(tv: TvViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Panel)) {
        val s = maxHeight.value / 540f
        var section by remember { mutableStateOf(Section.HOME) }
        val stack = remember { mutableStateListOf<Detail>() }
        val target by tv.browseTarget.collectAsStateWithLifecycle()
        LaunchedEffect(target) {
            target?.let { stack.clear(); stack.add(Detail(it)) }
        }
        BackHandler { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tv.closeBrowse() }

        Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // The menu on the left.
            Column(
                Modifier
                    .width((220f * s).dp)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .padding(vertical = (28f * s).dp, horizontal = (14f * s).dp),
                verticalArrangement = Arrangement.spacedBy((6f * s).dp),
            ) {
                Text(
                    "Library", color = Color.White.copy(alpha = 0.5f), fontSize = (16f * s).sp,
                    modifier = Modifier.padding(start = (12f * s).dp, bottom = (10f * s).dp),
                )
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try { first.requestFocus() } catch (_: IllegalStateException) {}
                }
                for (sec in Section.values()) {
                    RailItem(
                        label = sec.label, selected = sec == section, s = s,
                        modifier = if (sec == Section.HOME) Modifier.focusRequester(first) else Modifier,
                    ) {
                        if (sec != section) {
                            section = sec
                            stack.clear()
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = (32f * s).dp, vertical = (24f * s).dp)) {
                val top = stack.lastOrNull()
                if (top != null) {
                    DetailPage(tv, top, s) { stack.add(Detail(it)) }
                } else {
                    when (section) {
                        Section.HOME -> HomePage(tv, s) { stack.add(Detail(it)) }
                        Section.SEARCH -> SearchPage(tv, s) { stack.add(Detail(it)) }
                        Section.PLAYLISTS -> GridPage(tv, "Your playlists", s, { tv.catalog.myPlaylists() }) { stack.add(Detail(it)) }
                        Section.ALBUMS -> GridPage(tv, "Saved albums", s, { tv.catalog.savedAlbums() }) { stack.add(Detail(it)) }
                        Section.ARTISTS -> GridPage(tv, "Followed artists", s, { tv.catalog.followedArtists() }) { stack.add(Detail(it)) }
                        Section.LIKED -> LikedPage(tv, s)
                        Section.QUEUE -> QueuePage(tv, s)
                        Section.DEVICES -> DevicesPage(tv, s)
                    }
                }
            }
        }
        MiniPlayer(tv, s)
        }
    }
}

// ---- pages ------------------------------------------------------------------------------------

/** What is playing, along the bottom, so you never lose track of it while browsing. */
@Composable
private fun MiniPlayer(tv: TvViewModel, s: Float) {
    val np by tv.player.state.collectAsStateWithLifecycle()
    val n = np ?: return
    val progress = if (n.durationMs > 0L) {
        (n.estimatedProgressMs().toFloat() / n.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxWidth().background(Color(0xFF181818))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = (22f * s).dp, vertical = (10f * s).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((14f * s).dp),
        ) {
            Box(Modifier.size((48f * s).dp).clip(RoundedCornerShape(5.dp))) {
                Picture(n.coverUrl, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(n.title, color = Color.White, fontSize = (17f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.artist, color = Color.White.copy(alpha = 0.62f), fontSize = (14f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniControl("◀|", s) { tv.player.previous() }
            MiniControl(if (n.isPlaying) "Ⅱ" else "▶", s, primary = true) { tv.player.playPause() }
            MiniControl("|▶", s) { tv.player.next() }
            Column(Modifier.width((150f * s).dp)) {
                Text(
                    (n.deviceName ?: "Spotify") + if (n.isPlaying) " · Playing" else " · Paused",
                    color = Color.White.copy(alpha = 0.58f), fontSize = (12f * s).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("FULL LYRICS  ↗", color = Green, fontSize = (12f * s).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { tv.closeBrowse() }.padding(top = (3f * s).dp))
            }
        }
        Box(Modifier.fillMaxWidth().height((2f * s).dp).background(Color.White.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(progress).height((2f * s).dp).background(Green))
        }
    }
}

@Composable
private fun MiniControl(label: String, s: Float, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size((if (primary) 46f else 38f) * s.dp)
            .clip(CircleShape)
            .background(when {
                focused -> Color.White
                primary -> Green
                else -> Color.White.copy(alpha = 0.08f)
            })
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused || primary) Color.Black else Color.White, fontSize = (if (primary) 18f else 14f) .times(s).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomePage(tv: TvViewModel, s: Float, onOpen: (Entry) -> Unit) {
    val recent by produceState<Listing?>(null) { value = tv.catalog.recentlyPlayed() }
    val playlists by produceState<Listing?>(null) { value = tv.catalog.myPlaylists() }
    val artists by produceState<Listing?>(null) { value = tv.catalog.topArtists() }
    val tracks by produceState<Listing?>(null) { value = tv.catalog.topTracks() }
    fun open(e: Entry, from: List<Entry>) {
        when {
            e.kind == Kind.TRACK -> tv.playFromBrowse(uris = from.dropWhile { it.uri != e.uri }.take(100).map { it.uri }, label = e.title)
            else -> onOpen(e)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Good ${greeting()}", s)
        // A hint if the account hasn't granted the newer permissions yet.
        if (listOf(recent, playlists).any { it != null && (it.code == 401 || it.code == 403) }) NeedsLogin(tv, s)
        LazyColumn(contentPadding = PaddingValues(bottom = (24f * s).dp), verticalArrangement = Arrangement.spacedBy((10f * s).dp)) {
            item { Shelf("Recently played", recent?.items.orEmpty(), s) { open(it, recent?.items.orEmpty()) } }
            item { Shelf("Your playlists", playlists?.items.orEmpty(), s) { open(it, emptyList()) } }
            item { Shelf("Your top artists", artists?.items.orEmpty(), s) { open(it, emptyList()) } }
            item { Shelf("Your top songs", tracks?.items.orEmpty(), s) { open(it, tracks?.items.orEmpty()) } }
        }
    }
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return if (h < 5) "night" else if (h < 12) "morning" else if (h < 18) "afternoon" else "evening"
}

/** One row of cards you scroll sideways. Empty shelves are skipped. */
@Composable
private fun Shelf(title: String, list: List<Entry>, s: Float, onOpen: (Entry) -> Unit) {
    if (list.isEmpty()) return
    Column {
        Text(title, color = Color.White, fontSize = (21f * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = (8f * s).dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(vertical = (6f * s).dp, horizontal = (4f * s).dp),
            horizontalArrangement = Arrangement.spacedBy((16f * s).dp),
        ) {
            items(list, key = { it.uri }) { e -> Box(Modifier.width((150f * s).dp)) { Card(e, s) { onOpen(e) } } }
        }
    }
}

@Composable
private fun GridPage(tv: TvViewModel, title: String, s: Float, load: suspend () -> Listing, onOpen: (Entry) -> Unit) {
    val listing by produceState<Listing?>(null, title) { value = load() }
    Column(Modifier.fillMaxSize()) {
        PageTitle(title, s)
        Loaded(tv, listing, s) { list ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(bottom = (24f * s).dp, top = (8f * s).dp),
                horizontalArrangement = Arrangement.spacedBy((16f * s).dp),
                verticalArrangement = Arrangement.spacedBy((18f * s).dp),
            ) {
                items(list, key = { it.uri }) { e -> Card(e, s) { onOpen(e) } }
            }
        }
    }
}

@Composable
private fun LikedPage(tv: TvViewModel, s: Float) {
    val listing by produceState<Listing?>(null) { value = tv.catalog.savedTracks() }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Liked songs", s)
        Loaded(tv, listing, s) { list ->
            LazyColumn(contentPadding = PaddingValues(bottom = (24f * s).dp), verticalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                items(list.size, key = { list[it].uri + it }) { i ->
                    val e = list[i]
                    EntryRow(e, s, onLong = { tv.addToQueue(e.uri, e.title) }) {
                        // Play from this song onwards (Spotify takes up to 100 songs at once).
                        tv.playFromBrowse(uris = list.drop(i).take(100).map { it.uri }, label = e.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPage(tv: TvViewModel, detail: Detail, s: Float, onOpen: (Entry) -> Unit) {
    val entry = detail.entry
    val listing by produceState<Listing?>(null, entry.uri) {
        value = when (entry.kind) {
            Kind.ALBUM -> tv.catalog.albumTracks(entry)
            Kind.PLAYLIST -> tv.catalog.playlistTracks(entry.id)
            Kind.ARTIST -> tv.catalog.artistAlbums(entry.id)
            Kind.TRACK -> Listing(emptyList(), 200)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = (12f * s).dp)) {
            Thumb(entry, (96f * s).dp)
            Spacer(Modifier.width((20f * s).dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Color.White, fontSize = (30f * s).sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotEmpty()) {
                    Text(entry.subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = (18f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Pill("Play", s, Modifier.focusRequester(focus)) { tv.playFromBrowse(contextUri = entry.uri, label = entry.title) }
            Spacer(Modifier.width((12f * s).dp))
            Pill("Shuffle", s) { tv.shuffleFromBrowse(entry.uri, entry.title) }
        }
        Loaded(tv, listing, s) { list ->
            LazyColumn(contentPadding = PaddingValues(bottom = (24f * s).dp), verticalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                items(list.size, key = { list[it].uri + it }) { i ->
                    val e = list[i]
                    when (e.kind) {
                        Kind.TRACK -> EntryRow(e, s, onLong = { tv.addToQueue(e.uri, e.title) }) {
                            if (entry.kind == Kind.ARTIST) {
                                tv.playFromBrowse(uris = listOf(e.uri), label = e.title)
                            } else {
                                tv.playFromBrowse(contextUri = entry.uri, offsetUri = e.uri, label = e.title)
                            }
                        }
                        else -> EntryRow(e, s) { onOpen(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPage(tv: TvViewModel, s: Float, onOpen: (Entry) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    // Search as you type, a moment after the last key.
    LaunchedEffect(query) {
        delay(650)
        submitted = query.trim()
    }
    val results by produceState<SearchResults?>(null, submitted) {
        value = if (submitted.length < 2) null else tv.catalog.search(submitted)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Search", s)
        var fieldFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = (24f * s).sp),
            cursorBrush = SolidColor(Green),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { fieldFocused = it.isFocused }
                .clip(RoundedCornerShape((12f * s).dp))
                .background(Color.White.copy(alpha = if (fieldFocused) 0.14f else 0.08f))
                .border((2f * s).dp, if (fieldFocused) Green else Color.Transparent, RoundedCornerShape((12f * s).dp))
                .padding(horizontal = (18f * s).dp, vertical = (14f * s).dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Songs, artists, albums, playlists — press OK to type", color = Color.White.copy(alpha = 0.4f), fontSize = (22f * s).sp)
                }
                inner()
            },
        )
        Spacer(Modifier.height((12f * s).dp))
        val r = results
        when {
            submitted.length < 2 -> Hint("Type at least two letters.", s)
            r == null -> Hint("Searching…", s)
            r.code == 401 || r.code == 403 -> NeedsLogin(tv, s)
            r.code != 200 -> Hint("Can't reach Spotify right now.", s)
            r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty() -> Hint("No results for “$submitted”.", s)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = (24f * s).dp), verticalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                if (r.tracks.isNotEmpty()) {
                    item { SectionLabel("Songs", s) }
                    items(r.tracks, key = { "t" + it.uri }) { e ->
                        EntryRow(e, s, onLong = { tv.addToQueue(e.uri, e.title) }) { tv.playFromBrowse(uris = listOf(e.uri), label = e.title) }
                    }
                }
                if (r.artists.isNotEmpty()) {
                    item { SectionLabel("Artists", s) }
                    items(r.artists, key = { "r" + it.uri }) { e -> EntryRow(e, s) { onOpen(e) } }
                }
                if (r.albums.isNotEmpty()) {
                    item { SectionLabel("Albums", s) }
                    items(r.albums, key = { "a" + it.uri }) { e -> EntryRow(e, s) { onOpen(e) } }
                }
                if (r.playlists.isNotEmpty()) {
                    item { SectionLabel("Playlists", s) }
                    items(r.playlists, key = { "p" + it.uri }) { e -> EntryRow(e, s) { onOpen(e) } }
                }
            }
        }
    }
}

@Composable
private fun QueuePage(tv: TvViewModel, s: Float) {
    val q by produceState<com.almog.spotv.spotify.QueueState?>(null) { value = tv.catalog.queueEntries() }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Queue", s)
        val state = q
        when {
            state == null -> Hint("Loading…", s)
            state.code == 401 || state.code == 403 -> NeedsLogin(tv, s)
            state.code != 200 -> Hint("Can't reach Spotify right now.", s)
            state.now == null && state.next.isEmpty() -> Hint("The queue is empty. Hold OK on a song to add it.", s)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = (24f * s).dp), verticalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                state.now?.let { now ->
                    item { SectionLabel("Now playing", s) }
                    item { EntryRow(now, s) { tv.closeBrowse() } }
                }
                if (state.next.isNotEmpty()) {
                    item { SectionLabel("Next up", s) }
                    items(state.next.size, key = { "q" + state.next[it].uri + it }) { i ->
                        val e = state.next[i]
                        // Spotify can't jump inside the queue, so OK starts the queue from this song.
                        EntryRow(e, s) { tv.playFromBrowse(uris = state.next.drop(i).take(100).map { it.uri }, label = e.title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesPage(tv: TvViewModel, s: Float) {
    val devices by produceState<List<SpotifyDevice>?>(null) { value = tv.catalog.devices() }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Play on…", s)
        val list = devices
        when {
            list == null -> Hint("Looking for devices…", s)
            list.isEmpty() -> Hint("No Spotify devices found. Open Spotify on your phone, speaker or this TV, then come back.", s)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy((4f * s).dp)) {
                items(list, key = { it.id }) { d ->
                    val label = when (d.type.lowercase()) {
                        "tv" -> "TV"; "computer" -> "Computer"; "smartphone" -> "Phone"; "speaker" -> "Speaker"
                        else -> d.type
                    }
                    Focusable(s, onClick = { tv.transferTo(d.id, d.name) }) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Color.White, fontSize = (22f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(label + (d.volume?.let { " · volume $it%" } ?: ""), color = Color.White.copy(alpha = 0.55f), fontSize = (16f * s).sp)
                        }
                        if (d.active) Text("Playing here", color = Green, fontSize = (18f * s).sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---- pieces -----------------------------------------------------------------------------------

@Composable
private fun Loaded(tv: TvViewModel, listing: Listing?, s: Float, content: @Composable (List<Entry>) -> Unit) {
    when {
        listing == null -> Hint("Loading…", s)
        listing.code == 401 || listing.code == 403 -> NeedsLogin(tv, s)
        !listing.ok -> Hint("Can't reach Spotify right now.", s)
        listing.items.isEmpty() -> Hint("Nothing here yet.", s)
        else -> content(listing.items)
    }
}

@Composable
private fun NeedsLogin(tv: TvViewModel, s: Float) {
    Column {
        Hint(
            "Spotify needs your permission for this. The app now asks for library access, so log in once more " +
                "(this also happens on first use after an update).", s,
        )
        Spacer(Modifier.height((14f * s).dp))
        Pill("Log in again", s) { tv.logout() }
    }
}

@Composable
private fun PageTitle(text: String, s: Float) {
    Text(text, color = Color.White, fontSize = (30f * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = (12f * s).dp))
}

@Composable
private fun SectionLabel(text: String, s: Float) {
    Text(
        text.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = (14f * s).sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = (14f * s).dp, bottom = (4f * s).dp),
    )
}

@Composable
private fun Hint(text: String, s: Float) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = (20f * s).sp, modifier = Modifier.padding(top = (8f * s).dp))
}

@Composable
private fun RailItem(label: String, selected: Boolean, s: Float, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((10f * s).dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect() // moving the cursor over the menu already opens the section
            }
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.18f)
                    selected -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .combinedClickableFocus(source, onSelect)
            .padding(horizontal = (14f * s).dp, vertical = (12f * s).dp),
    ) {
        Text(
            label, color = if (selected || focused) Color.White else Color.White.copy(alpha = 0.65f),
            fontSize = (20f * s).sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableFocus(source: MutableInteractionSource, onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier =
    this.combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLong)

/** A focusable row with the common highlight; the content goes inside a Row. */
@Composable
private fun Focusable(
    s: Float,
    modifier: Modifier = Modifier,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape((12f * s).dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.035f))
            .border((2f * s).dp, if (focused) Green else Color.Transparent, RoundedCornerShape((12f * s).dp))
            .combinedClickableFocus(source, onClick, onLong)
            .padding(horizontal = (14f * s).dp, vertical = (10f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((16f * s).dp),
        content = content,
    )
}

/** A list row: picture, title, subtitle (and the length for songs). */
@Composable
private fun EntryRow(e: Entry, s: Float, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(s, onLong = onLong, onClick = onClick) {
        Thumb(e, (54f * s).dp)
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = (21f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.subtitle.isNotEmpty()) {
                Text(e.subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = (16f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (e.kind == Kind.TRACK && e.durationMs > 0) {
            val sec = e.durationMs / 1000
            Text("%d:%02d".format(sec / 60, sec % 60), color = Color.White.copy(alpha = 0.45f), fontSize = (16f * s).sp)
        }
    }
}

/** A big square card for the grids. */
@Composable
private fun Card(e: Entry, s: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val k = if (focused) 1.06f else 1f
                scaleX = k; scaleY = k
            }
            .combinedClickableFocus(source, onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(if (e.kind == Kind.ARTIST) CircleShape else RoundedCornerShape((10f * s).dp))
                .border((3f * s).dp, if (focused) Green else Color.Transparent, if (e.kind == Kind.ARTIST) CircleShape else RoundedCornerShape((10f * s).dp)),
        ) { Picture(e.imageUrl, Modifier.fillMaxSize()) }
        Text(e.title, color = Color.White, fontSize = (17f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = (6f * s).dp))
        if (e.subtitle.isNotEmpty()) {
            Text(e.subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = (14f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thumb(e: Entry, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(if (e.kind == Kind.ARTIST) CircleShape else RoundedCornerShape(6.dp)),
    ) { Picture(e.imageUrl, Modifier.fillMaxSize()) }
}

@Composable
private fun Picture(url: String?, modifier: Modifier) {
    val img = rememberImage(url)
    if (img != null) {
        Image(img, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF2A2A30), Color(0xFF1A1A1E)))))
    }
}

@Composable
private fun Pill(text: String, s: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val k = if (focused) 1.08f else 1f
                scaleX = k; scaleY = k
            }
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Green)
            .combinedClickableFocus(source, onClick)
            .padding(horizontal = (28f * s).dp, vertical = (12f * s).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color.Black else Color.Black, fontSize = (20f * s).sp, fontWeight = FontWeight.Bold)
    }
}
