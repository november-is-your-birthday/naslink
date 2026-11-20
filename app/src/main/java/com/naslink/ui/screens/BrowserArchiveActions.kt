package com.naslink.ui.screens

import com.naslink.R
import com.naslink.data.archive.ArchiveFormat
import com.naslink.data.model.FileItem
import com.naslink.ui.text.UiText
import com.naslink.util.FileNameValidationResult
import com.naslink.util.FileNameValidator

enum class BrowserArchiveAction {
    COMPRESS_TO_ZIP,
    EXTRACT_HERE,
    EXTRACTION_UNSUPPORTED,
}

enum class ArchiveTapAction {
    CONFIRM_EXTRACTION,
    SHOW_UNSUPPORTED,
    OPEN_EXTERNALLY,
}

object BrowserArchiveActions {

    fun tapAction(item: FileItem): ArchiveTapAction = when {
        ArchiveFormat.detect(item.name)?.canExtract == true ->
            ArchiveTapAction.CONFIRM_EXTRACTION
        item.isArchive -> ArchiveTapAction.SHOW_UNSUPPORTED
        else -> ArchiveTapAction.OPEN_EXTERNALLY
    }

    fun forSelection(items: List<FileItem>): Set<BrowserArchiveAction> = buildSet {
        if (items.isEmpty()) return@buildSet
        add(BrowserArchiveAction.COMPRESS_TO_ZIP)
        if (items.size != 1) return@buildSet

        val item = items.single()
        val format = ArchiveFormat.detect(item.name)
        when {
            format?.canExtract == true -> add(BrowserArchiveAction.EXTRACT_HERE)
            format == ArchiveFormat.RAR_UNSUPPORTED || item.isArchive -> {
                add(BrowserArchiveAction.EXTRACTION_UNSUPPORTED)
            }
        }
    }

    fun unsupportedExtractionReason(items: List<FileItem>): UiText? {
        if (items.size != 1) return null
        val item = items.single()
        val format = ArchiveFormat.detect(item.name)
        return when {
            format == ArchiveFormat.RAR_UNSUPPORTED ->
                UiText.Resource(R.string.browser_rar_extraction_unavailable)
            format == null && item.isArchive ->
                UiText.Resource(
                    R.string.browser_format_extraction_unavailable,
                    listOf(UiText.Dynamic(item.extension.uppercase())),
                )
            else -> null
        }
    }

    fun defaultZipName(
        selectedItems: List<FileItem>,
        existingNames: Set<String>,
        fallbackBaseName: String,
    ): String {
        require(selectedItems.isNotEmpty()) { "Select at least one item to compress" }
        val proposedBase = if (selectedItems.size == 1) {
            selectedItems.single().let { item ->
                if (item.isDirectory) {
                    item.name
                } else {
                    item.name.substringBeforeLast('.', item.name)
                }
            }
        } else {
            fallbackBaseName
        }
        val base = if ('\\' in proposedBase) {
            fallbackBaseName
        } else {
            when (val validation = FileNameValidator.validate(proposedBase)) {
                is FileNameValidationResult.Valid -> validation.name
                is FileNameValidationResult.Invalid -> fallbackBaseName
            }.ifBlank { fallbackBaseName }
        }
        val normalizedExistingNames = existingNames.mapTo(mutableSetOf()) { it.lowercase() }

        var candidate = "$base.zip"
        var suffix = 2
        while (candidate.lowercase() in normalizedExistingNames) {
            candidate = "$base ($suffix).zip"
            suffix++
        }
        return candidate
    }
}
