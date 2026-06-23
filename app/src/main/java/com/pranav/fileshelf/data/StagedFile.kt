package com.pranav.fileshelf.data

data class StagedFile(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val addedAt: Long
)
