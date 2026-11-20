package com.naslink.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naslink.R

private val ScrimBlack = Color.Black.copy(alpha = 0.62f)
private val ChipBlack = Color.Black.copy(alpha = 0.55f)

/**
 * One row of the track/quality style dialogs: a label, a radio state, and what tapping it does.
 */
internal data class PlayerOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** A titled group inside [PlayerListDialog]. */
internal data class PlayerOptionSection(
    val title: String,
    val options: List<PlayerOption>,
)

/**
 * Top bar: close, what is playing, and the two entry points that do not belong in the
 * bottom tool row (external player, overflow menu).
 */
@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ScrimBlack)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, stringResource(R.string.stream_close), tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onOpenExternal) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                stringResource(R.string.stream_open_externally),
                tint = Color.White,
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.player_more), tint = Color.White)
        }
    }
}

/**
 * Centre transport: 10-second jumps around the big play/pause target, the layout every
 * player converges on because it is reachable with either thumb.
 */
@Composable
internal fun PlayerCenterControls(
    isPlaying: Boolean,
    canSeek: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        IconButton(onClick = onSeekBack, enabled = canSeek) {
            Icon(
                Icons.Filled.Replay10,
                stringResource(R.string.player_seek_back),
                modifier = Modifier.size(34.dp),
                tint = if (canSeek) Color.White else Color.White.copy(alpha = 0.35f),
            )
        }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.92f),
                contentColor = Color.Black,
            ),
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onSeekForward, enabled = canSeek) {
            Icon(
                Icons.Filled.Forward10,
                stringResource(R.string.player_seek_forward),
                modifier = Modifier.size(34.dp),
                tint = if (canSeek) Color.White else Color.White.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * Bottom bar: scrubber, time labels, and the tool row.
 *
 * The slider keeps its own drag state so the thumb does not fight the position poll:
 * while the user scrubs, the poll's value is ignored and their finger wins.
 */
@Composable
internal fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    speedLabel: String,
    locked: Boolean,
    onSeekTo: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    onTracksClick: () -> Unit,
    onRotateClick: () -> Unit,
    onAspectClick: () -> Unit,
    onLockClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val positionFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = if (scrubbing) scrubFraction else positionFraction
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ScrimBlack)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlayerOptions.formatTime(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "-${PlayerOptions.formatTime(remainingMs)}",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Slider(
            value = shownFraction,
            onValueChange = {
                scrubbing = true
                scrubFraction = it
            },
            onValueChangeFinished = {
                scrubbing = false
                if (durationMs > 0L) onSeekTo((scrubFraction * durationMs).toLong())
            },
            enabled = durationMs > 0L,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerToolButton(
                icon = Icons.Filled.Speed,
                label = speedLabel,
                onClick = onSpeedClick,
            )
            PlayerToolButton(
                icon = Icons.Filled.Subtitles,
                label = stringResource(R.string.player_tracks_short),
                onClick = onTracksClick,
            )
            PlayerToolButton(
                icon = Icons.Filled.ScreenRotation,
                label = stringResource(R.string.player_rotate),
                onClick = onRotateClick,
            )
            PlayerToolButton(
                icon = Icons.Filled.AspectRatio,
                label = stringResource(R.string.player_aspect_short),
                onClick = onAspectClick,
            )
            PlayerToolButton(
                icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                label = stringResource(if (locked) R.string.player_unlock else R.string.player_lock),
                onClick = onLockClick,
                tint = if (locked) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

@Composable
private fun PlayerToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Small chip that survives outside the auto-hiding bars — the transient heads-up display. */
@Composable
internal fun PlayerHud(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(text = text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Brightness/volume feedback: an icon, a bar and a percentage, pinned to the gesture's side. */
@Composable
internal fun PlayerVerticalHud(
    kind: VerticalGesture,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (kind == VerticalGesture.BRIGHTNESS) {
                    Icons.Filled.Brightness6
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = stringResource(
                    if (kind == VerticalGesture.BRIGHTNESS) {
                        R.string.player_brightness
                    } else {
                        R.string.player_volume
                    },
                ),
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Unlock control shown while the screen is locked. */
@Composable
internal fun PlayerUnlockButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.Lock,
            stringResource(R.string.player_unlock),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Generic chooser used by every menu (speed, aspect, orientation, repeat, sleep, tracks).
 * One implementation keeps the interaction, spacing and dismissal behaviour identical.
 */
@Composable
internal fun PlayerListDialog(
    title: String,
    sections: List<PlayerOptionSection>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                sections.forEach { section ->
                    if (section.title.isNotEmpty()) {
                        item(key = "header:${section.title}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(section.options.size) { index ->
                        val option = section.options[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Dismiss first: an entry that opens a follow-up dialog
                                    // must not be closed by this dialog's own teardown.
                                    onDismiss()
                                    option.onClick()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.selected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
