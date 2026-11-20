package com.naslink.playback

import com.naslink.data.model.ConnectionProtocol
import com.naslink.data.model.RemoteConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The resume-position key: one file must produce one key no matter which connection
 * shape reached it, or "continue watching" silently misses.
 */
class StreamPlaybackCoordinatorMediaKeyTest {

    private val connection = RemoteConnection(
        name = "key test", protocol = ConnectionProtocol.SMB,
        host = "NAS", port = 445, username = "user", shareName = "media",
    )

    @Test
    fun pinnedShareAndDiscoveryModeProduceTheSameKey() {
        val pinned = StreamPlaybackCoordinator.mediaKeyFor(connection, "/videos/movie.mkv")
        val discovery = StreamPlaybackCoordinator.mediaKeyFor(connection.copy(shareName = null), "/media/videos/movie.mkv")
        assertEquals(pinned, discovery)
    }

    @Test
    fun caseAndSeparatorDifferencesDoNotSplitTheKey() {
        val normal = StreamPlaybackCoordinator.mediaKeyFor(connection, "/Videos/Movie.mkv")
        assertEquals(normal, StreamPlaybackCoordinator.mediaKeyFor(connection.copy(shareName = " MEDIA "), "\\videos\\movie.mkv"))
    }

    @Test
    fun differentFilesAndServersGetDifferentKeys() {
        val base = StreamPlaybackCoordinator.mediaKeyFor(connection, "/videos/movie.mkv")
        assertNotEquals(base, StreamPlaybackCoordinator.mediaKeyFor(connection, "/videos/other.mkv"))
        assertNotEquals(base, StreamPlaybackCoordinator.mediaKeyFor(connection.copy(host = "elsewhere"), "/videos/movie.mkv"))
        assertNotEquals(base, StreamPlaybackCoordinator.mediaKeyFor(connection.copy(shareName = "photos"), "/videos/movie.mkv"))
    }

    @Test
    fun unresolvablePathsProduceAnEmptyKeyInsteadOfThrowing() {
        // Discovery-mode "/" is the virtual root with no file behind it; a pinned "/"
        // is the share root itself and keeps a well-defined key.
        assertEquals("", StreamPlaybackCoordinator.mediaKeyFor(connection.copy(shareName = null), "/"))
        assertEquals(
            StreamPlaybackCoordinator.mediaKeyFor(connection, "/"),
            StreamPlaybackCoordinator.mediaKeyFor(connection, "/nonexistent/../."),
        )
    }
}
