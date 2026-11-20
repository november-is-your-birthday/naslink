package com.naslink.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that keeps a rename typo from destroying a file: an existing target is
 * refused unless it is the very same entry under a different case spelling.
 */
class RenameGuardTest {

    @Test
    fun freeTargetIsNeverBlocked() {
        assertFalse(RenameGuard.blocked("/a/old.txt", "/a/new.txt", targetExists = false, ignoreCase = false))
        assertFalse(RenameGuard.blocked("/a/old.txt", "/a/new.txt", targetExists = false, ignoreCase = true))
    }

    @Test
    fun existingTargetIsBlocked() {
        assertTrue(RenameGuard.blocked("/a/old.txt", "/a/new.txt", targetExists = true, ignoreCase = false))
        assertTrue(RenameGuard.blocked("/a/old.txt", "/a/new.txt", targetExists = true, ignoreCase = true))
    }

    @Test
    fun caseOnlyRenamePassesOnCaseInsensitiveServers() {
        assertFalse(RenameGuard.blocked("/a/report.txt", "/a/REPORT.txt", targetExists = true, ignoreCase = true))
    }

    @Test
    fun caseOnlyRenameIsStillCheckedOnCaseSensitiveServers() {
        // On a case-sensitive server "REPORT.txt" may be a different file; renaming over
        // it must stay guarded even though only the case changed.
        assertTrue(RenameGuard.blocked("/a/report.txt", "/a/REPORT.txt", targetExists = true, ignoreCase = false))
    }

    @Test
    fun identicalTargetIsNeverBlocked() {
        assertFalse(RenameGuard.blocked("/a/same.txt", "/a/same.txt", targetExists = true, ignoreCase = false))
        assertFalse(RenameGuard.blocked("/a/same.txt", "/a/same.txt", targetExists = true, ignoreCase = true))
    }
}
