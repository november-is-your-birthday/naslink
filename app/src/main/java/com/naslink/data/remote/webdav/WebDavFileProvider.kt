package com.naslink.data.remote.webdav

import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import com.naslink.data.model.RemoteConnection
import com.naslink.data.repository.FileProvider
import com.naslink.data.repository.RenameGuard
import com.naslink.data.repository.TransferCancellation
import com.naslink.data.repository.TransferAbortable
import com.naslink.data.repository.ForwardingOutputStream
import com.naslink.data.repository.StreamTransfer
import com.naslink.data.repository.StreamTransferProgress
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class WebDavFileProvider(
    private val connection: RemoteConnection,
    private val temporaryDirectory: File,
) : FileProvider {
    override fun isSameStorage(other: FileProvider): Boolean = other is WebDavFileProvider &&
        connection.host.equals(other.connection.host, ignoreCase = true) && connection.port == other.connection.port &&
        connection.username == other.connection.username && connection.shareName == other.connection.shareName

    /**
     * Guards the client/sardine handshake in [ensureConnected].
     *
     * The old check-then-act let two coroutines both see a null client and both build one.
     * The loser's client kept a dispatcher thread pool and a connection pool alive that
     * nothing would ever shut down — so the second dial silently became a permanent leak.
     * `SmbFileProvider` and `SftpFileProvider` serialise the equivalent state already.
     */
    private val connectLock = Any()

    /** Volatile so a concurrent [disconnect] cannot be missed by a later read. */
    @Volatile
    private var sardine: OkHttpSardine? = null

    @Volatile
    private var httpClient: OkHttpClient? = null

    private fun baseUrl(): String {
        return webDavBaseUrl(connection)
    }

    internal fun toUrl(path: String): String {
        val builder = baseUrl().toHttpUrl().newBuilder()
        val cleanPath = path.trimStart('/')
        if (cleanPath.isEmpty()) {
            builder.addPathSegment("")
        } else {
            cleanPath.split('/').forEach(builder::addPathSegment)
        }
        return builder.build().toString()
    }

    internal suspend fun createPlaybackSource(path: String): Result<PreparedWebDavPlayback> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient()
                val source = WebDavRangeSource(
                    client = client,
                    url = toUrl(path).toHttpUrl(),
                    authorization = connection.username.takeIf(String::isNotEmpty)?.let {
                        Credentials.basic(connection.username, connection.password)
                    },
                )
                val metadata = source.probe().getOrElse { error ->
                    source.close()
                    throw error
                }
                PreparedWebDavPlayback(source, metadata)
            }
        }

    /**
     * Returns a ready-to-use Sardine facade, creating the client only once.
     *
     * The instance is returned rather than re-read from the field so callers use exactly the
     * client that was validated. A concurrent [disconnect] may still tear it down underneath
     * them, which surfaces as an IOException from the request rather than as the
     * NullPointerException that dereferencing a field another coroutine just nulled would
     * produce.
     */
    private fun ensureConnected(): OkHttpSardine = synchronized(connectLock) {
        sardine?.let { return@synchronized it }
        val client = OkHttpClient.Builder()
            .apply {
                if (connection.username.isNotEmpty()) {
                    authenticator { _, response ->
                        val credential = Credentials.basic(connection.username, connection.password)
                        if (response.request.header("Authorization") == credential) {
                            null
                        } else {
                            response.request.newBuilder()
                                .header("Authorization", credential)
                                .build()
                        }
                    }
                }
            }
            .build()
        httpClient = client
        OkHttpSardine(client).also { sardine = it }
    }

    /**
     * The HTTP client behind the current Sardine facade, for the few call sites that need to
     * issue a raw call rather than go through Sardine.
     */
    private fun client(): OkHttpClient {
        ensureConnected()
        return httpClient
            ?: throw IOException("WebDAV client was disconnected during the request")
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = toUrl(path)
                ensureConnected().list(url).drop(1).map { resource -> // drop(1) to skip self
                    val name = resource.name.removeSuffix("/")
                    val filePath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    FileItem(
                        name = name,
                        path = filePath,
                        isDirectory = resource.isDirectoryResource(),
                        size = resource.contentLength,
                        lastModified = resource.modified ?: Date(),
                        isHidden = name.startsWith("."),
                        source = FileSource.WEBDAV,
                    )
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                ensureConnected().createDirectory(toUrl(fullPath))
                FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.WEBDAV)
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                ensureConnected().put(toUrl(fullPath), ByteArray(0))
                FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.WEBDAV)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected().delete(toUrl(path))
            }
        }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                if (RenameGuard.blocked(oldPath, newPath, exists(newPath), ignoreCase = false)) {
                    throw IllegalStateException("An item named $newName already exists in this folder")
                }
                ensureConnected().move(toUrl(oldPath), toUrl(newPath))
                FileItem(name = newName, path = newPath, isDirectory = false, source = FileSource.WEBDAV)
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                ensureConnected().copy(toUrl(sourcePath), toUrl("$destPath/$name"))
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                ensureConnected().move(toUrl(sourcePath), toUrl("$destPath/$name"))
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected().get(toUrl(path))
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = client()
                check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
                    "Could not prepare temporary storage for the upload"
                }
                val temporaryFile = File.createTempFile(
                    "naslink-webdav-",
                    ".upload",
                    temporaryDirectory,
                )
                object : ForwardingOutputStream(FileOutputStream(temporaryFile)), TransferAbortable {
                    private val closed = AtomicBoolean(false)
                    private val aborted = AtomicBoolean(false)
                    @Volatile
                    private var upload: Call? = null
                    private val abort = TransferCancellation.registerAbort(::abortTransfer)

                    override fun write(value: Int) {
                        checkCancellation()
                        super.write(value)
                    }

                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        checkCancellation()
                        super.write(buffer, offset, length)
                    }

                    override fun abortTransfer() {
                        aborted.set(true)
                        upload?.cancel()
                        runCatching { out.close() }
                        temporaryFile.delete()
                    }

                    private fun checkCancellation() {
                        TransferCancellation.check()
                        if (aborted.get()) throw CancellationException("Transfer cancelled")
                    }

                    override fun close() {
                        if (!closed.compareAndSet(false, true)) {
                            checkCancellation()
                            return
                        }
                        try {
                            super.close()
                            checkCancellation()
                            val request = Request.Builder()
                                .url(toUrl(path))
                                .put(temporaryFile.asRequestBody("application/octet-stream".toMediaType()))
                                .build()
                            val call = client.newCall(request)
                            upload = call
                            checkCancellation()
                            call.execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw IOException("WebDAV upload failed: HTTP ${response.code}")
                                }
                            }
                            checkCancellation()
                        } catch (error: Throwable) {
                            checkCancellation()
                            throw error
                        } finally {
                            upload = null
                            abort.close()
                            temporaryFile.delete()
                        }
                    }
                } as OutputStream
            }
        }

    override suspend fun writeStream(
        path: String,
        input: InputStream,
        sourcePath: String,
        totalBytes: Long?,
        onProgress: (StreamTransferProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected()
            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength(): Long = totalBytes ?: -1

                override fun isOneShot(): Boolean = true

                override fun writeTo(sink: BufferedSink) {
                    StreamTransfer.copy(
                        input = input,
                        output = sink.outputStream(),
                        path = sourcePath,
                        totalBytes = totalBytes,
                        onProgress = onProgress,
                    )
                }
            }
            val request = Request.Builder()
                .url(toUrl(path))
                .apply {
                    if (connection.username.isNotEmpty()) {
                        header(
                            "Authorization",
                            Credentials.basic(connection.username, connection.password),
                        )
                    }
                }
                .put(requestBody)
                .build()
            val call = client().newCall(request)
            TransferCancellation.registerAbort(call::cancel).use {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("WebDAV upload failed: HTTP ${response.code}")
                    }
                }
            }
        }
    }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected().exists(toUrl(path))
            } catch (cancelled: CancellationException) {
                // Cancellation is not an answer to "does this exist"; swallowing it keeps a
                // cancelled coroutine running and reports `false` for an unchecked path.
                throw cancelled
            } catch (_: IOException) {
                // A transport failure means "cannot tell", which this signature can only
                // express as false.
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resources = ensureConnected().list(toUrl(path))
                val resource = resources.firstOrNull()
                    ?: throw IllegalArgumentException("Not found: $path")
                FileItem(
                    name = resource.name.removeSuffix("/"),
                    path = path,
                    isDirectory = resource.isDirectoryResource(),
                    size = resource.contentLength,
                    lastModified = resource.modified ?: Date(),
                    source = FileSource.WEBDAV,
                )
            }
        }

    override fun getParentPath(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val parent = path.removeSuffix("/").substringBeforeLast("/")
        return parent.ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        httpClient?.dispatcher?.cancelAll()
        httpClient?.connectionPool?.evictAll()
        httpClient = null
        sardine = null
    }

    private fun DavResource.isDirectoryResource(): Boolean =
        isDirectory || href?.path?.endsWith("/") == true
}

internal data class PreparedWebDavPlayback(
    val source: WebDavRangeSource,
    val metadata: WebDavRangeMetadata,
)

internal fun webDavBaseUrl(connection: RemoteConnection): String {
    val scheme = if (connection.useTls) "https" else "http"
    val defaultPort = if (connection.useTls) 443 else 80
    val portPart = if (connection.port == defaultPort) "" else ":${connection.port}"
    return "$scheme://${connection.host}$portPart"
}
