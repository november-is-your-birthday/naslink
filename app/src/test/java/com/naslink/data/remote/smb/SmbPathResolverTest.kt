package com.naslink.data.remote.smb

import com.naslink.data.model.RemoteConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the share/relative split, which used to be duplicated in the playback coordinator
 * and drifted: playback inferred the share from the first path segment even when the
 * connection pinned one, so a pinned-share connection could not play files at all.
 */
class SmbPathResolverTest {

    private fun connection(shareName: String?) = RemoteConnection(
        id = 1,
        name = "nas",
        protocol = com.naslink.data.model.ConnectionProtocol.SMB,
        host = "10.0.0.5",
        port = 445,
        username = "user",
        password = "",
        shareName = shareName,
    )

    @Test
    fun `a pinned share means the whole path is relative to it`() {
        val resolved = SmbPathResolver.resolve(connection("MEDIA"), "/photos/a.jpg")
        assertEquals("MEDIA", resolved.shareName)
        assertEquals("photos\\a.jpg", resolved.relativePath)
    }

    @Test
    fun `a pinned share is not taken from the path even when the path repeats it`() {
        // This is the case the old playback copy got wrong.
        val resolved = SmbPathResolver.resolve(connection("MEDIA"), "/MEDIA/photos/a.jpg")
        assertEquals("MEDIA", resolved.shareName)
        assertEquals("MEDIA\\photos\\a.jpg", resolved.relativePath)
    }

    @Test
    fun `without a pinned share the first segment is the share`() {
        val resolved = SmbPathResolver.resolve(connection(null), "/MEDIA/photos/a.jpg")
        assertEquals("MEDIA", resolved.shareName)
        assertEquals("photos\\a.jpg", resolved.relativePath)
    }

    @Test
    fun `the share is validated against the discovered shares in discovery mode`() {
        val resolved = SmbPathResolver.resolve(connection(null), "/media/a.jpg") { candidate ->
            // Servers commonly report a different case than the user typed.
            if (candidate.equals("MEDIA", ignoreCase = true)) "MEDIA" else null
        }
        assertEquals("the discovered casing must be used for tree-connect", "MEDIA", resolved.shareName)
    }

    @Test
    fun `an unknown share is rejected when validation is supplied`() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathResolver.resolve(connection(null), "/nope/a.jpg") { null }
        }
    }

    @Test
    fun `an unknown share is taken at face value when validation is not available`() {
        // Playback has no share enumeration; failing at tree-connect gives the server's own
        // error, which beats a client-side guess.
        val resolved = SmbPathResolver.resolve(connection(null), "/nope/a.jpg", validateShare = null)
        assertEquals("nope", resolved.shareName)
    }

    @Test
    fun `the virtual root is not a real path`() {
        assertThrows(SmbVirtualRootException::class.java) {
            SmbPathResolver.resolve(connection(null), "/")
        }
    }

    @Test
    fun `resolveFile additionally rejects a share root`() {
        // The share root lists directories; it is not a file to stream.
        assertThrows(SmbVirtualRootException::class.java) {
            SmbPathResolver.resolveFile(connection(null), "/MEDIA")
        }
    }

    @Test
    fun `resolveFile accepts a path below the share root`() {
        val resolved = SmbPathResolver.resolveFile(connection(null), "/MEDIA/a.jpg")
        assertEquals("MEDIA", resolved.shareName)
        assertEquals("a.jpg", resolved.relativePath)
    }

    @Test
    fun `a blank share name counts as discovery mode`() {
        val resolved = SmbPathResolver.resolve(connection("   "), "/MEDIA/a.jpg")
        assertEquals("MEDIA", resolved.shareName)
        assertEquals("a.jpg", resolved.relativePath)
    }

    @Test
    fun `separators are normalised to the smb form`() {
        assertEquals("a\\b\\c.jpg", SmbPathResolver.toSmbPath("/a/b/c.jpg"))
    }
}
