package com.naslink.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sidecar subtitle attachment: every false positive means a wrong subtitle shows over
 * the film; every false negative means the subtitle the user placed next to the video
 * silently never loads.
 */
class SubtitleSidecarTest {

    @Test
    fun exactBaseNameMatchesAcrossVideoExtensions() {
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.srt"))
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.ass"))
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.ssa"))
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.vtt"))
        assertTrue(SubtitleSidecar.matches("SHOW.S01E01.mp4", "show.s01e01.srt"))
    }

    @Test
    fun languageTagsExtendAfterADotBoundary() {
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.zh.srt"))
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.zh.rCN.ass"))
        assertTrue(SubtitleSidecar.matches("movie.mkv", "movie.forced.srt"))
    }

    @Test
    fun similarlyNamedFilesDoNotAttach() {
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movie2.srt"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "making.of.srt"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "other.movie.srt"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movies.srt"))
    }

    @Test
    fun nonSubtitlesAndSelfReferencesAreRejected() {
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movie.nfo"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movie.mkv"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movie.jpg"))
    }

    @Test
    fun degenerateNamesNeverMatch() {
        assertFalse(SubtitleSidecar.matches(".mkv", ".srt"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", ".srt"))
        assertFalse(SubtitleSidecar.matches("movie.mkv", "movie."))
    }

    @Test
    fun mimeTypesCoverEverySupportedExtension() {
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP, SubtitleSidecar.mimeTypeFor("a.srt"))
        assertEquals(androidx.media3.common.MimeTypes.TEXT_SSA, SubtitleSidecar.mimeTypeFor("a.ass"))
        assertEquals(androidx.media3.common.MimeTypes.TEXT_SSA, SubtitleSidecar.mimeTypeFor("a.ssa"))
        assertEquals(androidx.media3.common.MimeTypes.TEXT_VTT, SubtitleSidecar.mimeTypeFor("a.vtt"))
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP, SubtitleSidecar.mimeTypeFor("a.SRT"))
    }
}
