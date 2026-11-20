package com.naslink.playback.player

import androidx.annotation.StringRes
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import com.naslink.R
import java.util.Locale

/**
 * Static choices the player exposes — speeds, aspect ratios, orientation locks, timers.
 *
 * They live outside the composables so the cycling logic can be unit-tested, and so every
 * menu in the UI reads from the same list instead of each carrying its own copy.
 */
internal object PlayerOptions {

    /**
     * The speed ladder. Wider than the old six-step cycler: VLC offers a continuous
     * panel, and half-steps matter when a lecture is *almost* comprehensible at 1.5x.
     */
    val SPEEDS = listOf(
        0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f,
    )

    /** Fallback speed when a remembered value is no longer on the ladder. */
    const val DEFAULT_SPEED = 1.0f

    /** Seconds a double tap skips. Matches the rewind/forward buttons. */
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /** Sleep-timer choices, in minutes; 0 means "off". */
    val SLEEP_TIMER_MINUTES = listOf(0, 15, 30, 45, 60, 90)

    /** How long the controls stay up after the last interaction while playing. */
    const val CONTROLS_TIMEOUT_MS = 3_500L

    /** How long a transient heads-up (seek, volume, brightness) stays on screen. */
    const val HUD_TIMEOUT_MS = 700L

    /**
     * Long-press speed. The point of the gesture is a temporary, obvious jump — 2x is
     * already unintelligible for dialogue but perfect for finding a scene — so when the
     * user is *already* faster than that, boost one notch further instead of slowing down.
     */
    fun boostSpeedFor(current: Float): Float = if (current < 2.0f) 2.0f else 3.0f

    /**
     * Nearest ladder entry, used to sanitise a persisted speed.
     *
     * A non-positive or non-finite stored value is treated as "no preference" and opens
     * at 1x. Snapping garbage to the slowest rung (0.25x) would look like a broken player,
     * and the value is only ever meant to be one this ladder produced.
     */
    fun nearestSpeed(value: Float): Float {
        if (!value.isFinite() || value <= 0f) return DEFAULT_SPEED
        return SPEEDS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_SPEED
    }

    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { it == current }
        return if (index < 0) DEFAULT_SPEED else SPEEDS[(index + 1) % SPEEDS.size]
    }

    fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

    /**
     * `mm:ss` below an hour, `h:mm:ss` above it — a two-hour film reading "125:03" is
     * technically correct and useless.
     */
    fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Human label for a seek offset, e.g. `+10s` / `-1m 20s`. */
    fun formatSeekDelta(deltaMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        val totalSeconds = kotlin.math.abs(deltaMs) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            String.format(Locale.US, "%s%dm %02ds", sign, minutes, seconds)
        } else {
            String.format(Locale.US, "%s%ds", sign, seconds)
        }
    }
}

/**
 * How the picture is scaled inside the viewport.
 *
 * Mirrors VLC's "video aspect" menu: fit letterboxes, fill crops nothing but stretches,
 * zoom crops the overflow.
 */
internal enum class PlayerAspect(
    @StringRes val labelRes: Int,
    val resizeMode: Int,
) {
    FIT(R.string.player_aspect_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL(R.string.player_aspect_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM(R.string.player_aspect_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): PlayerAspect = entries[(ordinal + 1) % entries.size]
}

/**
 * Rotation policy. [FREE] follows the device sensor; the other two pin the activity, so
 * the picture stays the way the user put it even when they lie down.
 */
internal enum class PlayerOrientation(@StringRes val labelRes: Int) {
    FREE(R.string.player_orientation_auto),
    LANDSCAPE(R.string.player_orientation_landscape),
    PORTRAIT(R.string.player_orientation_portrait);

    /** The orientation a rotate button should apply: flip whatever the user is looking at. */
    fun toggle(isLandscape: Boolean): PlayerOrientation =
        if (isLandscape) PORTRAIT else LANDSCAPE

    fun next(): PlayerOrientation = entries[(ordinal + 1) % entries.size]
}

/** Repeat cycle exposed in the overflow menu. */
internal enum class PlayerRepeat(@StringRes val labelRes: Int, val mode: Int) {
    OFF(R.string.player_repeat_off, Player.REPEAT_MODE_OFF),
    ONE(R.string.player_repeat_one, Player.REPEAT_MODE_ONE),
    ALL(R.string.player_repeat_all, Player.REPEAT_MODE_ALL);

    fun next(): PlayerRepeat = entries[(ordinal + 1) % entries.size]
}
