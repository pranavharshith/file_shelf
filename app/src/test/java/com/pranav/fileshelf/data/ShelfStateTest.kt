package com.pranav.fileshelf.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfStateTest {

    @Test fun `Idle is a singleton`() {
        assertThat(ShelfUiState.Idle).isSameInstanceAs(ShelfUiState.Idle)
    }

    @Test fun `Copying carries a list of pending entries`() {
        val pending = listOf(PendingCopy(id = "p1", displayName = "a.txt"))
        val state: ShelfUiState = ShelfUiState.Copying(pending)
        assertThat(state).isInstanceOf(ShelfUiState.Copying::class.java)
        assertThat((state as ShelfUiState.Copying).pending).hasSize(1)
    }

    @Test fun `Ready carries staged files`() {
        val files = listOf(
            StagedFile("1", "a", "text/plain", "/p", 1, "h", 0)
        )
        val state: ShelfUiState = ShelfUiState.Ready(files)
        assertThat((state as ShelfUiState.Ready).files).isEqualTo(files)
    }

    @Test fun `Error carries a message`() {
        val state: ShelfUiState = ShelfUiState.Error("boom")
        assertThat((state as ShelfUiState.Error).message).isEqualTo("boom")
    }
}
