package com.naslink.playback.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's arithmetic, tested without a device.
 *
 * These are exactly the parts that are easy to get wrong in a way nobody notices until a
 * two-hour film jumps to the wrong place: the drag-to-seek ratio, the clamping at the end
 * of the media, and the pan bounds of a zoomed picture.
 */
class PlayerGestureMathTest {

    // --- drag to seek ---------------------------------------------------------------

    @Test
    fun `a drag of twelve dp is one second of seek`() {
        assertEquals(1000L, PlayerGestureMath.seekDeltaMs(12f))
    }

    @Test
    fun `dragging backwards yields a negative delta`() {
        assertEquals(-1000L, PlayerGestureMath.seekDeltaMs(-12f))
    }

    @Test
    fun `a zero drag does not move the position`() {
        assertEquals(0L, PlayerGestureMath.seekDeltaMs(0f))
    }

    // --- seek clamping --------------------------------------------------------------

    @Test
    fun `seek never crosses the end of the media`() {
        assertEquals(60_000L, PlayerGestureMath.targetPosition(59_000L, 10_000L, 60_000L))
    }

    @Test
    fun `seek never crosses the start of the media`() {
        assertEquals(0L, PlayerGestureMath.targetPosition(3_000L, -10_000L, 60_000L))
    }

    @Test
    fun `seek stays positive when the duration is still unknown`() {
        // A container that has not been parsed reports 0; clamping to it would pin every
        // forward seek to the first frame.
        assertEquals(35_000L, PlayerGestureMath.targetPosition(25_000L, 10_000L, 0L))
        assertEquals(0L, PlayerGestureMath.targetPosition(5_000L, -10_000L, 0L))
    }

    // --- which vertical gesture -----------------------------------------------------

    @Test
    fun `the left half changes brightness and the right half volume`() {
        assertEquals(
            VerticalGesture.BRIGHTNESS,
            PlayerGestureMath.verticalGestureFor(startX = 100f, width = 1000f),
        )
        assertEquals(
            VerticalGesture.VOLUME,
            PlayerGestureMath.verticalGestureFor(startX = 600f, width = 1000f),
        )
    }

    @Test
    fun `the exact centre belongs to the volume side`() {
        assertEquals(
            VerticalGesture.VOLUME,
            PlayerGestureMath.verticalGestureFor(startX = 500f, width = 1000f),
        )
    }

    // --- vertical drag arithmetic ---------------------------------------------------

    @Test
    fun `dragging up increases the value and dragging down decreases it`() {
        assertTrue(PlayerGestureMath.verticalFraction(dragPx = -100f, heightPx = 1000f) > 0f)
        assertTrue(PlayerGestureMath.verticalFraction(dragPx = 100f, heightPx = 1000f) < 0f)
    }

    @Test
    fun `a full-height drag covers the whole range`() {
        assertEquals(1f, PlayerGestureMath.verticalFraction(dragPx = -1000f, heightPx = 1000f), 0.0001f)
    }

    @Test
    fun `a zero-height viewport cannot divide by zero`() {
        assertEquals(0f, PlayerGestureMath.verticalFraction(dragPx = -100f, heightPx = 0f), 0.0001f)
    }

    // --- pinching -------------------------------------------------------------------

    @Test
    fun `zoom never shrinks below the fitted picture`() {
        assertEquals(1f, PlayerGestureMath.zoomAfter(current = 1f, factor = 0.5f), 0.0001f)
    }

    @Test
    fun `zoom is capped so the image cannot dissolve into pixels`() {
        assertEquals(4f, PlayerGestureMath.zoomAfter(current = 3f, factor = 2f), 0.0001f)
    }

    @Test
    fun `zoom multiplies within range`() {
        assertEquals(2f, PlayerGestureMath.zoomAfter(current = 1f, factor = 2f), 0.0001f)
    }

    // --- panning a zoomed picture ---------------------------------------------------

    @Test
    fun `an unzoomed picture cannot be panned`() {
        val pan = PlayerGestureMath.panAfter(
            current = Offset(50f, 50f),
            delta = Offset(20f, 20f),
            zoom = 1f,
            width = 1000f,
            height = 500f,
        )
        assertEquals(Offset.Zero, pan)
    }

    @Test
    fun `pan is clamped to the visible overflow`() {
        // At 2x the picture overflows 500px horizontally and 250px vertically in total,
        // so the centre may move 250/125 px from the middle before showing the shutter.
        val pan = PlayerGestureMath.panAfter(
            current = Offset.Zero,
            delta = Offset(10_000f, 10_000f),
            zoom = 2f,
            width = 1000f,
            height = 500f,
        )
        assertEquals(250f, pan.x, 0.0001f)
        assertEquals(125f, pan.y, 0.0001f)
    }

    @Test
    fun `pan accumulates while inside the bounds`() {
        val pan = PlayerGestureMath.panAfter(
            current = Offset(10f, -10f),
            delta = Offset(20f, -20f),
            zoom = 2f,
            width = 1000f,
            height = 500f,
        )
        assertEquals(30f, pan.x, 0.0001f)
        assertEquals(-30f, pan.y, 0.0001f)
    }

    // --- double tap -----------------------------------------------------------------

    @Test
    fun `double tapping the left half rewinds and the right half fast-forwards`() {
        assertEquals(
            SeekDirection.BACKWARD,
            PlayerGestureMath.seekDirectionFor(tapX = 10f, width = 1000f),
        )
        assertEquals(
            SeekDirection.FORWARD,
            PlayerGestureMath.seekDirectionFor(tapX = 990f, width = 1000f),
        )
    }

    @Test
    fun `double tap delta carries the direction sign`() {
        assertEquals(-10_000L, PlayerGestureMath.doubleTapDeltaMs(SeekDirection.BACKWARD, 10_000L))
        assertEquals(10_000L, PlayerGestureMath.doubleTapDeltaMs(SeekDirection.FORWARD, 10_000L))
    }
}

/** Time formatting and the speed ladder — the two things every control row depends on. */
class PlayerOptionsTest {

    @Test
    fun `times under an hour are minutes and seconds`() {
        assertEquals("0:00", PlayerOptions.formatTime(0L))
        assertEquals("1:05", PlayerOptions.formatTime(65_000L))
        assertEquals("59:59", PlayerOptions.formatTime(3_599_000L))
    }

    @Test
    fun `times over an hour carry the hour`() {
        assertEquals("1:00:00", PlayerOptions.formatTime(3_600_000L))
        assertEquals("1:02:05", PlayerOptions.formatTime(3_725_000L))
    }

    @Test
    fun `negative positions read as the start`() {
        assertEquals("0:00", PlayerOptions.formatTime(-5_000L))
    }

    @Test
    fun `seek labels are human readable in both directions`() {
        assertEquals("+10s", PlayerOptions.formatSeekDelta(10_000L))
        assertEquals("-10s", PlayerOptions.formatSeekDelta(-10_000L))
        assertEquals("-1m 20s", PlayerOptions.formatSeekDelta(-80_000L))
    }

    @Test
    fun `whole speeds drop the decimal point`() {
        assertEquals("1x", PlayerOptions.speedLabel(1f))
        assertEquals("2x", PlayerOptions.speedLabel(2f))
        assertEquals("1.5x", PlayerOptions.speedLabel(1.5f))
        assertEquals("0.25x", PlayerOptions.speedLabel(0.25f))
    }

    @Test
    fun `cycling speeds wraps around the whole ladder`() {
        assertEquals(0.5f, PlayerOptions.nextSpeed(0.25f), 0.0001f)
        assertEquals(PlayerOptions.SPEEDS.first(), PlayerOptions.nextSpeed(PlayerOptions.SPEEDS.last()))
    }

    @Test
    fun `an unknown speed falls back to the nearest ladder entry`() {
        assertEquals(1.0f, PlayerOptions.nearestSpeed(0.98f), 0.0001f)
        assertEquals(1.5f, PlayerOptions.nearestSpeed(1.44f), 0.0001f)
        assertEquals(1.0f, PlayerOptions.nearestSpeed(0f), 0.0001f)
    }

    @Test
    fun `long press boosts to 2x and not down when already faster`() {
        assertEquals(2f, PlayerOptions.boostSpeedFor(1f), 0.0001f)
        assertEquals(2f, PlayerOptions.boostSpeedFor(1.5f), 0.0001f)
        assertEquals(3f, PlayerOptions.boostSpeedFor(2f), 0.0001f)
        assertEquals(3f, PlayerOptions.boostSpeedFor(3f), 0.0001f)
    }

    @Test
    fun `every ladder entry has a distinct label`() {
        val labels = PlayerOptions.SPEEDS.map(PlayerOptions::speedLabel)
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.endsWith("x") })
    }
}
