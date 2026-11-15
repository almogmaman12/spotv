package com.almog.spotv.spotify

import com.almog.spotv.DebugLog
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { TRACK, ALBUM, ARTIST, PLAYLIST }

/** One thing you can browse or play: a song, album, artist or playlist. */
data class Entry(
    val kind: Kind,
    val id: String,
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val durationMs: Long = 0L,
)

/** [code] is the HTTP code: 200 fine, 401/403 login lacks permission (log in again), -1 no network. */
class Listing(val items: List<Entry>, val code: Int) {
    val ok: Boolean get() = code == 200
}

class SearchResults(
    val tracks: List<Entry>,
    val albums: List<Entry>,
    val artists: List<Entry>,
    val playlists: List<Entry>,
    val code: Int,
)

data class SpotifyDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int?)

/** Browsing, search, library and device calls (the parts of the Web API a development-mode app still has). */
class Catalog(private val api: SpotifyApi) {

    // ---- reading ---------------------------------------------------------------------------

    suspend fun search(query: String): SearchResults {
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = api.call("GET", "/search", "q=$q&type=track,album,artist,playlist&limit=10")
        if (raw == null || raw.code != 200) return SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), raw?.code ?: -1)
        return try {
            val j = JSONObject(raw.body)
            SearchResults(
                tracks = entries(j.optJSONObject("tracks")?.optJSONArray("items"), Kind.TRACK),
                albums = entries(j.optJSONObject("albums")?.optJSONArray("items"), Kind.ALBUM),
                artists = entries(j.optJSONObject("artists")?.optJSONArray("items"), Kind.ARTIST),
                playlists = entries(j.optJSONObject("playlists")?.optJSONArray("items"), Kind.PLAYLIST),
                code = 200,
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "search parse: ${e.message}")
            SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), -1)
        }
    }

    suspend fun myPlaylists(): Listing = pagedList("/me/playlists", "limit=50", { it.optJSONArray("items") }, Kind.PLAYLIST, pages = 4)

    suspend fun savedTracks(): Listing = pagedList(
        "/me/tracks", "limit=50",
        { it.optJSONArray("items")?.let { arr -> unwrap(arr, "track") } }, Kind.TRACK, pages = 4,
    )

    suspend fun savedAlbums(): Listing = pagedList(
        "/me/albums", "limit=50",
        { it.optJSONArray("items")?.let { arr -> unwrap(arr, "album") } }, Kind.ALBUM, pages = 4,
    )

    suspend fun followedArtists(): Listing {
        val raw = api.call("GET", "/me/following", "type=artist&limit=50") ?: return Listing(emptyList(), -1)
        if (raw.code != 200) return Listing(emptyList(), raw.code)
        return try {
            Listing(entries(JSONObject(raw.body).optJSONObject("artists")?.optJSONArray("items"), Kind.ARTIST), 200)
        } catch (e: Exception) {
            Listing(emptyList(), -1)
        }
    }

    /** Playlist songs. Spotify only returns them for playlists you own or collaborate on. */
    suspend fun playlistTracks(id: String): Listing {
        val raw = api.call("GET", "/playlists/$id/items", "limit=100&additional_types=track")
            ?: return Listing(emptyList(), -1)
        if (raw.code != 200) return Listing(emptyList(), raw.code)
        return try {
            val arr = JSONObject(raw.body).optJSONArray("items") ?: JSONArray()
            val out = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                // The field was renamed from "track" to "item".
                val t = it.optJSONObject("item") ?: it.optJSONObject("track") ?: continue
                out.add(t)
            }
            Listing(entries(JSONArray(out), Kind.TRACK), 200)
        } catch (e: Exception) {
            Listing(emptyList(), -1)
        }
    }

    suspend fun albumTracks(album: Entry): Listing {
        val raw = api.call("GET", "/albums/${album.id}/tracks", "limit=50") ?: return Listing(emptyList(), -1)
        if (raw.code != 200) return Listing(emptyList(), raw.code)
        return try {
            val items = entries(JSONObject(raw.body).optJSONArray("items"), Kind.TRACK)
            // Album track objects carry no cover of their own: use the album's.
            Listing(items.map { it.copy(imageUrl = album.imageUrl) }, 200)
        } catch (e: Exception) {
            Listing(emptyList(), -1)
        }
    }

    suspend fun artistAlbums(id: String): Listing {
        val raw = api.call("GET", "/artists/$id/albums", "include_groups=album,single&limit=50") ?: return Listing(emptyList(), -1)
        if (raw.code != 200) return Listing(emptyList(), raw.code)
        return try {
            Listing(entries(JSONObject(raw.body).optJSONArray("items"), Kind.ALBUM), 200)
        } catch (e: Exception) {
            Listing(emptyList(), -1)
        }
    }

    suspend fun devices(): List<SpotifyDevice> {
        val raw = api.call("GET", "/me/player/devices") ?: return emptyList()
        if (raw.code != 200) return emptyList()
        return try {
            val arr = JSONObject(raw.body).optJSONArray("devices") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = d.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SpotifyDevice(
                    id, d.optString("name"), d.optString("type"), d.optBoolean("is_active"),
                    if (d.isNull("volume_percent")) null else d.optInt("volume_percent"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- doing -----------------------------------------------------------------------------

    /** Play a whole album/playlist/artist ([contextUri]), or specific songs ([uris]). Returns the HTTP code. */
    suspend fun play(contextUri: String? = null, uris: List<String>? = null, offsetUri: String? = null, deviceId: String? = null): Int {
        val body = JSONObject()
        if (contextUri != null) body.put("context_uri", contextUri)
        if (uris != null) body.put("uris", JSONArray(uris))
        if (offsetUri != null && contextUri != null) body.put("offset", JSONObject().put("uri", offsetUri))
        val raw = api.call("PUT", "/me/player/play", deviceId?.let { "device_id=${URLEncoder.encode(it, "UTF-8")}" }, body.toString())
        return raw?.code ?: -1
    }

    suspend fun transfer(deviceId: String, play: Boolean = true): Int {
        val body = JSONObject().put("device_ids", JSONArray(listOf(deviceId))).put("play", play)
        return api.call("PUT", "/me/player", null, body.toString())?.code ?: -1
    }

    suspend fun addToQueue(uri: String): Int =
        api.call("POST", "/me/player/queue", "uri=${URLEncoder.encode(uri, "UTF-8")}")?.code ?: -1

    suspend fun isSaved(uri: String): Boolean? {
        val raw = api.call("GET", "/me/library/contains", "uris=${URLEncoder.encode(uri, "UTF-8")}") ?: return null
        if (raw.code != 200) return null
        return try {
            JSONArray(raw.body).optBoolean(0)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setSaved(uri: String, saved: Boolean): Int =
        api.call(if (saved) "PUT" else "DELETE", "/me/library", "uris=${URLEncoder.encode(uri, "UTF-8")}")?.code ?: -1

    // ---- parsing ---------------------------------------------------------------------------

    private suspend fun pagedList(
        path: String,
        query: String,
        pick: (JSONObject) -> JSONArray?,
        kind: Kind,
        pages: Int,
    ): Listing {
        val out = ArrayList<Entry>()
        for (page in 0 until pages) {
            val raw = api.call("GET", path, "$query&offset=${page * 50}") ?: return Listing(out, if (out.isEmpty()) -1 else 200)
            if (raw.code != 200) return Listing(out, if (out.isEmpty()) raw.code else 200)
            val arr = try { pick(JSONObject(raw.body)) } catch (e: Exception) { null } ?: break
            val chunk = entries(arr, kind)
            out.addAll(chunk)
            if (arr.length() < 50) break
        }
        return Listing(out, 200)
    }

    private fun unwrap(arr: JSONArray, key: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject(key)?.let { out.put(it) }
        return out
    }

    private fun entries(arr: JSONArray?, kind: Kind): List<Entry> {
        if (arr == null) return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: continue
            if (kind == Kind.TRACK && o.optString("type", "track") != "track") continue
            val image = firstImage(o.optJSONArray("images") ?: o.optJSONObject("album")?.optJSONArray("images"))
            val (title, subtitle) = when (kind) {
                Kind.TRACK -> o.optString("name") to artistNames(o.optJSONArray("artists"))
                Kind.ALBUM -> o.optString("name") to listOf(artistNames(o.optJSONArray("artists")), o.optString("release_date").take(4))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                Kind.ARTIST -> o.optString("name") to "Artist"
                Kind.PLAYLIST -> o.optString("name") to (o.optJSONObject("owner")?.optString("display_name").orEmpty())
            }
            out.add(Entry(kind, id, uri, title, subtitle, image, o.optLong("duration_ms", 0L)))
        }
        return out
    }

    /** The smallest picture that is still at least ~200px, to keep lists light. */
    private fun firstImage(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        var best: String? = null
        var bestW = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val im = arr.optJSONObject(i) ?: continue
            val w = im.optInt("width", 640)
            if (w >= 200 && w < bestW) {
                bestW = w
                best = im.optString("url")
            }
        }
        return best ?: arr.optJSONObject(0)?.optString("url")
    }

    private fun artistNames(arr: JSONArray?): String {
        if (arr == null) return ""
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ")
    }

    companion object {
        private const val TAG = "Catalog"
    }
}
