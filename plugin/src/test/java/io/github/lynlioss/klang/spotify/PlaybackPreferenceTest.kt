package io.github.lynlioss.klang.spotify

import org.junit.Assert.*
import org.junit.Test

class PlaybackPreferenceTest {
    @Test fun qobuzSelectionRequiresACompleteAccountSetup() {
        listOf(null, "auto", "qobuz", "youtube").forEach { assertEquals("youtube", playbackPreference(it, false)) }
        assertEquals("qobuz", playbackPreference("qobuz", true))
        assertEquals("auto", playbackPreference("auto", true))
        assertEquals("youtube", playbackPreference("youtube", true))
    }
}
