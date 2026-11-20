package com.naslink.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Process-wide switch that parks preview generation while the screen is not being looked at.
 *
 * Two independent reasons to park, and they are not the same thing:
 *
 * - **Covered** — a full-screen viewer or player owns the screen. Preview reads compete
 *   with it for the one pooled SMB session, and the picture the user asked for used to
 *   queue behind a dozen previews of pictures they did not.
 * - **Scrolling** — a fling composes far more cells than it shows. Each remote preview is
 *   a file read, so a fast scroll through a 300-photo folder could start hundreds of reads
 *   for cells that were gone before the first byte came back.
 *
 * Parking is a suspension rather than a cancelled request: the loader waits here, and if
 * the cell leaves the screen while parked its coroutine is disposed and the read never
 * happens at all. A cell that is still on screen once the scroll settles proceeds.
 *
 * It is a process-wide object rather than a ViewModel field because the triggers are an
 * Android lifecycle event and the scroll state of a lazy layout, while the consumers are
 * leaf composables and background loaders with no shared owner.
 */
object ThumbnailLoadingGate {

    private val _enabled = MutableStateFlow(true)
    private val _scrolling = MutableStateFlow(false)

    /** Observed by [com.naslink.ui.components.FileThumbnailOrIcon] to show or park previews. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True while previews may load; handy for non-composable callers that must check. */
    val isEnabled: Boolean get() = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    fun setScrolling(scrolling: Boolean) {
        _scrolling.value = scrolling
    }

    /**
     * Suspends until previews may be generated.
     *
     * Called *before* a loader takes a concurrency permit, so a parked cell never holds a
     * slot that an on-screen cell is waiting for.
     */
    suspend fun awaitReady() {
        combine(_enabled, _scrolling) { enabled, scrolling -> enabled && !scrolling }
            .first { it }
    }
}
