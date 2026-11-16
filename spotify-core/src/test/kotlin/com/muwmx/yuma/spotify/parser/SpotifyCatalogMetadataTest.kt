package moe.rukamori.archivetune.spotify.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyCatalogMetadataTest {
    @Test fun trackCarriesRecordingAndAlbumIdentity() {
        val track = SpotifyTrackParser.parseGqlTrack(Json.parseToJsonElement("""{
          "uri":"spotify:track:1111111111111111111111", "name":"Song", "trackNumber":4, "discNumber":2,
          "playability":{"playable":false}, "externalIds":{"isrc":"SYNTHETIC001"},
          "trackDuration":{"totalMilliseconds":210000}, "contentRating":{"label":"EXPLICIT"},
          "artists":{"items":[{"uri":"spotify:artist:2222222222222222222222","profile":{"name":"Artist"}}]},
          "albumOfTrack":{"uri":"spotify:album:3333333333333333333333","name":"Album",
            "date":{"isoString":"2026-01-15"},"artists":{"items":[{"uri":"spotify:artist:2222222222222222222222","profile":{"name":"Artist"}}]}}
        }""").jsonObject)
        assertEquals(4, track.trackNumber)
        assertEquals(2, track.discNumber)
        assertEquals(false, track.isPlayable)
        assertEquals("SYNTHETIC001", track.externalIds?.isrc)
        assertEquals("2026-01-15", track.album?.releaseDate)
        assertEquals("Artist", track.album?.artists?.single()?.name)
        assertEquals(210000, track.durationMs)
        assertTrue(track.explicit)
    }

    @Test fun memberCountDoesNotGrantEditingOrCollaboration() {
        val data = Json.parseToJsonElement("""{"members":{"items":[{},{}]},"permissions":{"canEdit":false},"privacy":"PUBLIC"}""").jsonObject
        assertFalse(SpotifyPlaylistParser.collaborative(data))
        assertEquals(false, SpotifyPlaylistParser.canEdit(data))
        assertEquals(true, SpotifyPlaylistParser.visibility(data))
        assertNull(SpotifyPlaylistParser.canEdit(Json.parseToJsonElement("""{"collaborative":true}""").jsonObject))
    }
}
