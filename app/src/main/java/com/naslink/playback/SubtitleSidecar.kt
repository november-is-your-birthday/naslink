package com.naslink.playback

import androidx.media3.common.MimeTypes

/**
 * Sidecar subtitle files that sit next to a streamed video on the NAS.
 *
 * Pure logic on file names so the browser can pick the tracks before publishing them:
 * `movie.srt` and `movie.zh.srt` belong to `movie.mkv`, while `making.of.srt` does not.
 */
object SubtitleSidecar {

    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt")

    fun isSubtitle(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in subtitleExtensions

    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /**
     * Whether [subtitleName] belongs to [videoName]: the subtitle's base name must equal
     * the video's base name, or extend it after a dot boundary — `movie.zh.srt` attaches
     * to `movie.mkv`, but `movie2.srt` must not.
     */
    fun matches(videoName: String, subtitleName: String): Boolean {
        if (!isSubtitle(subtitleName)) return false
        val videoBase = videoName.substringBeforeLast('.', "").lowercase()
        val subtitleBase = subtitleName.substringBeforeLast('.', "").lowercase()
        if (videoBase.isEmpty() || subtitleBase.isEmpty()) return false
        if (!subtitleBase.startsWith(videoBase)) return false
        val remainder = subtitleBase.removePrefix(videoBase)
        return remainder.isEmpty() || remainder.startsWith('.')
    }
}
