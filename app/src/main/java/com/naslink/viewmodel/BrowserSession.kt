package com.naslink.viewmodel

import com.naslink.data.model.FileSource

data class BrowserSession(
    val id: String,
    val title: String,
    val source: FileSource,
    val rootPath: String,
    val currentPath: String,
    val connectionId: Long? = null,
    val host: String? = null,
) {
    val description: String
        get() {
            if (source == FileSource.LOCAL || source == FileSource.SAF) return currentPath
            val hostLabel = host?.takeIf { it.isNotBlank() }
            return listOfNotNull(source.name, hostLabel, currentPath).joinToString(" - ")
        }
}
