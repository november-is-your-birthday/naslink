package com.naslink.data.remote.ftp

import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import com.naslink.data.model.RemoteConnection
import com.naslink.data.repository.FileProvider
import com.naslink.data.repository.RenameGuard
import com.naslink.data.repository.StreamTransfer
import com.naslink.data.repository.StreamTransferProgress
import com.naslink.data.repository.TransferCancellation
import com.naslink.data.repository.TransferAbortable
import com.naslink.data.repository.ForwardingOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.MalformedServerReplyException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class FtpFileProvider(
    private val connection: RemoteConnection,
    private val temporaryDirectory: File,
) : FileProvider {
    override fun isSameStorage(other: FileProvider): Boolean = other is FtpFileProvider &&
        connection.host.equals(other.connection.host, ignoreCase = true) && connection.port == other.connection.port &&
        connection.username == other.connection.username && connection.shareName == other.connection.shareName

    /**
     * Guards the connect/replace handshake in [ensureConnected].
     *
     * Without it two coroutines can both observe a null client, both dial, and the second
     * assignment silently discards the first connection — which nothing will ever close.
     * `SmbFileProvider` and `SftpFileProvider` already serialise their equivalent state for
     * exactly this reason.
     */
    private val connectLock = Mutex()

    /**
     * Volatile because [disconnect] nulls it from one coroutine while another is mid-operation:
     * without the visibility guarantee a stale non-null read can survive past the disconnect.
     */
    @Volatile
    private var ftpClient: FTPClient? = null

    /**
     * Returns a connected, ready-to-use client, dialling one only when needed.
     *
     * The client is returned rather than re-read from the field so callers act on exactly the
     * connection that was just validated. A concurrent [disconnect] can still tear that socket
     * down underneath them, which surfaces as an IOException from the command being issued —
     * a truthful failure — instead of the NullPointerException that dereferencing a field
     * another coroutine just nulled would produce.
     */
    private suspend fun ensureConnected(): FTPClient = connectLock.withLock {
        val existingClient = ftpClient
        if (existingClient != null) {
            val connectionIsHealthy = existingClient.isConnected &&
                runCatching { existingClient.sendNoOp() }.getOrDefault(false)
            if (connectionIsHealthy) return@withLock existingClient
            runCatching { existingClient.disconnect() }
            ftpClient = null
        }

        val ftp = FTPClient().apply {
            connectTimeout = 30000
            defaultTimeout = 30000
            setDataTimeout(java.time.Duration.ofSeconds(30))
            bufferSize = TRANSFER_BUFFER_SIZE
        }
        try {
            ftp.connect(connection.host, connection.port)

            val reply = ftp.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                throw IllegalStateException("FTP server refused connection: $reply")
            }

            if (!ftp.login(connection.username, connection.password)) {
                throw IllegalStateException("FTP login failed")
            }

            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient = ftp
            ftp
        } catch (error: Throwable) {
            runCatching { if (ftp.isConnected) ftp.disconnect() }
            throw error
        }
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ftp = ensureConnected()
                ftp.listFiles(path).filter { it.name != "." && it.name != ".." }.map { file ->
                    val name = entryName(file.name)
                    file.toFileItem(name = name, path = joinPath(path, name))
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                if (!ensureConnected().makeDirectory(fullPath)) {
                    throw IllegalStateException("Failed to create directory")
                }
                FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.FTP)
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                if (!ensureConnected().storeFile(fullPath, ByteArrayInputStream(ByteArray(0)))) {
                    throw IllegalStateException("Failed to create file")
                }
                FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.FTP)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ftp = ensureConnected()
                if (ftp.isDirectory(path)) {
                    deleteDirectoryRecursive(ftp, path)
                } else {
                    if (!ftp.deleteFile(path)) {
                        throw IllegalStateException("Failed to delete: $path")
                    }
                }
            }
        }

    /**
     * The entry name as this provider treats it: the last path segment only.
     *
     * Servers answer `LIST` either with a bare file name or, like ProFTPD and Pure-FTPd,
     * with the path exactly as the client sent it. Recursion builds every child path from
     * its parent's, so reusing a full-path `name` yields `/a/b//a/b/c.txt` and each delete
     * or copy below that point addresses a file that does not exist. Normalising in one
     * place keeps [listFiles], [deleteDirectoryRecursive] and [copyDirectoryRecursive] agreeing.
     */
    private fun entryName(rawName: String): String = rawName.substringAfterLast('/')

    private fun deleteDirectoryRecursive(ftp: FTPClient, path: String) {
        for (file in ftp.listFiles(path)) {
            if (file.name == "." || file.name == "..") continue
            val filePath = "$path/${entryName(file.name)}"
            if (file.isDirectory) {
                deleteDirectoryRecursive(ftp, filePath)
            } else {
                ftp.deleteFile(filePath)
            }
        }
        ftp.removeDirectory(path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                if (RenameGuard.blocked(oldPath, newPath, exists(newPath), ignoreCase = false)) {
                    throw IllegalStateException("An item named $newName already exists in this folder")
                }
                if (!ensureConnected().rename(oldPath, newPath)) {
                    throw IllegalStateException("Failed to rename")
                }
                FileItem(name = newName, path = newPath, isDirectory = false, source = FileSource.FTP)
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ftp = ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                val targetPath = joinPath(destPath, name)
                TransferCancellation.check()
                check(ftp.listFiles(destPath).none { it.name == name }) {
                    "An item named $name already exists in this folder"
                }
                var ownsTarget = false
                try {
                    if (ftp.isDirectory(sourcePath)) {
                        check(ftp.makeDirectory(targetPath)) { "Failed to create directory: $targetPath" }
                        ownsTarget = true
                        copyDirectoryRecursive(ftp, sourcePath, targetPath)
                    } else {
                        copyFileThroughTemporaryStorage(ftp, sourcePath, targetPath) { ownsTarget = true }
                    }
                    TransferCancellation.check()
                } catch (error: Throwable) {
                    if (ownsTarget) runCatching { delete(targetPath).getOrThrow() }.onFailure(error::addSuppressed)
                    throw error
                }
                Unit
            }
        }

    private fun copyDirectoryRecursive(ftp: FTPClient, sourcePath: String, targetPath: String) {
        TransferCancellation.check()
        for (file in ftp.listFiles(sourcePath)) {
            if (file.name == "." || file.name == "..") continue
            // entryName, not file.name: see its KDoc — full-path replies would otherwise
            // double the path segment and address a file that does not exist.
            val childName = entryName(file.name)
            val sourceChild = joinPath(sourcePath, childName)
            val targetChild = joinPath(targetPath, childName)
            if (file.isDirectory) {
                TransferCancellation.check()
                check(ftp.makeDirectory(targetChild)) { "Failed to create directory: $targetChild" }
                copyDirectoryRecursive(ftp, sourceChild, targetChild)
            } else {
                copyFileThroughTemporaryStorage(ftp, sourceChild, targetChild)
            }
        }
    }

    override suspend fun copyFileTo(
        sourcePath: String,
        targetPath: String,
        totalBytes: Long?,
        onProgress: (StreamTransferProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copyFileThroughTemporaryStorage(ensureConnected(), sourcePath, targetPath, onProgress = onProgress)
        }
    }

    private fun copyFileThroughTemporaryStorage(
        ftp: FTPClient,
        sourcePath: String,
        targetPath: String,
        onProgress: (StreamTransferProgress) -> Unit = {},
        onTargetCreated: () -> Unit = {},
    ) {
        TransferCancellation.check()
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
            "Could not prepare temporary storage for the copy"
        }
        val temporaryFile = File.createTempFile("naslink-ftp-", ".copy", temporaryDirectory)
        try {
            val source = ftp.retrieveFileStream(sourcePath)
                ?: throw IllegalStateException("Failed to read: $sourcePath")
            PendingCommandInputStream(ftp, source, sourcePath).use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    StreamTransfer.copy(input, output, sourcePath, null)
                }
            }
            TransferCancellation.check()
            onTargetCreated()
            val target = ftp.storeFileStream(targetPath)
                ?: throw IllegalStateException("Failed to write: $targetPath")
            PendingCommandOutputStream(ftp, target, targetPath).use { output ->
                FileInputStream(temporaryFile).use { input ->
                    StreamTransfer.copy(input, output, sourcePath, temporaryFile.length(), onProgress = onProgress)
                }
            }
        } finally {
            temporaryFile.delete()
        }
    }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = sourcePath.substringAfterLast("/")
                if (!ensureConnected().rename(sourcePath, "$destPath/$name")) {
                    throw IllegalStateException("Failed to move")
                }
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ftp = ensureConnected()
                val input = ftp.retrieveFileStream(path)
                    ?: throw IllegalStateException("Failed to read: $path")
                PendingCommandInputStream(ftp, input, path) as InputStream
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ftp = ensureConnected()
                val output = ftp.storeFileStream(path)
                    ?: throw IllegalStateException("Failed to write: $path")
                PendingCommandOutputStream(ftp, output, path) as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected().statPath(path) != null
            } catch (cancelled: CancellationException) {
                // Cancellation is not an answer to "does this exist". Swallowing it here
                // would let a cancelled coroutine keep running and report `false` for a file
                // it never actually checked.
                throw cancelled
            } catch (_: IOException) {
                // A transport failure means "cannot tell", which this signature can only
                // render as false. Worth noting: callers that gate destructive actions on
                // this will proceed, so genuine I/O faults are not distinguishable here.
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected().statPath(path) ?: throw IllegalArgumentException("Not found: $path")
            }
        }

    /**
     * Describes the entry at [path], or returns null when nothing is there.
     *
     * The name always comes from the path. Servers answer `LIST file` either with the file name
     * or, like ProFTPD and Pure-FTPd, with the path exactly as the client sent it, and `LIST
     * directory` describes the directory's children rather than the directory itself, so a
     * listing cannot be trusted for the name or the type. MLST reports both for the path itself
     * in one round trip where the server advertises it. Otherwise a directory is recognised by
     * changing into it and a file by its one-entry listing.
     */
    private fun FTPClient.statPath(path: String): FileItem? {
        val name = nameOf(path)
        if (hasFeature("MLST")) {
            val listed = try {
                mlistFile(path)
            } catch (_: MalformedServerReplyException) {
                null
            }
            if (listed != null) return listed.toFileItem(name, path)
        }
        if (isDirectory(path)) {
            return FileItem(
                name = name,
                path = path,
                isDirectory = true,
                isHidden = name.startsWith("."),
                source = FileSource.FTP,
            )
        }
        val entry = listFiles(path).singleOrNull { it.name != "." && it.name != ".." }
        return entry?.toFileItem(name, path)
    }

    private fun FTPFile.toFileItem(name: String, path: String): FileItem = FileItem(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        lastModified = timestamp?.let { Date(it.timeInMillis) } ?: Date(),
        isHidden = name.startsWith("."),
        owner = user,
        source = FileSource.FTP,
    )

    private fun nameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    override fun getParentPath(path: String): String? {
        if (path == "/") return null
        val parent = path.substringBeforeLast("/")
        return parent.ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching {
                ftpClient?.logout()
                ftpClient?.disconnect()
            }
            ftpClient = null
        }
    }

    private fun FTPClient.isDirectory(path: String): Boolean {
        val currentPath = printWorkingDirectory()
        val changed = changeWorkingDirectory(path)
        if (changed && currentPath != null) {
            changeWorkingDirectory(currentPath)
        }
        return changed
    }

    private fun joinPath(parent: String, child: String): String = when {
        parent.isBlank() || parent == "/" -> "/$child"
        parent.endsWith("/") -> "$parent$child"
        else -> "$parent/$child"
    }

    private class PendingCommandInputStream(
        private val ftp: FTPClient,
        input: InputStream,
        private val path: String,
    ) : FilterInputStream(input), TransferAbortable {
        override fun abortTransfer() {
            runCatching { `in`.close() }
            runCatching { ftp.disconnect() }
        }
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                if (!ftp.completePendingCommand()) {
                    throw IllegalStateException("Failed to finish reading: $path")
                }
            }
        }
    }

    private class PendingCommandOutputStream(
        private val ftp: FTPClient,
        private val output: OutputStream,
        private val path: String,
    ) : ForwardingOutputStream(output), TransferAbortable {
        override fun abortTransfer() {
            runCatching { output.close() }
            runCatching { ftp.disconnect() }
        }
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                if (!ftp.completePendingCommand()) {
                    throw IllegalStateException("Failed to finish writing: $path")
                }
            }
        }
    }

    private companion object {
        // Matches the StreamTransfer chunk size. Commons Net otherwise buffers data connections
        // in 8 KiB and copies whole files, as used by provider-local copy, in 1 KiB steps.
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
    }
}
