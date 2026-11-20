package com.naslink.ui.screens

import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource

internal enum class RemoteFileTapAction {
    NAVIGATE,
    STREAM_WEBDAV,
    CONFIRM_DOWNLOAD,
    DOWNLOAD,
}

internal fun remoteFileTapAction(file: FileItem, confirmDownloads: Boolean = true): RemoteFileTapAction = when {
    file.isDirectory -> RemoteFileTapAction.NAVIGATE
    file.source == FileSource.WEBDAV -> RemoteFileTapAction.STREAM_WEBDAV
    confirmDownloads -> RemoteFileTapAction.CONFIRM_DOWNLOAD
    else -> RemoteFileTapAction.DOWNLOAD
}
