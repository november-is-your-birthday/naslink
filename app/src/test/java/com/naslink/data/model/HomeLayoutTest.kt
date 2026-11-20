package com.naslink.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun defaultLayoutMatchesExistingHomeOrder() {
        assertEquals(
            listOf(
                HomeSection.STORAGE,
                HomeSection.ACTIVE_SESSIONS,
                HomeSection.QUICK_ACCESS,
                HomeSection.REMOTE_CONNECTIONS,
                HomeSection.BOOKMARKS,
                HomeSection.FOLDERS,
            ),
            HomeLayout.DEFAULT.sectionOrder,
        )
        // NAS-first default: device-storage sections start hidden but keep their slot.
        assertEquals(
            setOf(HomeSection.STORAGE, HomeSection.QUICK_ACCESS, HomeSection.FOLDERS),
            HomeLayout.DEFAULT.hiddenSections,
        )
        assertEquals(
            listOf(
                HomeSection.ACTIVE_SESSIONS,
                HomeSection.REMOTE_CONNECTIONS,
                HomeSection.BOOKMARKS,
            ),
            HomeLayout.DEFAULT.visibleSections,
        )
    }

    @Test
    fun persistedLayoutDropsUnknownsAndDuplicatesThenAppendsMissingSections() {
        val layout = HomeLayout.fromPersisted(
            order = "FOLDERS,UNKNOWN,FOLDERS,STORAGE",
            hidden = "QUICK_ACCESS,REMOVED",
        )

        assertEquals(
            listOf(
                HomeSection.FOLDERS,
                HomeSection.STORAGE,
                HomeSection.ACTIVE_SESSIONS,
                HomeSection.QUICK_ACCESS,
                HomeSection.REMOTE_CONNECTIONS,
                HomeSection.BOOKMARKS,
            ),
            layout.sectionOrder,
        )
        assertEquals(setOf(HomeSection.QUICK_ACCESS), layout.hiddenSections)
    }

    @Test
    fun blankPersistenceUsesDefaultLayout() {
        assertEquals(HomeLayout.DEFAULT, HomeLayout.fromPersisted(null, null))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.fromPersisted("", ""))
    }

    @Test
    fun visibilityChangeKeepsSectionRecoverableInTheOrder() {
        val hidden = HomeLayout.DEFAULT.withVisibility(HomeSection.REMOTE_CONNECTIONS, visible = false)

        assertFalse(HomeSection.REMOTE_CONNECTIONS in hidden.visibleSections)
        assertTrue(HomeSection.REMOTE_CONNECTIONS in hidden.sectionOrder)
        assertEquals(
            HomeLayout.DEFAULT,
            hidden.withVisibility(HomeSection.REMOTE_CONNECTIONS, visible = true),
        )
    }

    @Test
    fun moveClampsAtBoundariesAndChangesOnlyOrder() {
        assertEquals(
            HomeLayout.DEFAULT,
            HomeLayout.DEFAULT.move(HomeSection.STORAGE, offset = -1),
        )
        assertEquals(
            HomeLayout.DEFAULT,
            HomeLayout.DEFAULT.move(HomeSection.FOLDERS, offset = 1),
        )

        val moved = HomeLayout.DEFAULT
            .withVisibility(HomeSection.BOOKMARKS, visible = false)
            .move(HomeSection.BOOKMARKS, offset = -1)

        assertEquals(HomeSection.BOOKMARKS, moved.sectionOrder[moved.sectionOrder.lastIndex - 2])
        assertEquals(HomeLayout.DEFAULT.hiddenSections + HomeSection.BOOKMARKS, moved.hiddenSections)
    }

    @Test
    fun persistenceRoundTripIsDeterministic() {
        val layout = HomeLayout.DEFAULT
            .move(HomeSection.FOLDERS, offset = -1)
            .withVisibility(HomeSection.ACTIVE_SESSIONS, visible = false)

        assertEquals(
            layout,
            HomeLayout.fromPersisted(layout.persistedOrder, layout.persistedHidden),
        )
        assertEquals("STORAGE,ACTIVE_SESSIONS,QUICK_ACCESS,FOLDERS", layout.persistedHidden)
    }
}
