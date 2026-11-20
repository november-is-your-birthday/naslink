package com.naslink.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Template semantics for the batch rename dialog: wrong numbering or a swallowed
 * extension would rename real user files into garbage, so every case here is a shape
 * the dialog can produce.
 */
class BatchRenameTest {

    @Test
    fun numberSlotIsReplacedWithPaddedIndex() {
        assertEquals("holiday_01.jpg", BatchRename.newName("holiday_##", "IMG_0001.jpg", false, 1, 2))
        assertEquals("holiday_10.jpg", BatchRename.newName("holiday_##", "IMG_0002.jpg", false, 10, 2))
    }

    @Test
    fun paddingGrowsWithSelectionSize() {
        assertEquals("trip_1.png", BatchRename.newName("trip_##", "a.png", false, 1, 1))
        assertEquals("trip_001.png", BatchRename.newName("trip_##", "a.png", false, 1, 3))
    }

    @Test
    fun extensionIsKeptWhenTemplateHasNone() {
        assertEquals("vacation_01.srt", BatchRename.newName("vacation_##", "movie.srt", false, 1, 2))
    }

    @Test
    fun templateExtensionWins() {
        assertEquals("vacation_01.sub", BatchRename.newName("vacation_##.sub", "movie.srt", false, 1, 2))
    }

    @Test
    fun directoriesNeverGrowAnExtension() {
        assertEquals("season_01", BatchRename.newName("season_##", "Season 1", true, 1, 2))
    }

    @Test
    fun dotfilesHaveNoExtensionToPreserve() {
        assertEquals("dot_01", BatchRename.newName("dot_##", ".hidden", false, 1, 2))
    }

    @Test
    fun extensionlessOriginalsStayClean() {
        assertEquals("readme_01", BatchRename.newName("readme_##", "README", false, 1, 2))
    }

    @Test
    fun longerHashRunsAreOneNumberSlot() {
        assertEquals("n_7.png", BatchRename.newName("n_#####", "a.png", false, 7, 1))
    }

    @Test
    fun withoutNumberSlotTheTemplateIsUsedWithKeptExtension() {
        assertEquals("plain.txt", BatchRename.newName("plain", "anything.txt", false, 3, 2))
    }

    @Test
    fun multiSelectRequiresANumberSlot() {
        assertFalse(BatchRename.needsNumberSlot("holiday_##"))
        assertFalse(BatchRename.needsNumberSlot("holiday_####"))
        assertTrue(BatchRename.needsNumberSlot("holiday_"))
        assertTrue(BatchRename.needsNumberSlot("holiday_#")) // a single # is literal, not a slot
    }
}
