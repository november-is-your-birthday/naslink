package com.naslink.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.naslink.ui.components.RemoteImageThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naslink.R
import kotlin.math.roundToInt

/**
 * Full-screen viewer for images that live on a remote server.
 *
 * Bytes come from the in-process loopback server, so a photo is read over SMB without being
 * downloaded to a temp file first, and the same pooled SMB session serves the thumbnail
 * grid, this viewer and video playback.
 *
 * Decoding is left to Coil so memory stays bounded: a NAS full of 100 MP photos would
 * otherwise be a guaranteed OOM. Only the zoomed state asks for original pixels, because
 * that is the one case where downscaled pixels would be visibly wrong.
 */
class ImageViewerActivity : ComponentActivity() {

    /**
     * Instance state, not a companion `var`: a static token list would be revoked by
     * whichever viewer finished first, breaking any other viewer still on screen — the same
     * bug the video player already had.
     */
    private var tokens: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        revokeTokens()
        render(intent)
    }

    override fun onDestroy() {
        revokeTokens()
        super.onDestroy()
    }

    private fun revokeTokens() {
        tokens.forEach { StreamPlaybackCoordinator.revoke(it) }
        tokens = emptyList()
    }

    private fun render(source: Intent?) {
        val urls = source?.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        val titles = source?.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        tokens = source?.getStringArrayListExtra(EXTRA_TOKENS).orEmpty()
        // The cache key travels with the rest: without it the entries rebuilt here would
        // all share one empty cache key, and every photo after the first would be served
        // the first one's bytes out of the memory or disk cache.
        val cacheKeys = source?.getStringArrayListExtra(EXTRA_CACHE_KEYS).orEmpty()
        // Zip the parallel lists defensively: a truncated intent must not crash the
        // viewer, and the shortest list is the only fully trustworthy one.
        val entries = urls.indices.map { i ->
            PublishedImage(
                url = urls[i],
                title = titles.getOrElse(i) { "" },
                token = tokens.getOrElse(i) { "" },
                cacheKey = cacheKeys.getOrElse(i) { "" },
            )
        }
        if (entries.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val startIndex = (source?.getIntExtra(EXTRA_INDEX, 0) ?: 0).coerceIn(0, entries.lastIndex)
        setContent {
            MaterialTheme {
                ImageViewerScreen(
                    entries = entries,
                    startIndex = startIndex,
                    // Resolved through the Application's ImageLoaderFactory, so the viewer
                    // shares one memory and disk cache with every thumbnail in the app.
                    imageLoader = LocalContext.current.imageLoader,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_URLS = "com.naslink.image.EXTRA_URLS"
        const val EXTRA_TITLES = "com.naslink.image.EXTRA_TITLES"
        const val EXTRA_TOKENS = "com.naslink.image.EXTRA_TOKENS"
        const val EXTRA_CACHE_KEYS = "com.naslink.image.EXTRA_CACHE_KEYS"
        const val EXTRA_INDEX = "com.naslink.image.EXTRA_INDEX"

        /**
         * @param entries every viewable image in the folder, so paging stays inside the
         *   published set instead of needing a new publish per swipe.
         */
        fun intent(context: Context, entries: List<PublishedImage>, startIndex: Int): Intent =
            Intent(context, ImageViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(entries.map { it.url }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(entries.map { it.title }))
                // Tokens travel along so the viewer can release every publication on
                // close; without them the pooled SMB sources would stay warm forever.
                .putStringArrayListExtra(EXTRA_TOKENS, ArrayList(entries.map { it.token }))
                // Cache keys travel along too: the viewer rebuilds its entries from the
                // intent extras, so anything left out of the intent is silently lost.
                .putStringArrayListExtra(EXTRA_CACHE_KEYS, ArrayList(entries.map { it.cacheKey }))
                .putExtra(EXTRA_INDEX, startIndex)
    }
}

/** One viewable image: where to read it, what to call it, and how to release it. */
data class PublishedImage(
    val url: String,
    val title: String,
    /** Token to release once the viewer is gone, or empty when the URL is not owned here. */
    val token: String = "",
    /**
     * Stable identity for Coil's caches, built from the file rather than from [url]:
     * the URL carries a random port and a random token, so keying on it would make every
     * full-size picture a fresh disk entry that can never be hit again. Those entries are
     * megabytes each, which is what pushed the image cache to tens of megabytes.
     *
     * Travels to the viewer through [EXTRA_CACHE_KEYS] — the intent boundary drops
     * anything not explicitly passed, and the requests treat a blank key as "no key"
     * rather than as one key shared by every photo.
     */
    val cacheKey: String = "",
)

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
private fun ImageViewerScreen(
    entries: List<PublishedImage>,
    startIndex: Int,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex) { entries.size }
    val scope = rememberCoroutineScope()

    // One request per visible page, and nothing else. An earlier version warmed the
    // neighbours after the current picture had loaded; that spends bandwidth the user is
    // not waiting on, and because a "warm" is a full original-bytes read it kept pulling
    // data after they had already closed the viewer. Paging to an uncached picture now
    // costs what it costs, which is the honest trade.

    // Hand every page except the visible one back to the pool. Every publication keeps its
    // own SMB handle once read, so a long slideshow used to hold one NAS session per
    // picture — and past roughly twenty the server starts refusing new ones, which looks
    // like "nothing opens any more" until the app is restarted. A page whose request is
    // already in flight is skipped inside the publication itself, so this cannot cut off a
    // read that is still running.
    LaunchedEffect(pagerState.currentPage, entries) {
        val current = pagerState.currentPage
        withContext(Dispatchers.IO) {
            entries.forEachIndexed { index, entry ->
                if (index != current && entry.token.isNotEmpty()) {
                    StreamPlaybackCoordinator.releaseHandle(entry.token)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableRemoteImage(
                image = entries[page],
                imageLoader = imageLoader,
                // A single tap closes the viewer, unless the image is zoomed in — there it
                // resets to fit first, which is what every gallery app does.
                onTap = onClose,
                modifier = Modifier.fillMaxSize(),
            )
        }

        ViewerChrome(
            position = pagerState.currentPage + 1,
            total = entries.size,
            canGoBack = pagerState.currentPage > 0,
            canGoForward = pagerState.currentPage < entries.lastIndex,
            onPrevious = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            },
            onNext = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * A pinch- and double-tap-zoomable image loaded from a URL.
 *
 * Written by hand rather than pulled from a gallery library: the behaviour is a scale factor
 * plus a clamped offset, and a dependency would cost more than it saves.
 *
 * The gesture handler deliberately leaves plain one-finger drags unconsumed while the image
 * is fit to the screen, so the surrounding pager can flip pages; it claims the gesture only
 * once a second finger lands or the image is already zoomed.
 */
@Composable
private fun ZoomableRemoteImage(
    image: PublishedImage,
    imageLoader: ImageLoader,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Zoom state resets per image so paging never inherits the previous zoom.
    var scale by remember(image.url) { mutableFloatStateOf(1f) }
    var offset by remember(image.url) { mutableStateOf(IntOffset.Zero) }
    var viewport by remember(image.url) { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    // A blank key must fall back to Coil's default: an empty string would make every photo
    // share one memory entry, so a page could be served the previous page's pixels.
    val cacheKey = image.cacheKey.takeIf { it.isNotEmpty() }
    // The grid already decoded this picture at preview size, so showing it here covers
    // the black frame between the tap and the first full-size pixel — without a second
    // network request. Null when the page was never previewed (paging past the grid's
    // viewport), in which case the page simply appears when its bytes arrive.
    val placeholder = remember(cacheKey) {
        cacheKey?.let { RemoteImageThumbnailLoader.cachedPreview(it) }
            ?.let { BitmapPainter(it.asImageBitmap()) }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = it }
            .pointerInput(image.url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (event.changes.size > 1 || scale > 1f) {
                            val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                            scale = next
                            offset = if (next > 1f && viewport.width > 0 && viewport.height > 0) {
                                // Panning is only meaningful once the image overflows its
                                // viewport; clamping to that overflow keeps a stray drag
                                // from losing the image.
                                val maxX = (viewport.width * (next - 1f) / 2f).roundToInt()
                                val maxY = (viewport.height * (next - 1f) / 2f).roundToInt()
                                IntOffset(
                                    x = (offset.x + panChange.x).roundToInt().coerceIn(-maxX, maxX),
                                    y = (offset.y + panChange.y).roundToInt().coerceIn(-maxY, maxY),
                                )
                            } else {
                                IntOffset.Zero
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(image.url) {
                detectTapGestures(
                    // A tap dismisses the viewer when it is showing the whole image, and
                    // collapses the zoom first when it is not. Closing straight away while
                    // zoomed would make the image impossible to frame.
                    onTap = { if (scale > 1f) { scale = 1f; offset = IntOffset.Zero } else onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = IntOffset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.url)
                // Keyed on the file, not the URL: a random token per publish would make
                // every revisit a fresh memory entry and the cache would never hit. The
                // decode is screen-sized, so paging back to a page already seen is
                // instant and costs no network at all.
                .memoryCacheKey(cacheKey)
                // One request per image, never resized on zoom: switching to ORIGINAL once
                // the user pinches would change the cache key mid-view, Coil would drop the
                // drawable and re-decode over SMB, and the screen would flash black between
                // a zoom-in and the fresh pixels. The screen-fit decode is what gets scaled
                // by graphicsLayer; up to MAX_ZOOM it stays sharp enough.
                .crossfade(false)
                .build(),
            imageLoader = imageLoader,
            contentDescription = image.title,
            contentScale = ContentScale.Fit,
            placeholder = placeholder,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x.toFloat()
                translationY = offset.y.toFloat()
            },
        )
    }
}

@Composable
private fun ViewerChrome(
    position: Int,
    total: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            // Scrim so the controls stay readable over a bright photo.
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .safeDrawingPadding()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = stringResource(R.string.image_viewer_prev),
                tint = Color.White,
            )
        }
        if (total > 1) {
            Text(
                text = stringResource(R.string.image_viewer_of_count, position, total),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = stringResource(R.string.image_viewer_next),
                tint = Color.White,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.image_viewer_close),
                tint = Color.White,
            )
        }
    }
}
