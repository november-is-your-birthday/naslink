package com.naslink.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import com.naslink.data.model.FileItem
import com.naslink.viewmodel.ConflictDecision
import com.naslink.viewmodel.ConflictResponse
import com.naslink.viewmodel.ConflictSource
import com.naslink.viewmodel.TransferConflict
import com.naslink.viewmodel.TransferConflictDecisions
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransferConflictDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun displaysBothMetadataAndReportsReplaceWithApplyAll() {
        var response: ConflictResponse? = null
        val request = request()
        compose.setContent { MaterialTheme { TransferConflictDialog(request) { response = it } } }
        compose.onNodeWithText("Replace report.txt?").assertIsDisplayed()
        compose.onNodeWithText("Source: 42 B", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Destination: 21 B", substring = true).assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Replace", substring = false).performClick()
        assertEquals(ConflictResponse(ConflictDecision.REPLACE, true), response)
    }

    @Test
    fun skipAndCancelAreExplicitDecisions() {
        var response: ConflictResponse? = null
        val request = request()
        compose.setContent { MaterialTheme { TransferConflictDialog(request) { response = it } } }
        compose.onNodeWithText("Skip").performClick()
        assertEquals(ConflictResponse(ConflictDecision.SKIP), response)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(ConflictResponse(ConflictDecision.CANCEL), response)
    }

    private fun request() = TransferConflictDecisions.Request(
        TransferConflict(ConflictSource("report.txt", 42, Date(1000)), FileItem("report.txt", "/target/report.txt", false, 21, Date(2000))),
        CompletableDeferred(),
    )
}
