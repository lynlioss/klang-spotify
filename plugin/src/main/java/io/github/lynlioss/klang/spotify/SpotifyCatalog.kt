package io.github.lynlioss.klang.spotify

import android.content.Context
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.spotify.*
import moe.rukamori.archivetune.spotify.api.SpotifyAlbumApi
import moe.rukamori.archivetune.spotify.api.SpotifyLibraryQueryApi
import moe.rukamori.archivetune.spotify.models.*
import org.json.JSONArray
import org.json.JSONObject

/** Spotify web catalog plus playlist mutations; the host deals only in generic catalog entities. */
internal class SpotifyCatalog(private val context: Context) {
    private val vault get() = AccountVault.get(context)
    private val userId get() = vault.get("spotify_user_id")
    private fun id(value: String, kind: String): String = value.removePrefix("spotify:$kind:").also {
        require(it.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid $kind ID" }
    }
    suspend fun call(operation: String, args: JSONObject): JSONObject {
        if (operation == "accounts") return JSONObject().put("spotify", vault.get("sp_dc") != null).put("connected", vault.get("sp_dc") != null)
            .put("qobuz", vault.qobuzConnected()).put("profile", vault.get("spotify_profile"))
            .put("revision", vault.revision("spotify")).put("playbackRevision", buildString {
                val prefs = context.getSharedPreferences("source_preferences", Context.MODE_PRIVATE)
                append(vault.revision("qobuz")); append(':'); append(playbackPreference(prefs.getString("source", null), vault.qobuzConnected()))
                append(':'); append(prefs.getInt("qobuzFormat", 27))
            })
        if (operation == "account.logout") { vault.spotifyLogout(); return JSONObject().put("ok", true) }
        // Song properties ask for the cached link state only; never touch the network or require a session.
        if (operation == "lookup" && args.optBoolean("statusOnly")) {
            val item = args.optJSONObject("item") ?: error("Missing song details")
            val account = vault.revision("spotify")
            return JSONObject().put("sourceStatus", RecordingDatabase.get(context).status(CatalogCodec.importSong(item), account).toJson(item))
                .put("accountRevision", account)
        }
        return SpotifySession.use(context, mutation = operation.startsWith("playlist.") || operation.startsWith("library.")) {
            if (args.has("accountRevision")) require(args.getString("accountRevision") == vault.revision("spotify")) {
                "The Spotify account changed. Refresh before editing this library."
            }
            if (userId == null) {
                val me = Spotify.me().getOrThrow()
                vault.putAll(mapOf("spotify_user_id" to me.id, "spotify_profile" to (me.displayName ?: me.id)))
            }
            val offset = args.optInt("offset").coerceAtLeast(0)
            val limit = args.optInt("limit", 50).coerceIn(1, 100)
            when (operation) {
                "home" -> {
                    val home = Spotify.home().getOrThrow()
                    val items = home.sections.flatMap { section -> section.items.map { item ->
                        val entity = when (item) {
                            is SpotifyHomeFeedItem.Playlist -> CatalogCodec.playlist(SpotifyPlaylist(item.id, item.name,
                                item.description, item.images.ifEmpty { item.imageUrl?.let { listOf(SpotifyImage(it)) }.orEmpty() }), userId)
                            is SpotifyHomeFeedItem.Album -> CatalogCodec.album(SpotifyAlbum(id = item.id, name = item.name,
                                artists = item.artists, images = item.images.ifEmpty { item.imageUrl?.let { listOf(SpotifyImage(it)) }.orEmpty() }))
                            is SpotifyHomeFeedItem.Artist -> CatalogCodec.artist(SpotifyArtist(item.id, item.name,
                                item.images.ifEmpty { item.imageUrl?.let { listOf(SpotifyImage(it)) }.orEmpty() }))
                        }
                        entity.put("section", section.title.orEmpty())
                    } }
                    CatalogCodec.page(home.greeting ?: "For you", items.drop(offset).take(limit), offset, items.size, limit)
                }
                "search" -> {
                    val search = Spotify.search(args.getString("query").take(1000), limit = limit, offset = offset).getOrThrow()
                    val kind = args.optString("kind", "all")
                    val tracks = search.tracks
                    val albums = search.albums; val artists = search.artists; val playlists = search.playlists
                    val items = mutableListOf<JSONObject>()
                    if (kind in setOf("all", "track")) items += tracks?.items.orEmpty().map { CatalogCodec.track(it) }
                    if (kind in setOf("all", "album")) items += albums?.items.orEmpty().map(CatalogCodec::album)
                    if (kind in setOf("all", "artist")) items += artists?.items.orEmpty().map(CatalogCodec::artist)
                    if (kind in setOf("all", "playlist")) items += playlists?.items.orEmpty().map { CatalogCodec.playlist(it, userId) }
                    val total = when (kind) { "track" -> tracks?.total; "album" -> albums?.total; "artist" -> artists?.total;
                        "playlist" -> playlists?.total; else -> listOfNotNull(tracks?.total, albums?.total, artists?.total, playlists?.total).maxOrNull() } ?: 0
                    CatalogCodec.page("Search results", items, offset, total, limit)
                }
                "library" -> {
                    when (args.optString("kind", "playlist")) {
                        "track" -> {
                            val page = Spotify.likedSongs(limit, offset).getOrThrow()
                            CatalogCodec.page("Liked songs", page.items.map { CatalogCodec.track(it.track).put("saved", true)
                                .put("added", runCatching { java.time.Instant.parse(it.addedAt).epochSecond }.getOrDefault(0L)) }, offset, page.total, limit)
                        }
                        "artist" -> {
                            val page = Spotify.myArtists(limit, offset).getOrThrow()
                            CatalogCodec.page("Artists", page.items.map(CatalogCodec::artist), offset, page.total, limit)
                        }
                        "album" -> {
                            val page = SpotifyLibraryQueryApi.myAlbums(limit, offset).getOrThrow()
                            CatalogCodec.page("Saved albums", page.items.map { CatalogCodec.album(it).put("saved", true) }, offset, page.total, limit)
                        }
                        "folder" -> {
                            val page = Spotify.myLibraryNode(args.optString("id").takeIf { it.isNotBlank() }, limit, offset).getOrThrow()
                            CatalogCodec.page("Your library", page.items.map { item -> when (item) {
                                is SpotifyLibraryItem.Playlist -> CatalogCodec.playlist(item.playlist, userId)
                                is SpotifyLibraryItem.Folder -> JSONObject().put("id", item.uri).put("kind", "folder")
                                    .put("title", item.folder.name).put("total", item.folder.totalChildren)
                            } }, offset, page.total, limit)
                        }
                        else -> {
                            val page = Spotify.myPlaylists(limit, offset).getOrThrow()
                            CatalogCodec.page("Your playlists", page.items.map { CatalogCodec.playlist(it, userId) }, offset, page.total, limit)
                        }
                    }
                }
                "lookup" -> lookup(args.getString("id"), offset, limit)
                "library.save", "library.remove" -> {
                    val uris = args.getJSONArray("ids").strings()
                    require(uris.size in 1..100 && uris.all { it.matches(Regex("spotify:(track|album|artist|playlist):[A-Za-z0-9]{22}")) })
                    if (operation.endsWith("save")) Spotify.addToLibrary(uris).getOrThrow() else Spotify.removeFromLibrary(uris).getOrThrow()
                    JSONObject().put("ok", true)
                }
                "playlist.create" -> {
                    val playlist = SpotifyPlaylistApi.createPlaylist(args.getString("name").also { require(it.isNotBlank() && it.length <= 100) },
                        args.optString("description").take(300), args.optBoolean("public")).getOrThrow()
                    JSONObject().put("entity", CatalogCodec.playlist(playlist, userId))
                }
                "playlist.add" -> {
                    editable(args.getString("id"))
                    val uris = args.getJSONArray("ids").strings()
                    require(uris.size in 1..100 && uris.all { it.matches(Regex("spotify:track:[A-Za-z0-9]{22}")) })
                    Spotify.addTracksToPlaylist(id(args.getString("id"), "playlist"), uris).getOrThrow()
                    JSONObject().put("ok", true)
                }
                "playlist.remove" -> {
                    editable(args.getString("id"))
                    val items = args.getJSONArray("items").objects().map { Spotify.PlaylistItemRef(it.getString("id"), it.getString("uid")) }
                    require(items.size in 1..100 && items.all { it.uid.isNotBlank() })
                    Spotify.removeTracksFromPlaylist(id(args.getString("id"), "playlist"), items).getOrThrow(); JSONObject().put("ok", true)
                }
                "playlist.move" -> {
                    editable(args.getString("id"))
                    val uids = args.getJSONArray("uids").strings(); require(uids.size in 1..100 && uids.all { it.isNotBlank() })
                    Spotify.moveItemsInPlaylist(id(args.getString("id"), "playlist"), uids, args.optString("beforeUid").takeIf { it.isNotBlank() && it != "null" }).getOrThrow()
                    JSONObject().put("ok", true)
                }
                "playlist.edit" -> {
                    val permissions = editable(args.getString("id"))
                    if (args.has("public") || args.has("collaborative")) require(permissions.optBoolean("owned")) { "Only the owner can change playlist visibility." }
                    require(!(args.optBoolean("public") && args.optBoolean("collaborative"))) { "A collaborative playlist must be private." }
                    val pid = id(args.getString("id"), "playlist")
                    val name = args.optString("name").takeIf { args.has("name") }?.also { require(it.isNotBlank() && it.length <= 100) }
                    Spotify.editPlaylistAttributes(pid, name, args.optString("description").takeIf { args.has("description") }?.take(300)).getOrThrow()
                    if (args.has("public") || args.has("collaborative")) {
                        val body = JSONObject()
                        listOf("public", "collaborative").filter(args::has).forEach { body.put(it, args.getBoolean(it)) }
                        restWrite("PUT", "playlists/$pid", body)
                    }
                    JSONObject().put("ok", true)
                }
                "playlist.follow", "playlist.unfollow" -> {
                    restWrite(if (operation.endsWith("unfollow")) "DELETE" else "PUT", "playlists/${id(args.getString("id"), "playlist")}/followers",
                        if (operation.endsWith("unfollow")) null else JSONObject().put("public", args.optBoolean("public")))
                    JSONObject().put("ok", true)
                }
                "playlist.cover" -> {
                    require(editable(args.getString("id")).optBoolean("owned")) { "Only the owner can change this playlist's cover." }
                    val base64 = args.getString("imageBase64").also { require(it.length in 1..340_000) }
                    val response = SpotifyGraphqlClient.restClient.put("playlists/${id(args.getString("id"), "playlist")}/images") {
                        header("Authorization", "Bearer ${Spotify.accessToken}"); contentType(ContentType.Image.JPEG); setBody(base64)
                    }
                    require(response.status.value in 200..299) { "Spotify couldn't update the cover (${response.status.value})" }
                    JSONObject().put("ok", true)
                }
                else -> error("Unsupported catalog operation")
            }.put("accountRevision", vault.revision("spotify")).also { result ->
                if (operation in setOf("library", "search", "lookup")) runCatching {
                    BackgroundMatches.observe(context, result, if (operation == "search") 1 else 0)
                }
                if (operation == "library" || operation.startsWith("library.") || operation.startsWith("playlist."))
                    BackgroundLibrary.schedule(context, force = operation != "library")
            }
        }
    }
    /** Library discovery uses one request per page, including followed playlists inside folders. */
    suspend fun libraryPage(task: LibraryTask): JSONObject = SpotifySession.use(context) {
        require(vault.revision("spotify") == task.account) { "The Spotify account changed." }
        val limit = 100
        when (task.kind) {
            "liked" -> Spotify.likedSongs(limit, task.offset).getOrThrow().let { page ->
                CatalogCodec.page("Liked songs", page.items.map { CatalogCodec.track(it.track) }, task.offset, page.total, page.limit)
            }
            "playlists" -> Spotify.myPlaylists(limit, task.offset).getOrThrow().let { page ->
                CatalogCodec.page("Playlists", page.items.map { CatalogCodec.playlist(it, userId) }, task.offset, page.total, page.limit)
            }
            "albums" -> SpotifyLibraryQueryApi.myAlbums(limit, task.offset).getOrThrow().let { page ->
                CatalogCodec.page("Albums", page.items.map(CatalogCodec::album), task.offset, page.total, page.limit)
            }
            "playlist" -> Spotify.playlistTracks(id(task.id, "playlist"), limit, task.offset).getOrThrow().let { page ->
                CatalogCodec.page("Playlist", page.items.mapNotNull { it.track?.let { track -> CatalogCodec.track(track) } }, task.offset, page.total, page.limit)
            }
            "album" -> SpotifyAlbumApi.album(id(task.id, "album"), task.offset, limit).getOrThrow().let { album ->
                CatalogCodec.page(album.name, album.tracks?.items.orEmpty().map { CatalogCodec.track(it) }, task.offset, album.totalTracks, album.tracks?.limit ?: limit)
            }
            else -> error("Unsupported library collection")
        }
    }
    private suspend fun lookup(uri: String, offset: Int, limit: Int): JSONObject = when {
        uri.startsWith("spotify:playlist:") -> {
            val pid = id(uri, "playlist")
            val playlist = Spotify.playlist(pid).getOrThrow()
            val page = Spotify.playlistTracks(pid, limit, offset).getOrThrow()
            // Preserve each playlist UID, including repeated songs, and report unavailable entries.
            val items = page.items.mapNotNull { it.track?.let { track -> CatalogCodec.track(track, it.uid) } }
            CatalogCodec.page(playlist.name, items, offset, page.total, limit, CatalogCodec.playlist(playlist, userId))
                .put("unavailableCount", page.items.size - items.size)
        }
        uri.startsWith("spotify:album:") -> {
            val album = SpotifyAlbumApi.album(id(uri, "album"), offset, limit).getOrThrow()
            CatalogCodec.page(album.name, album.tracks?.items.orEmpty().map { CatalogCodec.track(it) }, offset, album.totalTracks, limit, CatalogCodec.album(album))
        }
        uri.startsWith("spotify:artist:") -> {
            val aid = id(uri, "artist")
            val artist = Spotify.artist(aid).getOrThrow()
            val top = Spotify.artistTopTracks(aid).getOrThrow().tracks.map { CatalogCodec.track(it) }
            val releases = JSONObject().put("id", "spotify:artist-albums:$aid").put("kind", "folder")
                .put("title", "Albums and singles").put("subtitle", artist.name).put("artwork", CatalogCodec.artist(artist).opt("artwork"))
            CatalogCodec.page(artist.name, top + releases, limit = top.size + 1, entity = CatalogCodec.artist(artist))
        }
        uri.startsWith("spotify:artist-albums:") -> {
            val aid = id(uri, "artist-albums")
            val page = SpotifyGraphqlClient.authenticatedGet<SpotifyPaging<SpotifyAlbum>>("artists/$aid/albums") {
                parameter("include_groups", "album,single,compilation,appears_on"); parameter("limit", limit.coerceAtMost(50)); parameter("offset", offset)
            }
            CatalogCodec.page("Albums and singles", page.items.map(CatalogCodec::album), page.offset, page.total, page.limit)
        }
        uri.startsWith("spotify:track:") -> {
            val track = SpotifyGraphqlClient.authenticatedGet<SpotifyTrack>("tracks/${id(uri, "track")}")
            CatalogCodec.page(track.name, listOf(CatalogCodec.track(track)), entity = CatalogCodec.track(track))
        }
        uri.startsWith("spotify:folder:") -> {
            val page = Spotify.myLibraryNode(uri, limit, offset).getOrThrow()
            CatalogCodec.page("Your library", page.items.map { item -> when (item) {
                is SpotifyLibraryItem.Playlist -> CatalogCodec.playlist(item.playlist, userId)
                is SpotifyLibraryItem.Folder -> JSONObject().put("id", item.uri).put("kind", "folder").put("title", item.folder.name)
            } }, offset, page.total, limit)
        }
        else -> error("Unsupported Spotify link")
    }
    private suspend fun editable(uri: String): JSONObject = CatalogCodec.playlist(Spotify.playlist(id(uri, "playlist")).getOrThrow(), userId)
        .also { require(it.optBoolean("editable")) { "You don't have permission to edit this playlist." } }
    private suspend fun restWrite(method: String, path: String, body: JSONObject?) {
        val response = SpotifyGraphqlClient.restClient.request(path) {
            this.method = io.ktor.http.HttpMethod.parse(method)
            header("Authorization", "Bearer ${Spotify.accessToken}")
            if (body != null) { contentType(ContentType.Application.Json); setBody(Json.parseToJsonElement(body.toString())) }
        }
        require(response.status.value in 200..299) { "Spotify couldn't save the change (${response.status.value})" }
    }
    suspend fun preview(link: String): JSONObject {
        val parsed = app.lynli.klang.importing.ImportLink.parse(link)
        val uri = "spotify:${parsed.kind}:${parsed.id}"
        val all = mutableListOf<JSONObject>()
        var offset = 0
        var first: JSONObject? = null
        do {
            val page = call("lookup", JSONObject().put("id", uri).put("offset", offset).put("limit", 100))
            if (first == null) first = page
            val items = page.getJSONArray("items").objects()
            all += items.filter { it.optString("kind") == "track" && it.optBoolean("available", true) }
            require(all.size <= 10_000) { "Download collections of up to 10,000 songs." }
            if (page.isNull("nextOffset")) break
            val next = page.getInt("nextOffset")
            require(next > offset && next < 10_000) { "Download collections of up to 10,000 songs." }
            offset = next
        } while (true)
        require(all.isNotEmpty()) { "This collection has no accessible songs." }
        return JSONObject().put("title", first?.optString("title")).put("collection", parsed.playlist)
            .put("artwork", first?.optJSONObject("entity")?.opt("artwork")).put("songs", JSONArray(all.map(CatalogCodec::importJson)))
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
