package com.naslink.data.remote.smb

import com.naslink.data.model.RemoteConnection

/**
 * A browser path split into the two halves the SMB protocol needs: the share to tree-connect
 * to and the path relative to that share.
 */
internal data class ResolvedSharePath(
    val shareName: String,
    val relativePath: String,
)

/**
 * Turns a browser path into a share plus a share-relative path.
 *
 * This used to be duplicated in [SmbFileProvider] and again in the playback coordinator,
 * and the copies drifted: playback inferred the share from the first path segment even when
 * the saved connection already pinned one, so a pinned-share connection could play
 * directories and fail on files. Sharing one implementation keeps that from recurring.
 *
 * The rule, in full:
 * - A connection that pins a share already stores browser paths **relative to it**, so the
 *   whole path is the relative part.
 * - Otherwise the connection is in discovery mode and the **first segment is the share**,
 *   which must be validated against the discovered shares.
 */
internal object SmbPathResolver {

    /**
     * @param validateShare invoked in discovery mode to map the path's share segment to the
     *   server's own spelling of it. Returning null means the share does not exist and makes
     *   this function throw. Pass null when no enumeration is available — playback, for
     *   instance — and the name is taken at face value so the server produces the error.
     * @throws SmbVirtualRootException when the path addresses the virtual root, which is a
     *   browser-only entry point with no server-side counterpart.
     * @throws IllegalArgumentException when [validateShare] rejects the share.
     */
    fun resolve(
        connection: RemoteConnection,
        path: String,
        validateShare: ((String) -> String?)? = null,
    ): ResolvedSharePath {
        val configuredShare = connection.shareName?.trim().orEmpty()
        if (configuredShare.isNotEmpty()) {
            return ResolvedSharePath(configuredShare, toSmbPath(path))
        }
        return when (val parsed = SmbBrowsePath.parse(path)) {
            SmbBrowsePath.VirtualRoot -> throw SmbVirtualRootException()
            is SmbBrowsePath.Share -> {
                // The two null cases mean different things and must not be collapsed:
                // no validator at all means "take the name at face value", whereas a
                // validator that returns null means "this share does not exist".
                val name = if (validateShare == null) {
                    parsed.name
                } else {
                    validateShare(parsed.name)
                        ?: throw IllegalArgumentException("SMB share is not available: ${parsed.name}")
                }
                ResolvedSharePath(name, parsed.relativePath)
            }
        }
    }

    /** Like [resolve], but also rejects a path that lands on the share root. */
    fun resolveFile(
        connection: RemoteConnection,
        path: String,
        validateShare: ((String) -> String?)? = null,
    ): ResolvedSharePath = resolve(connection, path, validateShare).also {
        if (connection.shareName?.trim().isNullOrEmpty() && it.relativePath.isEmpty()) {
            throw SmbVirtualRootException()
        }
    }

    fun toSmbPath(path: String): String = path.removePrefix("/").replace("/", "\\")
}
