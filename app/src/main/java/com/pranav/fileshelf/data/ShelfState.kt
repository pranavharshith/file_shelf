package com.pranav.fileshelf.data

data class PendingCopy(
    val id: String,
    val displayName: String,
    val startedAt: Long = System.currentTimeMillis()
)

sealed class ShelfUiState {
    data object Idle : ShelfUiState()
    data class Copying(val pending: List<PendingCopy>) : ShelfUiState()
    data class Ready(val files: List<StagedFile>) : ShelfUiState()
    data class Error(val message: String) : ShelfUiState()
}
