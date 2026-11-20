package com.naslink.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Encodes and decodes the bytes kept in [ThumbnailDiskCache].
 *
 * WebP at quality 80 is the whole point of the cache: a 512 px preview lands around
 * 25–40 KB, where the original JPEG for the same cell was several megabytes. Lossy is
 * fine at preview size — the full-screen viewer still reads the original.
 */
internal object ThumbnailCodec {

    private const val QUALITY = 80

    fun encode(bitmap: Bitmap): ByteArray? = runCatching {
        val output = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = when {
            // Lossy WebP has no alpha plane on Android, and a transparent PNG encoded that
            // way turns its transparent pixels black — visible as a black tile in a light
            // themed grid. Lossless keeps the alpha; the extra kilobytes only apply to the
            // rare screenshot/logo that has any.
            bitmap.hasAlpha() -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        if (!bitmap.compress(format, QUALITY, output)) return null
        output.toByteArray()
    }.getOrNull()

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
