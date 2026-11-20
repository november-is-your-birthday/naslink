package com.naslink.playback

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.naslink.R
import com.naslink.data.local.PreferencesManager
import com.naslink.data.repository.WatchProgressRepository
import com.naslink.playback.player.PlayerAspect
import com.naslink.playback.player.PlayerBottomBar
import com.naslink.playback.player.PlayerCenterControls
import com.naslink.playback.player.PlayerGestureCallbacks
import com.naslink.playback.player.PlayerGestureMath
import com.naslink.playback.player.PlayerHud
import com.naslink.playback.player.PlayerListDialog
import com.naslink.playback.player.PlayerOption
import com.naslink.playback.player.PlayerOptionSection
import com.naslink.playback.player.PlayerOptions
import com.naslink.playback.player.PlayerOrientation
import com.naslink.playback.player.PlayerRepeat
import com.naslink.playback.player.PlayerTopBar
import com.naslink.playback.player.PlayerUnlockButton
import com.naslink.playback.player.PlayerVerticalHud
import com.naslink.playback.player.TapTracker
import com.naslink.playback.player.VerticalGesture
import com.naslink.playback.player.detectPlayerGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Plays a URL published by [StreamPlaybackCoordinator].
 *
 * The URL always points at the in-process loopback server, so the SMB session lives in this
 * app while ExoPlayer does what it does best: buffering and seeking over HTTP.
 *
 * The player itself is deliberately all-Compose rather than `PlayerView`'s stock
 * controller. The stock controller has no notion of a long-press speed boost, a
 * brightness drag or a rotation lock, and its layout cannot be extended without forking
 * it — so this activity draws its own controls over a controller-less `PlayerView`.
 */
class StreamPlayerActivity : ComponentActivity() {

    private var token: String? = null
    private var subtitleTokens: List<String> = emptyList()
    private var mediaKey: String = ""

    /**
     * Set once the loopback URL has been handed to an external player: this activity no
     * longer owns the publication, so quitting must not revoke it out from under the
     * other app. The idle sweep in [com.naslink.stream.StreamSessionStore] reclaims the
     * SMB session once nobody reads it.
     */
    private var handedOff = false

    /** Kept as state so the Compose tree can drop its overlay while in picture-in-picture. */
    private var inPictureInPicture by mutableStateOf(false)

    private val windowController by lazy { PlayerWindowController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchProgressRepository.get(this).pruneOldEntries()
        render(intent)
    }

    /** `singleTask` delivers a second video here instead of creating a new instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        releasePublications()
        render(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        releasePublications()
        super.onDestroy()
    }

    private fun releasePublications() {
        if (!handedOff) {
            token?.let { StreamPlaybackCoordinator.revoke(it) }
            subtitleTokens.forEach { StreamPlaybackCoordinator.revoke(it) }
        }
        token = null
        subtitleTokens = emptyList()
    }

    private fun render(source: Intent?) {
        val url = source?.getStringExtra(EXTRA_URL).orEmpty()
        val title = source?.getStringExtra(EXTRA_TITLE).orEmpty()
        token = source?.getStringExtra(EXTRA_TOKEN)
        mediaKey = source?.getStringExtra(EXTRA_MEDIA_KEY).orEmpty()
        handedOff = false
        val subtitleUrls = source?.getStringArrayListExtra(EXTRA_SUBTITLE_URLS).orEmpty()
        val subtitleMimes = source?.getStringArrayListExtra(EXTRA_SUBTITLE_MIMES).orEmpty()
        subtitleTokens = source?.getStringArrayListExtra(EXTRA_SUBTITLE_TOKENS).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                StreamPlayerScreen(
                    url = url,
                    title = title,
                    subtitleUrls = subtitleUrls,
                    subtitleMimes = subtitleMimes,
                    mediaKey = mediaKey,
                    inPictureInPicture = inPictureInPicture,
                    windowController = windowController,
                    onHandOff = { handedOff = true },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_URL = "com.naslink.playback.EXTRA_URL"
        const val EXTRA_TITLE = "com.naslink.playback.EXTRA_TITLE"
        const val EXTRA_TOKEN = "com.naslink.playback.EXTRA_TOKEN"
        const val EXTRA_MEDIA_KEY = "com.naslink.playback.EXTRA_MEDIA_KEY"
        const val EXTRA_SUBTITLE_URLS = "com.naslink.playback.EXTRA_SUBTITLE_URLS"
        const val EXTRA_SUBTITLE_MIMES = "com.naslink.playback.EXTRA_SUBTITLE_MIMES"
        const val EXTRA_SUBTITLE_TOKENS = "com.naslink.playback.EXTRA_SUBTITLE_TOKENS"

        fun intent(
            context: Context,
            url: String,
            title: String,
            token: String,
            mediaKey: String = "",
            subtitleUrls: List<String> = emptyList(),
            subtitleMimes: List<String> = emptyList(),
            subtitleTokens: List<String> = emptyList(),
        ): Intent =
            Intent(context, StreamPlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_MEDIA_KEY, mediaKey)
                .putStringArrayListExtra(EXTRA_SUBTITLE_URLS, ArrayList(subtitleUrls))
                .putStringArrayListExtra(EXTRA_SUBTITLE_MIMES, ArrayList(subtitleMimes))
                .putStringArrayListExtra(EXTRA_SUBTITLE_TOKENS, ArrayList(subtitleTokens))
    }
}

/**
 * The handful of window-level controls a Compose player cannot reach on its own:
 * screen brightness, media volume, system bars, requested rotation and picture-in-picture.
 *
 * Brightness is applied to this window only (`screenBrightness`), never written back to
 * the system setting — a video player that silently rewrites the device brightness is a
 * bug report waiting to happen.
 */
internal class PlayerWindowController(private val activity: ComponentActivity) {

    private val audioManager: AudioManager? = activity.getSystemService(AudioManager::class.java)

    /** Current window brightness, falling back to the system value the first time. */
    fun currentBrightness(): Float {
        val current = activity.window.attributes.screenBrightness
        if (current >= 0f) return current.coerceIn(0.01f, 1f)
        val system = runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0.01f, 1f)
    }

    fun setBrightness(fraction: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = fraction.coerceIn(0.01f, 1f)
        activity.window.attributes = attributes
    }

    fun currentVolume(): Float {
        val manager = audioManager ?: return 0f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(fraction: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        // Flag 0: do not pop the system volume panel over the video for every drag tick.
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    /** Hide or show the status/navigation bars, letting a swipe bring them back temporarily. */
    fun setImmersive(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Forces rotation even when the system's auto-rotate switch is off — that is the point. */
    fun applyOrientation(orientation: PlayerOrientation) {
        activity.requestedOrientation = when (orientation) {
            PlayerOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    fun enterPictureInPicture() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { activity.enterPictureInPictureMode(params) }
    }
}

/** Transient heads-up content; each variant maps to one composable. */
private sealed interface PlayerHudState {
    data class Seek(val deltaMs: Long, val targetMs: Long) : PlayerHudState
    data class Vertical(val kind: VerticalGesture, val fraction: Float) : PlayerHudState
    data class Note(val text: String) : PlayerHudState
}

/** Which chooser is open. One value instead of six booleans that could disagree. */
private sealed interface PlayerDialog {
    data object Speed : PlayerDialog
    data object Tracks : PlayerDialog
    data object Aspect : PlayerDialog
    data object Orientation : PlayerDialog
    data object Repeat : PlayerDialog
    data object Sleep : PlayerDialog
    data object More : PlayerDialog
}

private const val CHECKPOINT_INTERVAL_MS = 5_000L

@OptIn(UnstableApi::class)
@Composable
private fun StreamPlayerScreen(
    url: String,
    title: String,
    subtitleUrls: List<String>,
    subtitleMimes: List<String>,
    mediaKey: String,
    inPictureInPicture: Boolean,
    windowController: PlayerWindowController,
    onHandOff: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val progress = remember { WatchProgressRepository.get(context) }
    val preferences = remember { PreferencesManager(context) }
    // Writes the remembered speed; scoped to the composition so a closed player does not
    // leave a write running against a destroyed screen.
    val preferencesScope = rememberCoroutineScope()
    // -1 means "still loading", so the player is only built once both the resume
    // decision and the remembered speed exist.
    // Keyed on the URL too: `singleTask` can re-deliver the same mediaKey while this
    // activity is alive, and the key must change then so the latest checkpoint is
    // re-read instead of reusing the position captured at the previous open.
    val savedPosition by produceState(-1L, mediaKey, url) {
        value = progress.load(mediaKey) ?: 0
    }
    val savedSpeed by produceState(-1f) {
        value = PlayerOptions.nearestSpeed(preferences.playbackSpeed.firstOrNull() ?: 1f)
    }

    if (savedPosition < 0 || savedSpeed < 0) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    var speed by remember(savedSpeed) { mutableFloatStateOf(savedSpeed) }
    var boosting by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(PlayerAspect.FIT) }
    var orientation by remember { mutableStateOf(PlayerOrientation.FREE) }
    var repeatMode by remember { mutableStateOf(PlayerRepeat.OFF) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var sleepDeadlineMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf<VideoSize?>(null) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var hud by remember { mutableStateOf<PlayerHudState?>(null) }
    var hudToken by remember { mutableLongStateOf(0L) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // Position is polled rather than pushed: ExoPlayer has no "position changed" callback,
    // and 4 Hz is enough for a scrubber to look continuous.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    val player = remember(url, savedPosition) {
        ExoPlayer.Builder(context)
            // Claims media focus so another app's audio (a call, a podcast) pauses us,
            // and pauses when headphones disconnect instead of blasting the speaker.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // The rewind/forward buttons and double-tap seeks both read these.
            .setSeekBackIncrementMs(PlayerOptions.DOUBLE_TAP_SEEK_MS)
            .setSeekForwardIncrementMs(PlayerOptions.DOUBLE_TAP_SEEK_MS)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure = error.message ?: error.cause?.message ?: "playback failed"
                    }

                    override fun onVideoSizeChanged(size: VideoSize) {
                        videoSize = size
                    }
                })
                setMediaItem(buildMediaItem(url, title, subtitleUrls, subtitleMimes))
                if (savedPosition > 0) seekTo(savedPosition)
                // Sidecar subtitles carry no language metadata; without this they would only
                // appear after the user picks them from the track selector.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                setPlaybackSpeed(speed)
                prepare()
                playWhenReady = true
            }
    }

    // Values read from inside the gesture loop must not be captured at gesture start:
    // pointerInput restarts rarely, so a captured duration would stay at its first value.
    val latestDuration by rememberUpdatedState(durationMs)
    val latestSpeed by rememberUpdatedState(speed)
    val latestLocked by rememberUpdatedState(locked)
    val latestViewport by rememberUpdatedState(viewport)

    var seekBaseMs by remember { mutableLongStateOf(0L) }
    var seekDragMs by remember { mutableStateOf<Long?>(null) }
    var seekDragDeltaMs by remember { mutableLongStateOf(0L) }
    var verticalKind by remember { mutableStateOf<VerticalGesture?>(null) }
    var verticalBase by remember { mutableFloatStateOf(0f) }
    var verticalTarget by remember { mutableFloatStateOf(0f) }

    fun showHud(state: PlayerHudState) {
        hud = state
        hudToken++
    }

    fun revealControls() {
        controlsVisible = true
        interactionTick++
    }

    fun applySpeed(next: Float) {
        speed = next
        player.setPlaybackSpeed(next)
        interactionTick++
    }

    // A heads-up is transient by definition; the token makes a repeated identical value
    // (two "+10s" in a row) restart the timer instead of inheriting the first one's.
    LaunchedEffect(hudToken) {
        if (hud == null) return@LaunchedEffect
        delay(PlayerOptions.HUD_TIMEOUT_MS)
        hud = null
    }

    // Controls retreat on their own while the video plays, but never while the user is
    // scrubbing, has a menu open, or paused deliberately.
    LaunchedEffect(controlsVisible, isPlaying, locked, dialog, interactionTick) {
        if (!controlsVisible || locked || dialog != null || !isPlaying) return@LaunchedEffect
        delay(PlayerOptions.CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    // Immersive whenever the chrome is gone, and always while locked or in picture-in-picture.
    LaunchedEffect(controlsVisible, locked, inPictureInPicture) {
        windowController.setImmersive(locked || !controlsVisible || inPictureInPicture)
    }

    LaunchedEffect(sleepDeadlineMs) {
        val deadline = sleepDeadlineMs
        if (deadline <= 0L) return@LaunchedEffect
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining)
        player.pause()
        sleepDeadlineMs = 0L
        sleepMinutes = 0
        revealControls()
        showHud(PlayerHudState.Note(context.getString(R.string.player_sleep_finished)))
    }

    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            delay(250)
        }
    }

    // Checkpoint the position while playing so a crash or swipe-away still resumes.
    // Gated on isPlaying: a paused or errored player would just rewrite the same
    // position every interval.
    LaunchedEffect(player, mediaKey) {
        while (isActive) {
            delay(CHECKPOINT_INTERVAL_MS)
            if (mediaKey.isEmpty()) continue
            val position = player.currentPosition
            if (player.isPlaying && position > WatchProgressRepository.MIN_TRACKED_POSITION_MS) {
                progress.checkpoint(
                    mediaKey,
                    position,
                    player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                )
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            if (mediaKey.isNotEmpty()) {
                if (player.playbackState == Player.STATE_ENDED) {
                    progress.clear(mediaKey)
                } else {
                    progress.checkpoint(
                        mediaKey,
                        player.currentPosition,
                        player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                    )
                }
            }
            player.release()
        }
    }

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tapTracker = remember { TapTracker() }
    val seekStepMs = PlayerOptions.DOUBLE_TAP_SEEK_MS

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                // Inflated rather than constructed: `surface_type` only exists as an XML
                // attribute, and the player needs a TextureView for pinch-zoom to apply.
                (LayoutInflater.from(ctx).inflate(R.layout.view_stream_player, null) as PlayerView)
                    .apply { this.player = player }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
            update = { view ->
                if (view.player !== player) view.player = player
                if (view.resizeMode != aspect.resizeMode) view.resizeMode = aspect.resizeMode
            },
        )

        // The gesture surface sits under the controls: anything a button does not claim
        // reaches it, which is exactly the "tap anywhere to show/hide" behaviour.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPlayerGestures(
                        tracker = tapTracker,
                        callbacks = PlayerGestureCallbacks(
                            onTap = {
                                if (latestLocked) {
                                    controlsVisible = !controlsVisible
                                } else if (controlsVisible) {
                                    controlsVisible = false
                                } else {
                                    revealControls()
                                }
                            },
                            onDoubleTap = { direction ->
                                if (!latestLocked) {
                                    val delta = PlayerGestureMath.doubleTapDeltaMs(direction, seekStepMs)
                                    val target = PlayerGestureMath.targetPosition(
                                        player.currentPosition,
                                        delta,
                                        latestDuration,
                                    )
                                    player.seekTo(target)
                                    revealControls()
                                    showHud(PlayerHudState.Seek(delta, target))
                                }
                            },
                            onSpeedBoostStart = {
                                if (!latestLocked) {
                                    boosting = true
                                    player.setPlaybackSpeed(PlayerOptions.boostSpeedFor(latestSpeed))
                                }
                            },
                            onSpeedBoostEnd = {
                                if (boosting) {
                                    boosting = false
                                    player.setPlaybackSpeed(latestSpeed)
                                }
                            },
                            onSeekStart = {
                                if (!latestLocked) {
                                    seekBaseMs = player.currentPosition
                                    seekDragMs = seekBaseMs
                                    seekDragDeltaMs = 0L
                                    interactionTick++
                                }
                            },
                            onSeekDelta = { delta ->
                                if (!latestLocked) {
                                    seekDragDeltaMs = delta
                                    seekDragMs = PlayerGestureMath.targetPosition(
                                        seekBaseMs,
                                        delta,
                                        latestDuration,
                                    )
                                }
                            },
                            onSeekEnd = { delta ->
                                if (!latestLocked) {
                                    val target = PlayerGestureMath.targetPosition(
                                        seekBaseMs,
                                        delta,
                                        latestDuration,
                                    )
                                    player.seekTo(target)
                                    seekDragMs = null
                                    showHud(PlayerHudState.Seek(delta, target))
                                    interactionTick++
                                }
                            },
                            onVerticalStart = { kind ->
                                if (!latestLocked) {
                                    verticalKind = kind
                                    verticalBase = if (kind == VerticalGesture.BRIGHTNESS) {
                                        windowController.currentBrightness()
                                    } else {
                                        windowController.currentVolume()
                                    }
                                    verticalTarget = verticalBase
                                }
                            },
                            onVerticalDelta = { fraction ->
                                if (!latestLocked) {
                                    val kind = verticalKind
                                    if (kind != null) {
                                        val target = (verticalBase + fraction).coerceIn(0f, 1f)
                                        if (kind == VerticalGesture.BRIGHTNESS) {
                                            windowController.setBrightness(target)
                                        } else {
                                            windowController.setVolume(target)
                                        }
                                        verticalTarget = target
                                        hud = PlayerHudState.Vertical(kind, target)
                                        hudToken++
                                    }
                                }
                            },
                            onVerticalEnd = {
                                verticalKind = null
                                hud = null
                            },
                            onZoom = { factor, delta ->
                                val next = PlayerGestureMath.zoomAfter(zoom, factor)
                                val size = latestViewport
                                pan = PlayerGestureMath.panAfter(
                                    current = pan,
                                    delta = delta,
                                    zoom = next,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                )
                                zoom = next
                            },
                            onZoomEnd = {
                                if (zoom <= 1.02f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            },
                        ),
                    )
                },
        )

        // Locked hides the whole chrome on purpose: the only thing still on screen is the
        // unlock button, so a pocketed phone cannot pause, seek or leave the player.
        if (controlsVisible && !locked && !inPictureInPicture) {
            Column(modifier = Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = title,
                    subtitle = videoSize?.let { "${it.width}×${it.height}" },
                    onClose = onClose,
                    onOpenExternal = {
                        val ok = openInExternalPlayer(
                            context,
                            url,
                            StreamPlaybackCoordinator.mimeTypeFor(title),
                        )
                        if (ok) {
                            onHandOff()
                        } else {
                            Toast.makeText(
                                context,
                                R.string.stream_open_externally_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onMore = {
                        revealControls()
                        dialog = PlayerDialog.More
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (sleepMinutes > 0) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PlayerHud(
                            text = stringResource(R.string.player_sleep_active, sleepMinutes),
                            icon = Icons.Filled.Timer,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlayerCenterControls(
                        isPlaying = isPlaying,
                        canSeek = durationMs > 0L,
                        onPlayPause = {
                            if (player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0L)
                                player.play()
                            } else if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                            revealControls()
                        },
                        onSeekBack = {
                            player.seekTo(
                                PlayerGestureMath.targetPosition(
                                    player.currentPosition,
                                    -seekStepMs,
                                    durationMs,
                                ),
                            )
                            revealControls()
                        },
                        onSeekForward = {
                            player.seekTo(
                                PlayerGestureMath.targetPosition(
                                    player.currentPosition,
                                    seekStepMs,
                                    durationMs,
                                ),
                            )
                            revealControls()
                        },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                PlayerBottomBar(
                    positionMs = seekDragMs ?: positionMs,
                    durationMs = durationMs,
                    speedLabel = if (boosting) {
                        PlayerOptions.speedLabel(PlayerOptions.boostSpeedFor(speed))
                    } else {
                        PlayerOptions.speedLabel(speed)
                    },
                    locked = locked,
                    onSeekTo = { target ->
                        player.seekTo(target)
                        revealControls()
                    },
                    onSpeedClick = {
                        revealControls()
                        dialog = PlayerDialog.Speed
                    },
                    onTracksClick = {
                        revealControls()
                        dialog = PlayerDialog.Tracks
                    },
                    onRotateClick = {
                        // Flip whatever the user is looking at, then remember the pin so the
                        // choice survives the rotation it just triggered.
                        orientation = orientation.toggle(isLandscape)
                        windowController.applyOrientation(orientation)
                        revealControls()
                        showHud(
                            PlayerHudState.Note(
                                context.getString(orientation.labelRes),
                            ),
                        )
                    },
                    onAspectClick = {
                        revealControls()
                        dialog = PlayerDialog.Aspect
                    },
                    onLockClick = {
                        locked = !locked
                        revealControls()
                        if (locked) {
                            showHud(PlayerHudState.Note(context.getString(R.string.player_locked)))
                        }
                    },
                )
            }
        }

        if (locked && controlsVisible && !inPictureInPicture) {
            PlayerUnlockButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .padding(16.dp),
            )
        }

        if (boosting && !inPictureInPicture) {
            PlayerHud(
                text = stringResource(
                    R.string.player_boost_active,
                    PlayerOptions.speedLabel(PlayerOptions.boostSpeedFor(speed)),
                ),
                icon = Icons.Filled.FastForward,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
            )
        }

        if (!boosting && !inPictureInPicture) {
            val dragTarget = seekDragMs
            if (dragTarget != null) {
                // While the finger is down the preview must follow it, not the 700 ms
                // transient timer — the controls are usually hidden during a drag.
                PlayerHud(
                    text = stringResource(
                        R.string.player_seek_hud,
                        PlayerOptions.formatSeekDelta(seekDragDeltaMs),
                        PlayerOptions.formatTime(dragTarget),
                    ),
                    icon = null,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                when (val current = hud) {
                    is PlayerHudState.Seek -> PlayerHud(
                        text = stringResource(
                            R.string.player_seek_hud,
                            PlayerOptions.formatSeekDelta(current.deltaMs),
                            PlayerOptions.formatTime(current.targetMs),
                        ),
                        icon = null,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is PlayerHudState.Vertical -> PlayerVerticalHud(
                        kind = current.kind,
                        fraction = current.fraction,
                        modifier = Modifier
                            .align(
                                if (current.kind == VerticalGesture.BRIGHTNESS) {
                                    Alignment.CenterStart
                                } else {
                                    Alignment.CenterEnd
                                },
                            )
                            .padding(horizontal = 24.dp),
                    )

                    is PlayerHudState.Note -> PlayerHud(
                        text = current.text,
                        icon = null,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    null -> Unit
                }
            }
        }

        if (!inPictureInPicture && failure == null && buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        val error = failure
        if (!inPictureInPicture && error != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title.ifBlank { stringResource(R.string.stream_open_failed_title) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            failure = null
                            player.prepare()
                            player.play()
                        }) { Text(stringResource(R.string.image_viewer_retry)) }
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.stream_close))
                        }
                    }
                }
            }
        }
    }

    val aspectLabel = stringResource(aspect.labelRes)
    when (dialog) {
        PlayerDialog.Speed -> PlayerListDialog(
            title = stringResource(R.string.player_speed_title),
            sections = listOf(
                PlayerOptionSection(
                    title = "",
                    options = PlayerOptions.SPEEDS.map { option ->
                        PlayerOption(
                            label = PlayerOptions.speedLabel(option),
                            selected = option == speed,
                            onClick = {
                                applySpeed(option)
                                // Remember the choice so a new episode opens at the speed the
                                // user actually watches at, not at 1x every time.
                                preferencesScope.launch { preferences.setPlaybackSpeed(option) }
                            },
                        )
                    },
                ),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.Aspect -> PlayerListDialog(
            title = stringResource(R.string.player_aspect_title),
            sections = listOf(
                PlayerOptionSection(
                    title = "",
                    options = PlayerAspect.entries.map { option ->
                        PlayerOption(
                            label = stringResource(option.labelRes),
                            selected = option == aspect,
                            onClick = {
                                aspect = option
                                // Pinch-zoom and a resize mode stack multiplicatively; reset
                                // the pinch so the chosen mode is what the user sees.
                                zoom = 1f
                                pan = Offset.Zero
                            },
                        )
                    },
                ),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.Orientation -> PlayerListDialog(
            title = stringResource(R.string.player_orientation_title),
            sections = listOf(
                PlayerOptionSection(
                    title = "",
                    options = PlayerOrientation.entries.map { option ->
                        PlayerOption(
                            label = stringResource(option.labelRes),
                            selected = option == orientation,
                            onClick = {
                                orientation = option
                                windowController.applyOrientation(option)
                            },
                        )
                    },
                ),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.Repeat -> PlayerListDialog(
            title = stringResource(R.string.player_repeat_title),
            sections = listOf(
                PlayerOptionSection(
                    title = "",
                    options = PlayerRepeat.entries.map { option ->
                        PlayerOption(
                            label = stringResource(option.labelRes),
                            selected = option == repeatMode,
                            onClick = {
                                repeatMode = option
                                player.repeatMode = option.mode
                            },
                        )
                    },
                ),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.Sleep -> PlayerListDialog(
            title = stringResource(R.string.player_sleep_timer),
            sections = listOf(
                PlayerOptionSection(
                    title = "",
                    options = PlayerOptions.SLEEP_TIMER_MINUTES.map { minutes ->
                        PlayerOption(
                            label = if (minutes == 0) {
                                stringResource(R.string.player_sleep_off)
                            } else {
                                stringResource(R.string.player_sleep_option, minutes)
                            },
                            selected = if (minutes == 0) sleepMinutes == 0 else sleepMinutes == minutes,
                            onClick = {
                                sleepMinutes = minutes
                                sleepDeadlineMs = if (minutes == 0) {
                                    0L
                                } else {
                                    System.currentTimeMillis() + minutes * 60_000L
                                }
                            },
                        )
                    },
                ),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.Tracks -> PlayerListDialog(
            title = stringResource(R.string.player_tracks_title),
            sections = buildTrackSections(
                player = player,
                audioSectionTitle = stringResource(R.string.player_audio_section),
                subtitleSectionTitle = stringResource(R.string.player_subtitle_section),
                autoLabel = stringResource(R.string.player_track_auto),
                subtitlesOffLabel = stringResource(R.string.player_subtitles_off),
            ),
            onDismiss = { dialog = null },
        )

        PlayerDialog.More -> PlayerListDialog(
            title = stringResource(R.string.player_more),
            sections = listOf(
                PlayerOptionSection(
                    title = stringResource(R.string.player_more_section_playback),
                    options = listOf(
                        PlayerOption(
                            label = stringResource(R.string.player_pip),
                            selected = false,
                            onClick = { windowController.enterPictureInPicture() },
                        ),
                        PlayerOption(
                            label = stringResource(R.string.player_repeat_title) +
                                " · " + stringResource(repeatMode.labelRes),
                            selected = repeatMode != PlayerRepeat.OFF,
                            onClick = { dialog = PlayerDialog.Repeat },
                        ),
                        PlayerOption(
                            label = stringResource(R.string.player_sleep_timer) +
                                if (sleepMinutes > 0) " · $sleepMinutes min" else "",
                            selected = sleepMinutes > 0,
                            onClick = { dialog = PlayerDialog.Sleep },
                        ),
                    ),
                ),
                PlayerOptionSection(
                    title = stringResource(R.string.player_more_section_view),
                    options = listOf(
                        PlayerOption(
                            label = stringResource(R.string.player_aspect_title) + " · $aspectLabel",
                            selected = false,
                            onClick = { dialog = PlayerDialog.Aspect },
                        ),
                        PlayerOption(
                            label = stringResource(R.string.player_orientation_auto),
                            selected = orientation == PlayerOrientation.FREE,
                            onClick = {
                                orientation = PlayerOrientation.FREE
                                windowController.applyOrientation(PlayerOrientation.FREE)
                            },
                        ),
                    ),
                ),
            ),
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

/**
 * Builds the audio and subtitle choosers from whatever the container actually offers.
 *
 * Groups can appear late (tracks are known only after the container is parsed), which is
 * why this is rebuilt on every open instead of cached.
 */
private fun buildTrackSections(
    player: Player,
    audioSectionTitle: String,
    subtitleSectionTitle: String,
    autoLabel: String,
    subtitlesOffLabel: String,
): List<PlayerOptionSection> {
    val tracks = player.currentTracks
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
    val parameters = player.trackSelectionParameters
    val subtitlesDisabled = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    // "Automatic" means no override of this type: the renderer's own preference applies.
    val audioPinned = parameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }

    val audioOptions = buildList {
        add(
            PlayerOption(
                label = autoLabel,
                selected = !audioPinned,
                onClick = { player.restoreAutomaticTrackSelection(C.TRACK_TYPE_AUDIO) },
            ),
        )
        audioGroups.forEach { group ->
            for (index in 0 until group.length) {
                add(
                    PlayerOption(
                        label = trackLabel(group.getTrackFormat(index), index),
                        selected = group.isTrackSelected(index),
                        onClick = { player.selectTrack(group, index) },
                    ),
                )
            }
        }
    }

    val subtitleOptions = buildList {
        add(
            PlayerOption(
                label = subtitlesOffLabel,
                selected = subtitlesDisabled,
                onClick = { player.disableTrackType(C.TRACK_TYPE_TEXT) },
            ),
        )
        add(
            PlayerOption(
                label = autoLabel,
                selected = !subtitlesDisabled &&
                    parameters.overrides.keys.none { it.type == C.TRACK_TYPE_TEXT },
                onClick = { player.restoreAutomaticTrackSelection(C.TRACK_TYPE_TEXT) },
            ),
        )
        textGroups.forEach { group ->
            for (index in 0 until group.length) {
                add(
                    PlayerOption(
                        label = trackLabel(group.getTrackFormat(index), index),
                        selected = group.isTrackSelected(index),
                        onClick = { player.selectTrack(group, index) },
                    ),
                )
            }
        }
    }

    return listOf(
        PlayerOptionSection(title = audioSectionTitle, options = audioOptions),
        PlayerOptionSection(title = subtitleSectionTitle, options = subtitleOptions),
    )
}

private fun trackLabel(format: androidx.media3.common.Format, index: Int): String {
    val name = format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.takeIf { it.isNotBlank() && it != "und" }
    return name ?: "#${index + 1}"
}

private fun Player.selectTrack(group: Tracks.Group, index: Int) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(group.type, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        .build()
}

private fun Player.restoreAutomaticTrackSelection(type: Int) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setTrackTypeDisabled(type, false)
        .build()
}

private fun Player.disableTrackType(type: Int) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setTrackTypeDisabled(type, true)
        .build()
}

/**
 * Opens [url] — a loopback URL published for this process — with whatever player app
 * the user picks. Loopback is device-wide, so another app on the same device can read
 * it; the publication stays registered until the session idle sweep reclaims it.
 */
private fun openInExternalPlayer(context: Context, url: String, mimeType: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), mimeType)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(Intent.createChooser(intent, null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}

/**
 * Builds the media item: the video plus any sidecar subtitle configurations, which
 * ExoPlayer fetches over the same loopback server.
 */
private fun buildMediaItem(
    url: String,
    title: String,
    subtitleUrls: List<String>,
    subtitleMimes: List<String>,
): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    val configurations = subtitleUrls.mapIndexed { index, subtitleUrl ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
            .setMimeType(subtitleMimes.getOrNull(index))
            .build()
    }
    if (configurations.isNotEmpty()) builder.setSubtitleConfigurations(configurations)
    return builder.build()
}
