package com.naslink.audio

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naslink.data.model.FileItem
import com.naslink.ui.components.AudioToneMenuItems
import com.naslink.ui.text.UiText
import com.naslink.viewmodel.OperationOutcome
import com.naslink.viewmodel.OperationState
import com.naslink.viewmodel.TransferOperationController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioToneActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuOffersBothExplicitToneTypes() {
        val chosen = mutableListOf<AudioTone>()
        compose.setContent {
            MaterialTheme {
                DropdownMenu(expanded = true, onDismissRequest = {}) {
                    AudioToneMenuItems(FileItem("sound.mp3", "/sound.mp3", false)) { _, tone -> chosen += tone }
                }
            }
        }
        compose.onNodeWithText("Set as ringtone").performClick()
        compose.onNodeWithText("Set as notification tone").performClick()
        assertEquals(listOf(AudioTone.RINGTONE, AudioTone.NOTIFICATION), chosen)
    }

    @Test fun cancellationBeforeCommitPreventsSettingsMutationButAcceptedCommitFinishes() = runBlocking {
        for (cancelBefore in listOf(true, false)) {
            val proceed = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var mutated = false
            lateinit var controller: TransferOperationController
            withContext(Dispatchers.Main.immediate) {
                controller = TransferOperationController({})
                controller.launch(UiText.Dynamic("Setting tone"), {}, { finished.complete(Unit) }) {
                    proceed.await()
                    controller.beginCommit()
                    controller.cancel()
                    assertFalse((controller.state.value as OperationState.Running).cancellable)
                    mutated = true
                }
                if (cancelBefore) controller.cancel()
                proceed.complete(Unit)
            }
            finished.await()
            assertEquals(!cancelBefore, mutated)
            assertEquals(if (cancelBefore) OperationOutcome.CANCELLED else OperationOutcome.COMPLETED, controller.lastResult.value!!.outcome)
        }
    }
}
