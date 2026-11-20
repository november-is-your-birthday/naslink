package com.naslink.data.repository

/**
 * The rename-overwrite guard shared by every provider that a remote server would
 * otherwise silently honour: SMB, SFTP, FTP, WebDAV and SAF all replace an existing
 * target without asking, so a one-letter typo in a rename dialog could destroy a file.
 *
 * [ignoreCase] follows each protocol's filesystem: SMB servers are case-insensitive
 * (a case-only rename addresses the same entry), while SFTP/FTP/WebDAV are usually
 * case-sensitive and must compare byte for byte.
 */
internal object RenameGuard {

    fun blocked(
        sourcePath: String,
        targetPath: String,
        targetExists: Boolean,
        ignoreCase: Boolean,
    ): Boolean {
        if (!targetExists) return false
        return if (ignoreCase) !targetPath.equals(sourcePath, ignoreCase = true)
        else targetPath != sourcePath
    }
}
