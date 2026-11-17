package app.lynli.klang.importing

import org.junit.Assert.*
import org.junit.Test

class YouTubeCatalogAlbumTest {
    private fun song(album: String, artist: String, albumArtist: String = "") = ImportSong("spotify:track:example",
        "https://open.spotify.com/track/example", "Song", artist, album, spotify = true, albumArtist = albumArtist)

    @Test fun tracksFromTheSameAlbumShareOneCacheKeyRegardlessOfArtistOrder() {
        val key = YouTubeCatalog.albumKey(song("Album", "First, Second", "First"))
        assertEquals("first|album", key)
        assertEquals(key, YouTubeCatalog.albumKey(song(" album ", "First")))
        assertNotEquals(key, YouTubeCatalog.albumKey(song("Another Album", "First")))
    }

    @Test fun songsWithoutUsableAlbumMetadataNeverShareASearch() {
        assertNull(YouTubeCatalog.albumKey(song("", "Artist")))
        assertNull(YouTubeCatalog.albumKey(song("Album", "")))
        assertNull(YouTubeCatalog.albumKey(song("null", "Artist")))
        assertTrue(YouTubeCatalog.cachedAlbum(song("", "Artist")).isEmpty())
    }
}
