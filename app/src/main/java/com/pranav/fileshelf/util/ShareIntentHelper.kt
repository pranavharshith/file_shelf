package com.pranav.fileshelf.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.DragEvent
import android.view.View
import androidx.core.content.FileProvider
import com.pranav.fileshelf.R
import com.pranav.fileshelf.data.StagedFile
import java.io.File

object ShareIntentHelper {

    fun buildShareIntent(context: Context, file: StagedFile): Intent =
        buildShareIntent(context, listOf(file))

    fun buildShareIntent(context: Context, files: List<StagedFile>): Intent {
        require(files.isNotEmpty()) { "No staged files to share" }
        val uris = files.map { file ->
            val localFile = File(file.localPath)
            require(localFile.exists()) { "Staged file missing: ${file.localPath}" }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
        }

        if (files.size == 1) {
            return Intent(Intent.ACTION_SEND).apply {
                type = files.first().mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_SUBJECT, files.first().displayName)
                clipData = ClipData.newUri(context.contentResolver, files.first().displayName, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val clipData = ClipData.newUri(context.contentResolver, files.first().displayName, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = sharedMimeType(files)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.resources.getQuantityString(R.plurals.file_count_label, files.size, files.size)
            )
            this.clipData = clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launchChooser(context: Context, file: StagedFile) =
        launchChooser(context, listOf(file))

    fun launchChooser(context: Context, files: List<StagedFile>) {
        if (files.isEmpty()) return
        val intent = buildShareIntent(context, files)
        val chooserTitle = if (files.size == 1) {
            "Send ${files.first().displayName}"
        } else {
            "Send ${files.size} files"
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun sharedMimeType(files: List<StagedFile>): String {
        val firstMimeType = files.first().mimeType
        return if (files.all { it.mimeType == firstMimeType }) firstMimeType else "*/*"
    }
}

object DragHelper {

    fun startDrag(
        view: View,
        context: Context,
        file: StagedFile,
        onDragStarted: () -> Unit = {},
        onDragEnded: (accepted: Boolean) -> Unit = {}
    ): Boolean = startDrag(view, context, listOf(file), onDragStarted, onDragEnded)

    fun startDrag(
        view: View,
        context: Context,
        files: List<StagedFile>,
        onDragStarted: () -> Unit = {},
        onDragEnded: (accepted: Boolean) -> Unit = {}
    ): Boolean {
        if (files.isEmpty()) return false
        val clip = buildClipData(context, files) ?: return false
        val shadow = if (files.size == 1) {
            FileDragShadow(view, files.first().displayName, MimeIconResolver.emojiFor(files.first().mimeType, files.first().displayName))
        } else {
            FileDragShadow(view, "${files.size} files", "FILES")
        }

        view.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                view.setOnDragListener(null)
                onDragEnded(event.result)
            }
            false
        }

        val started = view.startDragAndDrop(
            clip,
            shadow,
            files,
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        )
        if (started) onDragStarted()
        return started
    }

    private fun buildClipData(context: Context, files: List<StagedFile>): ClipData? {
        val uris = files.mapNotNull { file ->
            val localFile = File(file.localPath)
            if (!localFile.exists()) return@mapNotNull null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
        }
        if (uris.size != files.size || uris.isEmpty()) return null

        // Build a ClipDescription that advertises the union of all files' MIME
        // types plus generic stream fallbacks. ClipData.newUri() only inspects
        // the FIRST uri to populate the description, so multi-file drags would
        // otherwise expose only one file's type and be rejected by receivers
        // (ChatGPT, Grok, Gboard, Drive, Chrome, etc.) that filter on
        // ClipDescription.hasMimeType() in ACTION_DRAG_STARTED. Including
        // application/octet-stream and */* makes the drag acceptable to
        // generic file/attachment targets regardless of the specific type.
        //
        // text/plain (and text/* in general) must NOT appear in the description
        // for a file drag. Apps like WhatsApp treat a ClipDescription that
        // advertises text/plain as a text-paste event rather than a file drop —
        // they try to read the item as text, find a content URI instead, and
        // reject it. Using application/octet-stream for all text/* files ensures
        // receivers treat the drop as a document/file attachment, not a paste.
        val mimeTypes = linkedSetOf<String>().apply {
            files.forEach { f ->
                val raw = f.mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                // Remap text/* → application/octet-stream so messaging/upload
                // targets handle the drop as a file, not as clipboard text.
                val t = if (raw.startsWith("text/")) "application/octet-stream" else raw
                add(t)
            }
            add("application/octet-stream")
            add("*/*")
        }

        val label = if (files.size == 1) files.first().displayName else "${files.size} files"
        val description = ClipDescription(label, mimeTypes.toTypedArray())
        val clip = ClipData(description, ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    private class FileDragShadow(
        view: View,
        private val name: String,
        private val labelPrefix: String
    ) : View.DragShadowBuilder(view) {

        private val density = view.resources.displayMetrics.density
        private fun dp(value: Float) = (value * density).toInt()

        private val hPad = dp(14f)
        private val vPad = dp(10f)
        private val cornerRadius = dp(12f).toFloat()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FFFFFF")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1E")
            textSize = dp(13f).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dp(2f).toFloat(), 0f, dp(1f).toFloat(), Color.parseColor("#1A000000"))
        }

        private val maxTextWidth = dp(220f).toFloat()

        private val label: String by lazy {
            val full = "$labelPrefix  $name"
            if (textPaint.measureText(full) <= maxTextWidth) full else {
                var trimmed = name
                while (trimmed.isNotEmpty() && textPaint.measureText("$labelPrefix  $trimmed...") > maxTextWidth) {
                    trimmed = trimmed.dropLast(1)
                }
                "$labelPrefix  $trimmed..."
            }
        }

        private val shadowW by lazy { (textPaint.measureText(label) + hPad * 2).toInt() }
        private val shadowH by lazy { textPaint.descent().toInt() - textPaint.ascent().toInt() + vPad * 2 }

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(shadowW, shadowH)
            outShadowTouchPoint.set(shadowW / 2, 0)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.drawRoundRect(
                RectF(0f, 0f, shadowW.toFloat(), shadowH.toFloat()),
                cornerRadius,
                cornerRadius,
                bgPaint
            )
            canvas.drawText(label, hPad.toFloat(), vPad - textPaint.ascent(), textPaint)
        }
    }
}
