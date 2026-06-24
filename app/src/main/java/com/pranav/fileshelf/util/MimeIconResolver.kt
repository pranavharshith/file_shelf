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
    /**
     * Short label shown on the file chip ("PDF", "IMG", "DOC", etc.).
     *
     * Falls back to the filename extension when the MIME type is missing
     * or generic (application/octet-stream, wildcard types, etc.). Without
     * that fallback, anything shared with a vague intent type — common
     * from file managers and some browsers — landed on disk as
     * application/octet-stream and was permanently labelled "FILE", even
     * when the filename was clearly `report.pdf`.
     */
    fun emojiFor(mimeType: String, displayName: String? = null): String {
        labelForMime(mimeType)?.let { return it }
        displayName?.let { name ->
            labelForExtension(extensionOf(name))?.let { return it }
        }
        return "FILE"
    }

    @ColorRes
    fun chipColorRes(mimeType: String, displayName: String? = null): Int {
        chipForMime(mimeType)?.let { return it }
        displayName?.let { name ->
            chipForExtension(extensionOf(name))?.let { return it }
        }
        return R.color.colorChipOther
    }

    // ── private ──────────────────────────────────────────────────────────

    private fun labelForMime(mimeType: String): String? = when {
        mimeType.startsWith("image/") -> "IMG"
        mimeType.startsWith("video/") -> "VID"
        mimeType.startsWith("audio/") -> "AUD"
        mimeType.contains("pdf") -> "PDF"
        mimeType.contains("word") || mimeType.contains("document") -> "DOC"
        mimeType.contains("sheet") || mimeType.contains("excel") -> "XLS"
        mimeType.contains("presentation") || mimeType.contains("powerpoint") -> "PPT"
        mimeType.contains("zip") || mimeType.contains("archive") ||
            mimeType.contains("compressed") || mimeType.contains("tar") ||
            mimeType.contains("rar") || mimeType.contains("7z") -> "ZIP"
        mimeType.startsWith("text/") -> "TXT"
        else -> null
    }

    private fun labelForExtension(ext: String): String? = when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tiff", "tif" -> "IMG"
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv" -> "VID"
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma" -> "AUD"
        "pdf" -> "PDF"
        "doc", "docx", "odt", "rtf", "pages" -> "DOC"
        "xls", "xlsx", "ods", "csv", "numbers" -> "XLS"
        "ppt", "pptx", "odp", "key" -> "PPT"
        "zip", "tar", "gz", "tgz", "bz2", "xz", "rar", "7z" -> "ZIP"
        "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf" -> "TXT"
        else -> null
    }

    @ColorRes
    private fun chipForMime(mimeType: String): Int? = when {
        mimeType.contains("pdf") -> R.color.colorChipPdf
        mimeType.startsWith("image/") -> R.color.colorChipImage
        mimeType.startsWith("video/") -> R.color.colorChipVideo
        mimeType.startsWith("audio/") -> R.color.colorChipAudio
        mimeType.contains("word") || mimeType.contains("document") ||
            mimeType.contains("sheet") || mimeType.contains("excel") ||
            mimeType.contains("presentation") || mimeType.contains("powerpoint") ||
            mimeType.startsWith("text/") -> R.color.colorChipDoc
        else -> null
    }

    @ColorRes
    private fun chipForExtension(ext: String): Int? = when (ext) {
        "pdf" -> R.color.colorChipPdf
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tiff", "tif" -> R.color.colorChipImage
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv" -> R.color.colorChipVideo
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma" -> R.color.colorChipAudio
        "doc", "docx", "odt", "rtf", "pages",
        "xls", "xlsx", "ods", "csv", "numbers",
        "ppt", "pptx", "odp", "key",
        "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf" -> R.color.colorChipDoc
        else -> null
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
