package com.naslink.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The EXIF preview extractor is the difference between "read 30 MB" and "read 256 KB" for
 * large photos, so it has to be right on the files cameras actually produce and harmless on
 * everything else.
 *
 * The fixtures are built by hand rather than shipped as binaries: a JPEG header is a
 * length-prefixed segment list, which makes it easy to state exactly which layout a test
 * covers (IFD0 vs IFD1, little vs big endian), and it keeps the failure mode legible when
 * one of them breaks.
 */
class JpegExifThumbnailTest {

    @Test
    fun `reads the preview from IFD1 in a little-endian file`() {
        val thumbnail = jpegStub(0xA1)
        val file = jpegWithExif(thumbnail = thumbnail, littleEndian = true)
        assertArrayEquals(thumbnail, JpegExifThumbnail.extract(file))
    }

    @Test
    fun `reads the preview from IFD1 in a big-endian file`() {
        // Canon and some phone cameras write "MM" instead of "II"; reading only one byte
        // order would silently disable the fast path for half the world.
        val thumbnail = jpegStub(0xB2)
        val file = jpegWithExif(thumbnail = thumbnail, littleEndian = false)
        assertArrayEquals(thumbnail, JpegExifThumbnail.extract(file))
    }

    @Test
    fun `reads a preview some writers put in IFD0`() {
        val thumbnail = jpegStub(0xC3)
        val file = jpegWithExif(thumbnail = thumbnail, previewInFirstDirectory = true)
        assertArrayEquals(thumbnail, JpegExifThumbnail.extract(file))
    }

    @Test
    fun `skips a non-EXIF APP1 segment before the EXIF one`() {
        // XMP metadata is also APP1. Stopping at the first APP1 would miss the preview in
        // every photo edited by a tool that writes XMP first.
        val thumbnail = jpegStub(0xD4)
        val file = jpegWithExif(
            thumbnail = thumbnail,
            leadingXmp = true,
        )
        assertArrayEquals(thumbnail, JpegExifThumbnail.extract(file))
    }

    @Test
    fun `returns null for a JPEG with no metadata`() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertNull(JpegExifThumbnail.extract(bare))
    }

    @Test
    fun `returns null for formats that are not JPEG`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertNull(JpegExifThumbnail.extract(png))
        assertNull(JpegExifThumbnail.extract(ByteArray(0)))
        assertNull(JpegExifThumbnail.extract(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `an EXIF block without preview offsets is a miss`() {
        val file = jpegWithExif(thumbnail = jpegStub(1), omitPreviewTags = true)
        assertNull(JpegExifThumbnail.extract(file))
    }

    @Test
    fun `a preview offset pointing outside the segment is rejected`() {
        // Corrupt or hostile metadata must not hand back bytes from the middle of the
        // image data, which would decode as garbage or fail deep inside BitmapFactory.
        val file = jpegWithExif(thumbnail = jpegStub(2), previewOffsetOverride = 0x7FFF)
        assertNull(JpegExifThumbnail.extract(file))
    }

    @Test
    fun `a declared segment length past the buffer is a miss rather than a crash`() {
        val valid = jpegWithExif(thumbnail = jpegStub(3))
        // Cut inside the APP1 payload, so its declared length overruns the array.
        assertNull(JpegExifThumbnail.extract(valid.copyOf(20)))
    }

    @Test
    fun `no truncation of a valid file can throw`() {
        // The caller hands this whatever a 256 KB prefix of a file happened to contain,
        // which is frequently a half-read segment. Every prefix is fair game.
        val valid = jpegWithExif(thumbnail = jpegStub(4), leadingXmp = true)
        for (length in 0..valid.size) {
            JpegExifThumbnail.extract(valid.copyOf(length))
        }
    }

    @Test
    fun `random bytes are not mistaken for a preview`() {
        val noise = ByteArray(512) { index -> ((index * 37) and 0xFF).toByte() }
        assertNull(JpegExifThumbnail.extract(noise))
    }

    // ---- fixtures -------------------------------------------------------------------

    private fun jpegStub(seed: Int): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE0.toByte(),
        0x00,
        0x04,
        seed.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    private fun jpegWithExif(
        thumbnail: ByteArray,
        littleEndian: Boolean = true,
        previewInFirstDirectory: Boolean = false,
        leadingXmp: Boolean = false,
        omitPreviewTags: Boolean = false,
        previewOffsetOverride: Int? = null,
    ): ByteArray {
        val tiff = tiffBlock(
            littleEndian = littleEndian,
            previewInFirstDirectory = previewInFirstDirectory,
            omitPreviewTags = omitPreviewTags,
            previewOffsetOverride = previewOffsetOverride,
            thumbnail = thumbnail,
        )
        return ByteArrayOutputStream().apply {
            write(0xFF); write(0xD8)
            if (leadingXmp) {
                val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray(Charsets.US_ASCII)
                write(0xFF); write(0xE1)
                writeU16(2 + xmp.size, littleEndian = false)
                write(xmp)
            }
            write(0xFF); write(0xE1)
            writeU16(2 + 6 + tiff.size, littleEndian = false)
            write("Exif".toByteArray(Charsets.US_ASCII))
            write(0); write(0)
            write(tiff)
            write(0xFF); write(0xDA)
            writeU16(4, littleEndian = false)
            write(0); write(0)
            write(thumbnail)
            write(0xFF); write(0xD9)
        }.toByteArray()
    }

    /**
     * Builds the TIFF block inside APP1.
     *
     * Layout with a second directory: header (8) → IFD0 (18) → IFD1 (30) → thumbnail.
     * Layout with the preview in IFD0: header (8) → IFD0 (30) → thumbnail.
     */
    private fun tiffBlock(
        littleEndian: Boolean,
        previewInFirstDirectory: Boolean,
        omitPreviewTags: Boolean,
        previewOffsetOverride: Int?,
        thumbnail: ByteArray,
    ): ByteArray {
        val ifd0Offset = 8
        val ifd0Count = if (previewInFirstDirectory) 2 else 1
        val ifd0Size = 2 + ifd0Count * 12 + 4
        val ifd1Offset = ifd0Offset + ifd0Size
        val ifd1Count = 2
        val ifd1Size = 2 + ifd1Count * 12 + 4
        val previewOffset = if (previewInFirstDirectory) ifd1Offset else ifd1Offset + ifd1Size
        val writtenPreviewOffset = previewOffsetOverride ?: previewOffset

        return ByteArrayOutputStream().apply {
            if (littleEndian) {
                write('I'.code); write('I'.code)
            } else {
                write('M'.code); write('M'.code)
            }
            writeU16(42, littleEndian)
            writeU32(ifd0Offset, littleEndian)

            // IFD0: one harmless tag, or the preview tags themselves.
            writeU16(ifd0Count, littleEndian)
            if (previewInFirstDirectory) {
                writePreviewEntry(0x0201, writtenPreviewOffset, littleEndian)
                writePreviewEntry(0x0202, thumbnail.size, littleEndian)
                writeU32(0, littleEndian)
            } else {
                writeEntry(tag = 0x010F, type = 2, count = 1, value = 0, littleEndian = littleEndian)
                writeU32(ifd1Offset, littleEndian)

                writeU16(ifd1Count, littleEndian)
                if (omitPreviewTags) {
                    writeEntry(0x010F, 2, 1, 0, littleEndian)
                    writeEntry(0x0110, 2, 1, 0, littleEndian)
                } else {
                    writePreviewEntry(0x0201, writtenPreviewOffset, littleEndian)
                    writePreviewEntry(0x0202, thumbnail.size, littleEndian)
                }
                writeU32(0, littleEndian)
            }
            write(thumbnail)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writePreviewEntry(tag: Int, value: Int, littleEndian: Boolean) =
        writeEntry(tag = tag, type = 4, count = 1, value = value, littleEndian = littleEndian)

    private fun ByteArrayOutputStream.writeEntry(
        tag: Int,
        type: Int,
        count: Int,
        value: Int,
        littleEndian: Boolean,
    ) {
        writeU16(tag, littleEndian)
        writeU16(type, littleEndian)
        writeU32(count, littleEndian)
        writeU32(value, littleEndian)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int, littleEndian: Boolean) {
        val high = (value shr 8) and 0xFF
        val low = value and 0xFF
        if (littleEndian) {
            write(low); write(high)
        } else {
            write(high); write(low)
        }
    }

    private fun ByteArrayOutputStream.writeU32(value: Int, littleEndian: Boolean) {
        val bytes = intArrayOf(
            (value shr 24) and 0xFF,
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF,
        )
        if (littleEndian) bytes.reversed().forEach { write(it) } else bytes.forEach { write(it) }
    }
}
