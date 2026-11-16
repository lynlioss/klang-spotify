package io.github.lynlioss.klang.spotify

import moe.rukamori.archivetune.spotify.models.*
import org.junit.Assert.*
import org.junit.Test

class CatalogCodecTest {
    @Test fun downloadsRetainAlbumCreditsAndPositionAndPlaylistDuplicates() {
        val song = SpotifyTrack(id = "1".repeat(22), name = "Song", artists = listOf(SpotifySimpleArtist(name = "Artist")),
            album = SpotifySimpleAlbum(id = "2".repeat(22), name = "Album", releaseDate = "2026-01-15", artists = listOf(SpotifySimpleArtist(name = "Album artist"))),
            durationMs = 200000, trackNumber = 4, discNumber = 2, externalIds = SpotifyExternalIds("SYNTHETIC001"))
        val first = CatalogCodec.track(song, "first")
        val second = CatalogCodec.track(song, "second")
        assertEquals(first.getString("id"), second.getString("id"))
        assertNotEquals(first.getString("uid"), second.getString("uid"))
        val imported = CatalogCodec.importSong(first)
        assertEquals("Album artist", imported.albumArtist)
        assertEquals(4, imported.trackNumber)
        assertEquals(2, imported.discNumber)
        assertEquals("2026-01-15", imported.date)
        assertEquals(first.toString(), CatalogCodec.importJson(first).getString("payload"))
    }
    @Test fun collaborationWithoutCurrentUserPermissionIsNotEditable() {
        val playlist = SpotifyPlaylist(id = "1".repeat(22), name = "List", collaborative = true, owner = SpotifyPlaylistOwner("owner"))
        assertFalse(CatalogCodec.playlist(playlist, "another-user").getBoolean("editable"))
        assertTrue(CatalogCodec.playlist(playlist, "owner").getBoolean("editable"))
        assertTrue(CatalogCodec.playlist(playlist.copy(canEdit = true), "another-user").getBoolean("editable"))
        assertFalse(CatalogCodec.playlist(SpotifyPlaylist(), null).getBoolean("owned"))
    }
    @Test fun collectionPaginationContinuesPastUnavailableEntries() {
        assertEquals(100, CatalogCodec.page("List", emptyList(), 0, 125, 100).getInt("nextOffset"))
        assertTrue(CatalogCodec.page("List", emptyList(), 100, 125, 100).isNull("nextOffset"))
    }
}
