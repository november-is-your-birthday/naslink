package com.naslink.playback

import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import com.naslink.data.model.RemoteConnection

/** A remote image that has been made readable over the loopback HTTP server. */
data class PublishedRemoteImage(
    val url: String,
    val token: String,
)

/**
 * Opens SMB media on the loopback server, and supplies the cache identity the previews use.
 *
 * Frames are read through [StreamPlaybackCoordinator] rather than
 * `FileProvider.getInputStream` for one specific reason: `getInputStream` opens a brand-new
 * SMB connection per call. A grid asks for dozens of previews at once, and Windows and most
 * NASes cap inbound sessions at roughly 20, so the naive route fails exactly when the
 * feature is most used. The coordinator's pooled session is what makes a grid viable.
 *
 * Photo previews no longer come through here: they are read as bytes and cached as
 * thumbnails (see `RemoteImageThumbnailLoader`). Video frames still need a URL because
 * `MediaMetadataRetriever` does the reading, and that retriever speaks HTTP range requests.
 *
 * Deliberately SMB-only. SFTP, FTP and WebDAV have no equivalent session pool, and WebDAV
 * already has its own `content://` publication path for playback; bolting the loopback
 * server onto them would mean a second, competing mechanism for the same job. Those
 * protocols keep their current download-to-view behaviour until they get a pool of their
 * own.
 */
class RemoteImagePublisher(
    /** The server these files belong to; previews read through it and key on it. */
    val connection: RemoteConnection?,
) {

    /**
     * Video previews are published once per file *and never revoked*, so a folder scrolled
     * through once would leave one loopback session per file behind — each holding an open
     * SMB source until the store's 30-minute idle sweep runs. That is precisely the
     * inbound-session pressure this class's KDoc exists to relieve, so publishing is keyed
     * on the file's stable identity and repeated publishes of the same file share one token.
     *
     * Bounded rather than unbounded-by-luck: an LRU over [PUBLISHED_CACHE_LIMIT] entries caps
     * how many publications a single browsing session can strand, and revoking on eviction is
     * safe because a token only drops a source once nothing is reading it.
     */
    private val publishedTokens = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (size <= PUBLISHED_CACHE_LIMIT) return false
            // Access-ordered, so the eldest is the least recently published or used.
            RemoteImagePublisher.revoke(eldest.value)
            return true
        }
    }
    private val publishedLock = Any()

    /**
     * @return null when the file cannot be published — no connection, a directory, a
     *   non-image, or a path the server rejects. The caller should then fall back to the
     *   file-type icon rather than showing a broken image.
     */
    fun publishVideo(file: FileItem): PublishedRemoteImage? {
        val target = connection ?: return null
        if (file.isDirectory || file.source != FileSource.SMB) return null
        val cacheKey = cacheKey(file)
        synchronized(publishedLock) {
            publishedTokens[cacheKey]?.let { token ->
                if (StreamPlaybackCoordinator.isPublished(token)) {
                    val url = StreamPlaybackCoordinator.urlFor(token) ?: return null
                    return PublishedRemoteImage(url, token)
                }
                publishedTokens.remove(cacheKey)
            }
        }
        return runCatching {
            val published = StreamPlaybackCoordinator.publish(target, file.name, file.path)
            synchronized(publishedLock) { publishedTokens[cacheKey] = published.token }
            PublishedRemoteImage(published.url, published.token)
        }.getOrNull()
    }

    /**
     * Stable cache identity for one remote file, for both Coil's memory and disk caches.
     *
     * This must never be derived from [PublishedRemoteImage.url]. The loopback port is
     * drawn at random when the server starts and the token is 32 fresh random bytes per
     * publish call, so a URL-derived key changes every single time the file is shown:
     * Coil would write a brand-new disk entry per visit and never hit any of them again.
     * The cache then fills with unreachable data until the LRU cap evicts it, which is
     * exactly the "it grows to tens of megabytes and clearing does nothing" symptom.
     *
     * Scoped to the connection because [FileItem.path] carries only the share and the
     * path inside it: two different servers exporting the same share name would
     * otherwise collide and show each other's pictures. Size and mtime are part of the
     * key so an overwritten file is re-fetched rather than served stale from cache.
     */
    fun cacheKey(file: FileItem): String = cacheKey(connection, file)

    companion object {
        /**
         * SVG is excluded: it is a vector format, the platform decoder will not rasterise
         * it, and attempting it would show an error icon for a file that is actually fine.
         */
        fun supports(file: FileItem): Boolean =
            !file.isDirectory &&
                file.source == FileSource.SMB &&
                file.mimeType.startsWith("image/") &&
                file.mimeType != "image/svg+xml"

        /** See [RemoteImagePublisher.cacheKey]; callable without a publisher instance. */
        fun cacheKey(connection: RemoteConnection?, file: FileItem): String = buildString {
            append(connection?.protocol?.name ?: "unconnected")
            append("://")
            append(connection?.username.orEmpty())
            append('@')
            append(connection?.host.orEmpty())
            append(':')
            append(connection?.port ?: 0)
            // A connection may pin one share, in which case the path is relative to it.
            connection?.shareName?.takeIf { it.isNotBlank() }?.let { append('/').append(it) }
            append('/')
            append(file.path.removePrefix("/"))
            append('|').append(file.size)
            append('|').append(file.lastModified.time)
        }

        fun revoke(token: String) = StreamPlaybackCoordinator.revoke(token)

        /**
         * How many video publications one browsing session may hold at once.
         *
         * Sized well above a screenful of previews plus a scrollback buffer, and well below
         * the ~20 inbound sessions a Windows/NAS host typically allows, so a long scroll cannot
         * starve the playback path of connections.
         */
        private const val PUBLISHED_CACHE_LIMIT = 64
    }
}
