package com.naslink.playback

import android.util.Log
import com.naslink.data.model.RemoteConnection
import com.naslink.data.remote.smb.SmbPathResolver
import com.naslink.stream.LoopbackStreamServer
import com.naslink.stream.RandomAccessSource
import com.naslink.stream.SmbRandomAccessSource
import com.naslink.stream.SmbSessionPool
import com.naslink.stream.SmbTarget
import com.naslink.stream.StreamUrl
import java.io.IOException

/**
 * Owns the process-wide loopback stream server.
 *
 * Publishing is deliberately separate from playback: the returned URL is a plain
 * `http://127.0.0.1` address, so the same session can be handed to the built-in player,
 * to MX Player / VLC, or to Coil for image decoding without a second SMB handshake.
 */
object StreamPlaybackCoordinator {

    private const val TAG = "NasLinkStream"

    private val server = LoopbackStreamServer(logger = { Log.d(TAG, it) })

    /**
     * Shared across every publication so consecutive reads against one NAS reuse a single
     * authenticated SMB session. A fresh `SmbRandomAccessSource` per request would dial a
     * new inbound connection each time, and Windows caps those at roughly 20 — a photo grid
     * would exhaust that budget on its own.
     */
    private val sessions = SmbSessionPool()

    @Volatile
    private var started = false

    private val playableExtensions = setOf(
        "mkv", "mp4", "m4v", "mov", "webm", "avi", "ts", "m2ts", "flv",
        "mpg", "mpeg", "wmv", "rmvb", "mp3", "flac", "aac", "m4a", "ogg", "opus", "wav",
    )

    /** Whether this file should be streamed instead of downloaded. */
    fun isPlayable(name: String): Boolean {
        val extension = name.substringAfterLast('.', "")
        return extension.isNotEmpty() && extension.lowercase() in playableExtensions
    }

    /**
     * Whether this file can be shown by the built-in full-screen image viewer.
     *
     * Kept separate from [isPlayable] on purpose: mixing them would make the media player
     * offer to open a JPEG, and the image viewer offer to open a MKV.
     */
    fun isViewableImage(mimeType: String): Boolean =
        mimeType.startsWith("image/") && mimeType != "image/svg+xml"

    fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v", "mov" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "ts", "m2ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            // Images. Named explicitly rather than falling through to octet-stream: the
            // loopback server sends this as Content-Type, and Coil picks its decoder from
            // it rather than sniffing the bytes.
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    /**
     * Stable, case-normalised identity for one SMB file: host, port, share and the
     * share-relative path segments. The resume-position store keys on this, so the same
     * file reached through a pinned share or through discovery mode shares one entry.
     * Dot segments fold the same way [com.naslink.data.remote.smb.SmbFileProvider] folds
     * them when comparing paths. Empty when the path cannot be resolved — playback then
     * simply has no memory.
     */
    fun mediaKeyFor(connection: RemoteConnection, path: String): String {
        val resolved = runCatching {
            SmbPathResolver.resolveFile(connection, path, validateShare = null)
        }.getOrNull() ?: return ""
        val segments = mutableListOf(resolved.shareName.lowercase())
        for (segment in resolved.relativePath.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.size > 1) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment.lowercase())
            }
        }
        return listOf(connection.host.lowercase(), connection.port.toString())
            .plus(segments)
            .joinToString("|")
    }

    /**
     * Publishes an SMB file and returns a URL a client can read over HTTP with Range support.
     *
     * The share/relative split comes from [SmbPathResolver], the same code the browser
     * uses. Sharing it matters: an earlier private copy inferred the share from the first
     * path segment even when the connection pinned one, which made playback disagree with
     * the listing it was launched from.
     *
     * @throws IOException when the file cannot be opened.
     * @throws IllegalStateException when [path] does not address a file.
     */
    @Synchronized
    @Throws(IOException::class, IllegalStateException::class)
    fun publish(connection: RemoteConnection, name: String, path: String): StreamUrl {
        if (!started) {
            server.start()
            started = true
        }
        val target = targetFor(connection, path)
        return server.publish(name, mimeTypeFor(name)) { SmbRandomAccessSource(target, pool = sessions) }
    }

    /**
     * Opens a seekable read handle on one SMB file, for callers that want bytes rather
     * than a URL.
     *
     * Used by the thumbnail pipeline to read a bounded prefix of a picture: publishing a
     * URL first would mint a token, start the HTTP server and keep the publication alive
     * for the idle timeout, all to read a few kilobytes once. The handle still borrows the
     * pooled session, so a grid of previews does not dial a connection per picture. The
     * caller owns it and must close it.
     *
     * Does not require the loopback server: thumbnails do not need to be reachable over
     * HTTP, only readable.
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun openRandomAccess(connection: RemoteConnection, path: String): RandomAccessSource =
        SmbRandomAccessSource(targetFor(connection, path), pool = sessions)

    private fun targetFor(connection: RemoteConnection, path: String): SmbTarget {
        // There is no share enumeration handy here, so an unvalidated name is taken at face
        // value; a wrong one fails at tree-connect with the server's own error, which is
        // more informative than a client-side guess.
        val resolved = SmbPathResolver.resolveFile(connection, path, validateShare = null)
        require(resolved.relativePath.isNotEmpty()) { "SMB path must point at a file" }
        return SmbTarget(
            host = connection.host,
            port = connection.port.takeIf { it > 0 } ?: SmbTarget.DEFAULT_PORT,
            share = resolved.shareName,
            path = resolved.relativePath,
            username = connection.username,
            password = connection.password.toCharArray(),
            domain = connection.domain,
            // Browsing always authenticates with the stored credentials; doing the same
            // here keeps browsing and streaming on the identical code path.
            anonymous = false,
        )
    }

    @Synchronized
    fun revoke(token: String) {
        if (started) server.revoke(token)
    }

    /**
     * Lets a publication give its SMB handle back to the pool while staying readable.
     *
     * The viewer calls this for pages the user has paged away from. Keeping the handle
     * would mean one NAS session per viewed picture; giving it back costs a reconnect only
     * if the user returns to that page.
     */
    @Synchronized
    fun releaseHandle(token: String) {
        if (started) server.releaseHandle(token)
    }

    /**
     * Whether [token] still resolves to a live publication.
     *
     * Lets a caller holding a remembered token re-check it instead of assuming: the server's
     * idle sweep and explicit revokes both retire tokens behind the caller's back, and
     * handing back a URL whose token has been retired produces a broken image rather than a
     * silent miss.
     */
    @Synchronized
    fun isPublished(token: String): Boolean = started && server.isPublished(token)

    /** The loopback URL for a still-live [token], or null once it has been retired. */
    @Synchronized
    fun urlFor(token: String): String? = if (started) server.urlFor(token) else null

    /**
     * Releases the loopback server and every pooled SMB session. Call when the user signs
     * out or disconnects, so credentials do not linger in a live authenticated session.
     */
    @Synchronized
    fun stop() {
        if (started) {
            server.stop()
            started = false
        }
        sessions.clear()
    }
}
