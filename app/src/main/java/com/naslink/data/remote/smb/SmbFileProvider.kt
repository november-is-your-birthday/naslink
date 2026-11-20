package com.naslink.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.share.DiskShare
import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import com.naslink.data.model.RemoteConnection
import com.naslink.data.repository.FileProvider
import com.naslink.data.repository.RenameGuard
import com.naslink.data.repository.ForwardingOutputStream
import com.naslink.data.repository.TransferAbortable
import com.naslink.stream.SmbSessionPool
import javax.net.SocketFactory
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.EnumSet
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SmbVirtualRootException(
    message: String = "The SMB server and share roots are read-only browser entries",
) : IllegalStateException(message)

internal interface SmbSessionHandle {
    fun discover(discovery: SmbShareDiscovery): List<SmbDiscoveredShare>
    fun connectShare(name: String): DiskShare
    fun close()
}

internal fun interface SmbSessionHandleFactory {
    fun connect(connection: RemoteConnection): SmbSessionHandle
    fun connectTransfer(connection: RemoteConnection, sockets: SocketFactory): SmbSessionHandle = connect(connection)
}

private object DefaultSmbSessionHandleFactory : SmbSessionHandleFactory {
    override fun connect(connection: RemoteConnection): SmbSessionHandle = connect(connection, null)

    override fun connectTransfer(connection: RemoteConnection, sockets: SocketFactory): SmbSessionHandle = connect(connection, sockets)

    private fun connect(connection: RemoteConnection, sockets: SocketFactory?): SmbSessionHandle {
        var client: SMBClient? = null
        var smbConnection: Connection? = null
        var session: Session? = null
        try {
            // Built from the playback path's own configuration so browsing cannot drift
            // away from streaming; only the socket factory is specific to this call.
            val base = SmbSessionPool.defaultConfig(SmbClientSettings.ENCRYPT_DATA)
            val config = if (sockets == null) {
                base
            } else {
                SmbConfig.builder(base).withSocketFactory(sockets).build()
            }
            client = SMBClient(config)
            smbConnection = client.connect(connection.host, connection.port)
            session = smbConnection.authenticate(
                AuthenticationContext(
                    connection.username,
                    connection.password.toCharArray(),
                    connection.domain ?: "",
                ),
            )
            return RealSmbSessionHandle(client, smbConnection, session)
        } catch (error: Throwable) {
            if (sockets is SmbTransferSocketFactory) sockets.close()
            runCatching { session?.close() }
            runCatching { smbConnection?.close() }
            runCatching { client?.close() }
            throw error
        }
    }
}

/**
 * One SMB configuration for browsing, streaming and transferring.
 *
 * Browsing and streaming used to disagree on `withEncryptData`, which made files list
 * but fail to play (or the reverse) depending on the path taken. Every other value now
 * comes from [SmbSessionPool] — the same source the playback path uses — so the two
 * cannot drift apart again as either side is edited.
 *
 * The transfer chunk sizes are deliberately *not* unified with the SMB read buffer: that
 * one is the READ request size, while the loopback server's 256 KiB and the download
 * path's 64 KiB are socket and disk copy chunks. They sit at different layers and a
 * different value is correct for each.
 */
internal object SmbClientSettings {
    /** Off by default: it breaks against servers that advertise but mishandle it. */
    const val ENCRYPT_DATA: Boolean = false
}

private class RealSmbSessionHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : SmbSessionHandle {
    override fun discover(discovery: SmbShareDiscovery): List<SmbDiscoveredShare> = discovery.discover(session)

    override fun connectShare(name: String): DiskShare = session.connectShare(name) as DiskShare

    override fun close() {
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

class SmbFileProvider internal constructor(
    private val connection: RemoteConnection,
    private val shareDiscovery: SmbShareDiscovery = DceRpcSmbShareDiscovery,
    private val sessionFactory: SmbSessionHandleFactory = DefaultSmbSessionHandleFactory,
    private val transferSocketFactory: () -> SmbTransferSocketFactory = { SmbTransferSocketFactory() },
) : FileProvider {
    override fun isSameStorage(other: FileProvider): Boolean = other is SmbFileProvider &&
        connection.host.equals(other.connection.host, ignoreCase = true) && connection.port == other.connection.port &&
        connection.username == other.connection.username && connection.shareName == other.connection.shareName

    private val configuredShare = connection.shareName?.trim().orEmpty()
    private val isDiscoveryMode = configuredShare.isEmpty()

    override fun isSamePath(path: String, other: FileProvider, otherPath: String): Boolean {
        if (other !is SmbFileProvider || !sameServer(other)) return false
        val first = identityPath(path) ?: return false
        val second = other.identityPath(otherPath) ?: return false
        return first == second
    }

    override suspend fun isDescendantPath(ancestor: String, other: FileProvider, path: String): Boolean {
        if (other !is SmbFileProvider || !sameServer(other)) return false
        val parent = identityPath(ancestor) ?: return false
        val child = other.identityPath(path) ?: return false
        return child.size >= parent.size && child.take(parent.size) == parent
    }

    private fun sameServer(other: SmbFileProvider): Boolean =
        connection.host.equals(other.connection.host, ignoreCase = true) && connection.port == other.connection.port

    private fun identityPath(path: String): List<String>? {
        // No share validation: this only builds a comparison key, so a stale or mistyped
        // share must still normalise rather than throw. Only a discovery-mode virtual root
        // has no identity; for a pinned-share connection "/" IS the share itself, so it
        // must compare equal to the same share addressed from discovery mode.
        if (path == "/" && isDiscoveryMode) return null
        val resolved = SmbPathResolver.resolve(connection, path, validateShare = null)
        // Conservatively treat matching server/share paths as aliases even when credentials or initial folders differ.
        val segments = mutableListOf(resolved.shareName.lowercase(java.util.Locale.ROOT))
        for (segment in resolved.relativePath.replace('/', '\\').split('\\')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.size > 1) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment.lowercase(java.util.Locale.ROOT))
            }
        }
        return segments
    }

    // Every mutator below is a check-then-act sequence over connection state. Without a
    // lock, two coroutines entering ensureSession() each build a connection and the loser's
    // connection leaks -- and Windows/NAS cap inbound sessions at roughly 20, so a handful
    // of leaked handles is enough to make the server refuse new connections.
    private val stateLock = Any()

    @Volatile
    private var sessionHandle: SmbSessionHandle? = null

    @Volatile
    private var activeShareName: String? = null

    @Volatile
    private var share: DiskShare? = null

    @Volatile
    private var discoveredShares: List<SmbDiscoveredShare>? = null

    @Volatile
    private var virtualRootItemsCache: List<FileItem>? = null

    private fun ensureSession(): SmbSessionHandle = sessionHandle ?: synchronized(stateLock) {
        sessionHandle ?: sessionFactory.connect(connection).also { sessionHandle = it }
    }

    private fun ensureShare(name: String): DiskShare {
        share?.let { if (activeShareName == name) return it }
        return synchronized(stateLock) {
            // Re-check under the lock: another coroutine may have switched the share already.
            share?.let { if (activeShareName == name) return it }
            runCatching { share?.close() }
            share = null
            activeShareName = null
            val opened = ensureSessionLocked().connectShare(name)
            share = opened
            activeShareName = name
            opened
        }
    }

    /** Must be called with [stateLock] held. */
    private fun ensureSessionLocked(): SmbSessionHandle = sessionHandle
        ?: sessionFactory.connect(connection).also { sessionHandle = it }

    /**
     * Runs [block] against the share, recovering once from a transport that died behind
     * our back: an idle NAS rebooted its session or the WiFi hopped, and the cached
     * DiskShare now only throws "already been closed". The cached share, session and
     * handle are dropped and a single fresh dial is attempted; every other error
     * propagates unchanged. Two coroutines failing at once may each redial — the loser's
     * result is an honest error, not a leaked connection.
     */
    private inline fun <T> withLiveShare(name: String, block: (DiskShare) -> T): T {
        try {
            return block(ensureShare(name))
        } catch (error: Exception) {
            if (!isDeadTransport(error)) throw error
        }
        android.util.Log.w("SmbRecovery", "cached SMB session died; redialing share $name once")
        dropCachedSession()
        return block(ensureShare(name))
    }

    private fun isDeadTransport(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is TransportException) return true
            if (cause is SocketException || cause is EOFException) return true
            // smbj surfaces a dead cached share as SMBRuntimeException("... has already
            // been closed"), so the wording — not the type — is the reliable signal.
            if (cause.message?.contains("already been closed") == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun dropCachedSession() {
        synchronized(stateLock) {
            runCatching { share?.close() }
            share = null
            activeShareName = null
            runCatching { sessionHandle?.close() }
            sessionHandle = null
        }
    }

    /**
     * smbj's own fileExists/folderExists swallow OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND
     * and friends, but not STATUS_NO_SUCH_FILE — a status impacket-class servers answer for
     * a missing path. That status means "absent" everywhere, so it is folded into false here
     * instead of aborting rename guards and existence checks.
     */
    private fun DiskShare.existsInShare(path: String): Boolean = try {
        fileExists(path) || folderExists(path)
    } catch (error: SMBApiException) {
        if (error.statusCode == NtStatus.STATUS_NO_SUCH_FILE.value) false else throw error
    }

    /**
     * Directory detection via query-info, not a directory-flagged open: opening with
     * FILE_DIRECTORY_FILE is unreliable across servers (impacket happily "opens" a file
     * that way, and smbj then trips over its own File-to-Directory cast).
     */
    private fun DiskShare.isDirectoryInShare(path: String): Boolean = try {
        getFileInformation(path).standardInformation.isDirectory
    } catch (error: SMBApiException) {
        if (error.statusCode == NtStatus.STATUS_NO_SUCH_FILE.value) false else throw error
    }

    private fun discoverShares(): List<SmbDiscoveredShare> {
        discoveredShares?.let { return it }
        return synchronized(stateLock) {
            discoveredShares ?: ensureSessionLocked().discover(shareDiscovery).also { discoveredShares = it }
        }
    }

    private fun findDiscoveredShare(name: String): SmbDiscoveredShare? = discoverShares()
        .firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun resolve(path: String): ResolvedSharePath = SmbPathResolver.resolve(
        connection = connection,
        path = path,
        validateShare = { candidate ->
            findDiscoveredShare(candidate)?.name
                ?: throw IllegalArgumentException("SMB share is not available: $candidate")
        },
    )

    private fun resolveFile(path: String): ResolvedSharePath = SmbPathResolver.resolveFile(
        connection = connection,
        path = path,
        validateShare = { candidate ->
            findDiscoveredShare(candidate)?.name
                ?: throw IllegalArgumentException("SMB share is not available: $candidate")
        },
    )

    override suspend fun listFiles(path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (isDiscoveryMode && path == "/") return@runCatching virtualRootItems()
            val resolved = resolve(path)
            withLiveShare(resolved.shareName) { diskShare ->
                diskShare.list(resolved.relativePath)
                    .filter { it.fileName != "." && it.fileName != ".." }
                    .map { info ->
                        val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                        FileItem(
                            name = info.fileName,
                            path = appendPath(path, info.fileName),
                            isDirectory = isDirectory,
                            size = info.endOfFile,
                            lastModified = Date(info.lastWriteTime.toEpochMillis()),
                            isHidden = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L,
                            source = FileSource.SMB,
                        )
                    }
            }
        }
    }

    private fun virtualRootItems(): List<FileItem> {
        virtualRootItemsCache?.let { return it }
        return synchronized(stateLock) {
            virtualRootItemsCache ?: discoverShares()
                .map { discovered ->
                    FileItem(
                        name = discovered.name,
                        path = "/${discovered.name}",
                        isDirectory = true,
                        size = 0,
                        source = FileSource.SMB,
                    )
                }
                .also { virtualRootItemsCache = it }
        }
    }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = resolve(path)
            val fullPath = appendPath(path, name)
            withLiveShare(parent.shareName) { it.mkdir(joinSmbPath(parent.relativePath, name)) }
            FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.SMB)
        }
    }

    override suspend fun createFile(path: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = resolve(path)
            val fullPath = appendPath(path, name)
            withLiveShare(parent.shareName) { diskShare ->
                diskShare.openFile(
                    joinSmbPath(parent.relativePath, name),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_CREATE,
                    null,
                ).close()
            }
            FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.SMB)
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            withLiveShare(resolved.shareName) { diskShare ->
                if (diskShare.isDirectoryInShare(resolved.relativePath)) {
                    deleteDirectoryRecursive(diskShare, resolved.relativePath)
                } else {
                    diskShare.rm(resolved.relativePath)
                }
            }
        }
    }

    private fun deleteDirectoryRecursive(diskShare: DiskShare, smbPath: String) {
        for (info in diskShare.list(smbPath)) {
            if (info.fileName == "." || info.fileName == "..") continue
            val entryPath = joinSmbPath(smbPath, info.fileName)
            if (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L) {
                deleteDirectoryRecursive(diskShare, entryPath)
            } else {
                diskShare.rm(entryPath)
            }
        }
        diskShare.rmdir(smbPath, false)
    }

    private fun DiskShare.openForRename(path: String, isDirectory: Boolean): DiskEntry = if (isDirectory) {
        openDirectory(
            path,
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    } else {
        openFile(
            path,
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(oldPath)
            withLiveShare(resolved.shareName) { diskShare ->
                val parent = resolved.relativePath.substringBeforeLast("\\", "")
                val newSmb = joinSmbPath(parent, newName)
                if (RenameGuard.blocked(resolved.relativePath, newSmb,
                        diskShare.existsInShare(newSmb),
                        ignoreCase = true)
                ) {
                    throw IllegalStateException("An item named $newName already exists in this folder")
                }
                val wasDirectory = diskShare.isDirectoryInShare(resolved.relativePath)
                val entry = diskShare.openForRename(resolved.relativePath, wasDirectory)
                try {
                    entry.rename(newSmb)
                } finally {
                    entry.close()
                }
                val parentPath = oldPath.substringBeforeLast('/').ifEmpty { "/" }
                FileItem(
                    name = newName,
                    path = appendPath(parentPath, newName),
                    isDirectory = wasDirectory,
                    source = FileSource.SMB,
                )
            }
        }
    }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolveFile(sourcePath)
            val destination = resolve(destPath)
            requireSameShare(source, destination, "copy")
            val name = sourcePath.substringAfterLast('/')
            withLiveShare(source.shareName) { diskShare ->
                copyPath(
                    diskShare = diskShare,
                    sourceSmb = source.relativePath,
                    destSmb = joinSmbPath(destination.relativePath, name),
                )
            }
        }
    }

    private fun copyPath(diskShare: DiskShare, sourceSmb: String, destSmb: String) {
        if (diskShare.folderExists(sourceSmb)) {
            diskShare.mkdir(destSmb)
            for (info in diskShare.list(sourceSmb)) {
                if (info.fileName == "." || info.fileName == "..") continue
                copyPath(
                    diskShare,
                    joinSmbPath(sourceSmb, info.fileName),
                    joinSmbPath(destSmb, info.fileName),
                )
            }
            return
        }

        val sourceFile = diskShare.openFile(
            sourceSmb,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val destinationFile = diskShare.openFile(
            destSmb,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null,
        )
        try {
            sourceFile.remoteCopyTo(destinationFile)
        } finally {
            runCatching { sourceFile.close() }
            destinationFile.close()
        }
    }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolveFile(sourcePath)
            val destination = resolve(destPath)
            requireSameShare(source, destination, "move")
            withLiveShare(source.shareName) { diskShare ->
                val name = sourcePath.substringAfterLast('/')
                val destinationSmb = joinSmbPath(destination.relativePath, name)
                val isDirectory = diskShare.folderExists(source.relativePath)
                val entry = diskShare.openForRename(source.relativePath, isDirectory)
                try {
                    entry.rename(destinationSmb)
                } finally {
                    entry.close()
                }
            }
        }
    }

    private fun requireSameShare(source: ResolvedSharePath, destination: ResolvedSharePath, operation: String) {
        require(source.shareName.equals(destination.shareName, ignoreCase = true)) {
            "Cross-share SMB $operation is not supported"
        }
    }

    override suspend fun getInputStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            val owner = SmbTransferFile(transferSocketFactory())
            owner.open(sessionFactory, connection, resolved.shareName, resolved.relativePath, writing = false)
            try {
                object : FilterInputStream(owner.file.inputStream), TransferAbortable {
                    private var closed = false
                    override fun abortTransfer() = owner.abortTransfer()
                    override fun close() {
                        if (closed) return
                        closed = true
                        owner.finishStream { super.close() }
                    }
                } as InputStream
            } catch (error: Throwable) {
                owner.abortTransfer()
                runCatching { owner.close() }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    override suspend fun getOutputStream(path: String): Result<OutputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveFile(path)
            val owner = SmbTransferFile(transferSocketFactory())
            owner.open(sessionFactory, connection, resolved.shareName, resolved.relativePath, writing = true)
            try {
                object : ForwardingOutputStream(owner.file.outputStream), TransferAbortable {
                    private var closed = false
                    override fun abortTransfer() = owner.abortTransfer()
                    override fun close() {
                        if (closed) return
                        closed = true
                        owner.finishStream { super.close() }
                    }
                } as OutputStream
            } catch (error: Throwable) {
                owner.abortTransfer()
                runCatching { owner.close() }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isDiscoveryMode && path == "/") return@withContext true
            if (isDiscoveryMode) {
                val parsed = SmbBrowsePath.parse(path)
                if (parsed is SmbBrowsePath.Share && parsed.relativePath.isEmpty()) {
                    return@withContext findDiscoveredShare(parsed.name) != null
                }
            }
            val resolved = resolve(path)
            withLiveShare(resolved.shareName) { diskShare ->
                diskShare.existsInShare(resolved.relativePath)
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun getFileInfo(path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            virtualFileInfo(path)?.let { return@runCatching it }
            val resolved = resolve(path)
            val info = withLiveShare(resolved.shareName) { it.getFileInformation(resolved.relativePath) }
            FileItem(
                name = path.substringAfterLast('/'),
                path = path,
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                lastModified = Date(info.basicInformation.lastWriteTime.toEpochMillis()),
                source = FileSource.SMB,
            )
        }
    }

    private fun virtualFileInfo(path: String): FileItem? {
        if (!isDiscoveryMode) return null
        if (path == "/") {
            return FileItem(
                name = connection.host,
                path = "/",
                isDirectory = true,
                source = FileSource.SMB,
            )
        }
        val parsed = SmbBrowsePath.parse(path)
        if (parsed !is SmbBrowsePath.Share || parsed.relativePath.isNotEmpty()) return null
        val discovered = findDiscoveredShare(parsed.name)
            ?: throw IllegalArgumentException("SMB share is not available: ${parsed.name}")
        return FileItem(
            name = discovered.name,
            path = "/${discovered.name}",
            isDirectory = true,
            source = FileSource.SMB,
        )
    }

    override fun getParentPath(path: String): String? = if (isDiscoveryMode) {
        SmbBrowsePath.parentOf(path)
    } else {
        if (path == "/" || path.isEmpty()) null else path.substringBeforeLast('/').ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            // Detach under the lock so a concurrent ensureShare() cannot install a share
            // that this call would then close, and close the detached handles outside it.
            val currentShare: DiskShare?
            val currentSession: SmbSessionHandle?
            synchronized(stateLock) {
                currentShare = share
                currentSession = sessionHandle
                share = null
                activeShareName = null
                sessionHandle = null
                discoveredShares = null
                virtualRootItemsCache = null
            }
            runCatching { currentShare?.close() }
            runCatching { currentSession?.close() }
        }
    }

    private fun appendPath(parent: String, name: String): String = if (parent == "/") {
        "/$name"
    } else {
        "${parent.trimEnd('/')}/$name"
    }

    private fun joinSmbPath(parent: String, name: String): String = if (parent.isEmpty()) {
        name
    } else {
        "$parent\\$name"
    }
}
