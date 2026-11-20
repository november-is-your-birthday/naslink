package com.naslink.playback

interface PlaybackRandomAccessSource : AutoCloseable {
    fun read(offset: Long, byteCount: Int): Result<ByteArray>
}
