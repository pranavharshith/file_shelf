package com.pranav.fileshelf.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.ColorRes
import com.pranav.fileshelf.R

fun ContentResolver.queryDisplayName(uri: Uri): String? {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}

object MimeIconResolver {
    fun emojiFor(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMG"
        mimeType.startsWith("video/") -> "VID"
        mimeType.startsWith("audio/") -> "AUD"
        mimeType.contains("pdf") -> "PDF"
        mimeType.contains("word") || mimeType.contains("document") -> "DOC"
        mimeType.contains("sheet") || mimeType.contains("excel") -> "XLS"
        mimeType.contains("zip") || mimeType.contains("archive") -> "ZIP"
        mimeType.startsWith("text/") -> "TXT"
        else -> "FILE"
    }

    @ColorRes
    fun chipColorRes(mimeType: String): Int = when {
        mimeType.contains("pdf") -> R.color.colorChipPdf
        mimeType.startsWith("image/") -> R.color.colorChipImage
        mimeType.startsWith("video/") -> R.color.colorChipVideo
        mimeType.startsWith("audio/") -> R.color.colorChipAudio
        mimeType.contains("word") || mimeType.contains("document") ||
            mimeType.contains("sheet") || mimeType.contains("excel") ||
            mimeType.startsWith("text/") -> R.color.colorChipDoc
        else -> R.color.colorChipOther
    }
}
