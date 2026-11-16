package io.github.lynlioss.klang.spotify

import app.lynli.klang.importing.ImportSong
import moe.rukamori.archivetune.spotify.models.*
import org.json.JSONArray
import org.json.JSONObject

internal object CatalogCodec {
    private fun image(images: List<SpotifyImage>) = images.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }?.url
    private fun base(id: String, kind: String, title: String, subtitle: String = "", art: String? = null) =
        JSONObject().put("id", "spotify:$kind:$id").put("kind", kind).put("title", title).put("subtitle", subtitle)
            .put("artwork", art).put("url", "https://open.spotify.com/$kind/$id")
    fun track(track: SpotifyTrack, uid: String? = null): JSONObject = base(track.id, "track", track.name,
        track.artists.joinToString(", ") { it.name }, track.album?.images?.let(::image))
        .put("artist", track.artists.joinToString(", ") { it.name }).put("album", track.album?.name.orEmpty())
        .put("albumArtist", track.album?.artists?.joinToString(", ") { it.name }.orEmpty())
        .put("durationMs", track.durationMs).put("explicit", track.explicit).put("local", track.isLocal)
        .put("available", !track.isLocal && track.id.isNotBlank() && track.isPlayable != false).put("isrc", track.externalIds?.isrc)
        .put("trackNumber", track.trackNumber).put("discNumber", track.discNumber).put("releaseDate", track.album?.releaseDate).put("uid", uid)
        .put("albumId", track.album?.id?.takeIf { it.isNotBlank() }?.let { "spotify:album:$it" })
        .put("artists", JSONArray(track.artists.map { JSONObject().put("id", it.id?.let { id -> "spotify:artist:$id" }).put("name", it.name) }))
    fun album(album: SpotifyAlbum) = base(album.id, "album", album.name, album.artists.joinToString(", ") { it.name }, image(album.images))
        .put("total", album.totalTracks).put("releaseDate", album.releaseDate).put("albumType", album.albumType)
    fun artist(artist: SpotifyArtist) = base(artist.id, "artist", artist.name, "Artist", image(artist.images))
        .put("genres", JSONArray(artist.genres))
    fun playlist(playlist: SpotifyPlaylist, userId: String?) = base(playlist.id, "playlist", playlist.name,
        playlist.owner?.displayName.orEmpty(), image(playlist.images)).put("description", playlist.description)
        .put("total", playlist.tracks?.total).put("editable", (!userId.isNullOrBlank() && playlist.owner?.id == userId) || playlist.canEdit == true)
        .put("owned", !userId.isNullOrBlank() && playlist.owner?.id == userId).put("public", playlist.public).put("collaborative", playlist.collaborative)
        .put("snapshot", playlist.snapshotId).put("ownerId", playlist.owner?.id)
    fun page(title: String, items: List<JSONObject>, offset: Int = 0, total: Int = items.size, limit: Int = 50,
        entity: JSONObject? = null) = JSONObject().put("title", title).put("items", JSONArray(items))
        .put("total", total).put("offset", offset).put("nextOffset", if (offset + limit < total) offset + limit else JSONObject.NULL)
        .put("entity", entity)
    fun importSong(item: JSONObject) = ImportSong(item.getString("id"), item.getString("url"), item.getString("title"),
        item.optString("artist"), item.optString("album"), item.optLong("durationMs"),
        item.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
        item.optString("releaseDate").takeIf { it.isNotBlank() && it != "null" }, item.optInt("trackNumber"), item.optInt("discNumber"),
        spotify = true, explicit = if (item.has("explicit")) item.optBoolean("explicit") else null, albumArtist = item.optString("albumArtist"))
    fun importJson(item: JSONObject) = JSONObject().put("id", item.getString("id")).put("url", item.getString("url"))
        .put("title", item.getString("title")).put("artist", item.optString("artist")).put("album", item.optString("album"))
        .put("duration", item.optLong("durationMs")).put("artwork", item.opt("artwork")).put("payload", item.toString())
}
