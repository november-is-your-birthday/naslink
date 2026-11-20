package com.naslink.data.repository

import android.content.Context
import android.net.Uri
import com.naslink.data.model.ConnectionProtocol
import com.naslink.data.model.RemoteConnection
import com.naslink.data.remote.ftp.FtpFileProvider
import com.naslink.data.remote.saf.SafFileProvider
import com.naslink.data.remote.sftp.SftpFileProvider
import com.naslink.data.remote.smb.SmbFileProvider
import com.naslink.data.remote.webdav.WebDavFileProvider
import java.io.File

object FileProviderFactory {
    fun createLocal(): FileProvider = LocalFileProvider()

    fun createSaf(context: Context, treeUri: Uri): FileProvider =
        SafFileProvider(context.applicationContext, treeUri)

    fun createRemote(context: Context, connection: RemoteConnection): FileProvider = when (connection.protocol) {
        ConnectionProtocol.SFTP -> SftpFileProvider(
            connection = connection,
            knownHostsFile = File(context.applicationContext.filesDir, "ssh/known_hosts"),
        )
        ConnectionProtocol.FTP -> FtpFileProvider(connection, context.applicationContext.cacheDir)
        ConnectionProtocol.SMB -> SmbFileProvider(connection)
        ConnectionProtocol.WEBDAV -> WebDavFileProvider(connection, context.applicationContext.cacheDir)
    }
}
