package com.naslink.playback

import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decides which files the built-in viewer and the remote thumbnail path take.
 *
 * Every case here was a way the feature could misfire: showing an error icon for a
 * perfectly good file, or trying to publish a directory.
 */
class RemoteImagePublisherTest {

    private fun item(
        name: String,
        source: FileSource = FileSource.SMB,
        directory: Boolean = false,
    ) = FileItem(
        name = name,
        path = "/media/$name",
        isDirectory = directory,
        size = 2048,
        source = source,
    )

    @Test
    fun `ordinary photo formats are supported`() {
        for (name in listOf("a.jpg", "b.jpeg", "c.png", "d.webp", "e.gif", "f.bmp")) {
            assertTrue(name, RemoteImagePublisher.supports(item(name)))
        }
    }

    @Test
    fun `svg is excluded because the platform decoder cannot rasterise it`() {
        // Letting Coil try would show an error icon for a file that is actually fine, which
        // reads as data loss to the user.
        assertFalse(RemoteImagePublisher.supports(item("logo.svg")))
    }

    @Test
    fun `directories are never published`() {
        assertFalse(RemoteImagePublisher.supports(item("photos", directory = true)))
    }

    @Test
    fun `non-images are never published`() {
        for (name in listOf("a.mp4", "b.mkv", "c.pdf", "d.apk", "e.txt", "f")) {
            assertFalse(name, RemoteImagePublisher.supports(item(name)))
        }
    }

    @Test
    fun `only smb is supported, because it is the only protocol with a session pool`() {
        // SFTP/FTP/WebDAV would need a pooled session to be viable in a grid; without one
        // each thumbnail would dial a fresh connection.
        assertFalse(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.SFTP)))
        assertFalse(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.FTP)))
        assertFalse(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.WEBDAV)))
        assertTrue(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.SMB)))
    }

    @Test
    fun `local and saf files keep using the existing local thumbnail path`() {
        // Routing them here would publish them over SMB-shaped plumbing they do not need.
        assertFalse(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.LOCAL)))
        assertFalse(RemoteImagePublisher.supports(item("a.jpg", source = FileSource.SAF)))
    }

    @Test
    fun `the extension check is case insensitive`() {
        assertTrue(RemoteImagePublisher.supports(item("PHOTO.JPG")))
        assertTrue(RemoteImagePublisher.supports(item("Photo.Png")))
    }
}
