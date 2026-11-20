package com.naslink.ui.components

import com.naslink.R
import com.naslink.util.FileNameValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNameValidationTest {

    @Test
    fun acceptsAndTrimsZipNames() {
        assertEquals(
            FileNameValidationResult.Valid("backup.ZIP"),
            validateZipArchiveName(" backup.ZIP "),
        )
    }

    @Test
    fun rejectsMissingZipSuffixAndProviderSeparators() {
        assertEquals(
            FileNameValidationResult.Invalid(R.string.name_must_end_zip),
            validateZipArchiveName("backup.tar"),
        )
        val separatorResult = validateZipArchiveName("folder\\backup.zip")
        assertTrue(separatorResult is FileNameValidationResult.Invalid)
        assertEquals(
            R.string.name_no_backslashes,
            (separatorResult as FileNameValidationResult.Invalid).messageRes,
        )
    }
}
