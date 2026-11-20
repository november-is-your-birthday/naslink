package com.naslink.playback.player

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * Which of the two vertical gestures a drag drives.
 *
 * The split follows the convention every mobile player uses: the left half of the picture
 * changes brightness, the right half changes volume. It is decided once, from where the
 * finger went down, so a drag that wanders across the middle keeps doing what it started.
 */
internal enum class VerticalGesture { BRIGHTNESS, VOLUME }

/** Sign of a double-tap seek, so the HUD can draw the arrow on the correct side. */
internal enum class SeekDirection { BACKWARD, FORWARD }

/**
 * Arithmetic behind the gestures, kept free of Compose and Android so it can be tested
 * on the JVM. Everything here is a pure function of its arguments.
 */
internal object PlayerGestureMath {

    /**
     * How many milliseconds of seek one dp of horizontal drag is worth.
     *
     * 12 dp per second means a comfortable 300 dp swipe across a phone lands around 25
     * seconds — enough to skip an intro without overshooting a scene. VLC's "precise"
     * mode uses a similar ratio; a duration-relative mapping would make a two-hour film
     * jump minutes per millimetre.
     */
    const val MILLIS_PER_DP = 1000f / 12f

    /** Milliseconds of seek for a horizontal drag of [dragDp]. */
    fun seekDeltaMs(dragDp: Float): Long = (dragDp * MILLIS_PER_DP).toLong()

    /**
     * Where a seek lands, clamped to the media's bounds.
     *
     * [durationMs] is 0 while the duration is still unknown (a live-ish source, or a
     * container that has not been parsed yet); then only the lower bound applies, because
     * clamping to an unknown duration would pin every seek to the start.
     */
    fun targetPosition(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val raw = currentMs + deltaMs
        val upper = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return raw.coerceIn(0L, upper)
    }

    /** Left half brightens, right half sets volume. */
    fun verticalGestureFor(startX: Float, width: Float): VerticalGesture =
        if (startX < width / 2f) VerticalGesture.BRIGHTNESS else VerticalGesture.VOLUME

    /** Double tap: taps left of centre rewind, taps right of centre fast-forward. */
    fun seekDirectionFor(tapX: Float, width: Float): SeekDirection =
        if (tapX < width / 2f) SeekDirection.BACKWARD else SeekDirection.FORWARD

    /** Signed seek offset for a double tap on the given side. */
    fun doubleTapDeltaMs(direction: SeekDirection, stepMs: Long): Long =
        if (direction == SeekDirection.BACKWARD) -stepMs else stepMs

    /**
     * Fraction accumulated by a vertical drag of [dragPx] over a viewport [heightPx].
     *
     * Dragging the full height of the picture covers 100 % of the range, and dragging up
     * increases the value (brightness and volume both grow upwards), hence the negation.
     */
    fun verticalFraction(dragPx: Float, heightPx: Float): Float =
        if (heightPx <= 0f) 0f else (-dragPx / heightPx)

    /** New zoom level after a pinch of [factor], clamped to what the view can usefully show. */
    fun zoomAfter(current: Float, factor: Float, max: Float = 4f): Float =
        (current * factor).coerceIn(1f, max)

    /**
     * Pan offset after dragging the zoomed picture by [delta].
     *
     * The picture is centred, so at zoom z the visible half-extent is (1 - 1/z) of the
     * viewport per axis; panning further than that would show the black shutter.
     */
    fun panAfter(current: Offset, delta: Offset, zoom: Float, width: Float, height: Float): Offset {
        if (zoom <= 1f) return Offset.Zero
        val maxX = width * (1f - 1f / zoom) / 2f
        val maxY = height * (1f - 1f / zoom) / 2f
        return Offset(
            x = (current.x + delta.x).coerceIn(-maxX, maxX),
            y = (current.y + delta.y).coerceIn(-maxY, maxY),
        )
    }
}

/**
 * Remembers the previous tap so the next one can be recognised as a double tap.
 *
 * Lives in the composable, not in the gesture loop: [detectPlayerGestures] restarts for
 * every gesture, so per-gesture state cannot see the tap that came before.
 */
internal class TapTracker {
    var lastTapUptimeMs: Long = 0L
        private set
    var lastTapPosition: Offset = Offset.Zero
        private set

    fun record(uptimeMs: Long, position: Offset) {
        lastTapUptimeMs = uptimeMs
        lastTapPosition = position
    }

    fun isDoubleTap(uptimeMs: Long, position: Offset, timeoutMs: Long, slopPx: Float): Boolean {
        if (lastTapUptimeMs == 0L) return false
        val elapsed = uptimeMs - lastTapUptimeMs
        if (elapsed < 0L || elapsed > timeoutMs) return false
        return (position - lastTapPosition).getDistance() <= slopPx
    }

    fun clear() {
        lastTapUptimeMs = 0L
        lastTapPosition = Offset.Zero
    }
}

/**
 * Everything the gesture loop can ask the player to do.
 *
 * Exposed as one object rather than a dozen lambdas so the loop stays readable and so a
 * caller can disable a gesture by passing a no-op — the locked-screen mode does exactly
 * that, keeping only the tap that brings the unlock button back.
 */
internal class PlayerGestureCallbacks(
    val onTap: () -> Unit,
    val onDoubleTap: (SeekDirection) -> Unit,
    /** Long press: play faster for as long as the finger stays down. */
    val onSpeedBoostStart: () -> Unit,
    val onSpeedBoostEnd: () -> Unit,
    val onSeekStart: () -> Unit,
    /** Delta in milliseconds relative to where the drag began (signed). */
    val onSeekDelta: (Long) -> Unit,
    val onSeekEnd: (Long) -> Unit,
    val onVerticalStart: (VerticalGesture) -> Unit,
    /** Signed fraction of the full range, relative to where the drag began. */
    val onVerticalDelta: (Float) -> Unit,
    val onVerticalEnd: () -> Unit,
    val onZoom: (factor: Float, pan: Offset) -> Unit,
    val onZoomEnd: () -> Unit,
)

private enum class DragMode { UNDECIDED, SEEK, VERTICAL, ZOOM, SPEED_BOOST }

/**
 * One detector for every player gesture.
 *
 * Why not stack `detectTapGestures` and `detectDragGestures`: both consume the initial
 * down event, and whichever runs first starves the other — taps would work or drags would,
 * never both. A single state machine owns the pointer from down to up, decides what the
 * gesture is once (by slop and direction), and reports the whole arc to [callbacks].
 *
 * Long press is the one branch that needs a clock rather than an event: a finger held
 * still produces no pointer events at all, so the wait for "did this become a drag before
 * the long-press timeout" runs under [awaitPointerEventScope]'s own timeout.
 */
internal suspend fun PointerInputScope.detectPlayerGestures(
    tracker: TapTracker,
    callbacks: PlayerGestureCallbacks,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()

        val width = size.width.toFloat().coerceAtLeast(1f)
        val height = size.height.toFloat().coerceAtLeast(1f)
        val doubleTapSlop = viewConfiguration.touchSlop * 2f
        val longPressAtMs = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

        var mode = DragMode.UNDECIDED
        var total = Offset.Zero
        var pointerId = down.id
        var seekDeltaMs = 0L
        var verticalFraction = 0f

        while (true) {
            val event = if (mode == DragMode.UNDECIDED) {
                val remainingMs = longPressAtMs - SystemClock.uptimeMillis()
                if (remainingMs > 0L) {
                    withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
                } else {
                    null
                }
            } else {
                awaitPointerEvent()
            }

            // Timed out while the finger was still and undecided: this is a long press.
            if (event == null) {
                mode = DragMode.SPEED_BOOST
                callbacks.onSpeedBoostStart()
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                if (mode == DragMode.UNDECIDED) mode = DragMode.ZOOM
                if (mode == DragMode.ZOOM) {
                    val factor = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (factor != 1f || pan != Offset.Zero) {
                        callbacks.onZoom(factor, pan)
                        event.changes.forEach(PointerInputChange::consume)
                    }
                }
                continue
            }

            val change = pressed.first()
            if (change.id != pointerId) {
                pointerId = change.id
                total = Offset.Zero
            }
            val delta = change.positionChange()
            total += delta

            if (mode == DragMode.UNDECIDED && total.getDistance() > viewConfiguration.touchSlop) {
                mode = if (abs(total.x) >= abs(total.y)) {
                    callbacks.onSeekStart()
                    DragMode.SEEK
                } else {
                    callbacks.onVerticalStart(
                        PlayerGestureMath.verticalGestureFor(down.position.x, width),
                    )
                    DragMode.VERTICAL
                }
            }

            when (mode) {
                DragMode.SEEK -> {
                    seekDeltaMs = PlayerGestureMath.seekDeltaMs(total.x / density)
                    callbacks.onSeekDelta(seekDeltaMs)
                    change.consume()
                }

                DragMode.VERTICAL -> {
                    verticalFraction = PlayerGestureMath.verticalFraction(total.y, height)
                    callbacks.onVerticalDelta(verticalFraction)
                    change.consume()
                }

                else -> Unit
            }
        }

        when (mode) {
            DragMode.SEEK -> callbacks.onSeekEnd(seekDeltaMs)
            DragMode.VERTICAL -> callbacks.onVerticalEnd()
            DragMode.ZOOM -> callbacks.onZoomEnd()
            DragMode.SPEED_BOOST -> callbacks.onSpeedBoostEnd()
            DragMode.UNDECIDED -> {
                val now = SystemClock.uptimeMillis()
                if (tracker.isDoubleTap(now, down.position, viewConfiguration.doubleTapTimeoutMillis, doubleTapSlop)) {
                    tracker.clear()
                    callbacks.onDoubleTap(PlayerGestureMath.seekDirectionFor(down.position.x, width))
                } else {
                    tracker.record(now, down.position)
                    callbacks.onTap()
                }
            }
        }
    }
}
