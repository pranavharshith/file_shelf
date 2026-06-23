package com.pranav.fileshelf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.pranav.fileshelf.service.OverlayService
import com.pranav.fileshelf.util.PermissionHelper
import com.pranav.fileshelf.worker.FileCopyWorker

class ShareReceiverActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()
        overridePendingTransition(0, 0)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finishImmediate()
            return
        }

        val uris = extractShareUris(intent)
        if (uris.isEmpty()) {
            finishImmediate()
            return
        }

        // SECURITY: Validate URIs before processing
        val validUris = uris.filter { (uri, _) -> isValidUri(uri) }
        
        if (validUris.isEmpty()) {
            android.util.Log.w("ShareReceiverActivity", "All URIs rejected as invalid")
            android.widget.Toast.makeText(
                applicationContext,
                "Unable to process shared files",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            finishImmediate()
            return
        }

        validUris.forEach { (uri, mime) ->
            FileCopyWorker.enqueue(applicationContext, uri, mime)
        }

        if (PermissionHelper.hasHardPermissions(applicationContext)) {
            OverlayService.startIfNeeded(applicationContext)
        }

        finishImmediate()
    }
    
    /**
     * Security validation: Ensures URI is safe to process.
     * Prevents malicious apps from sending crafted URIs that crash File Shelf.
     */
    private fun isValidUri(uri: android.net.Uri): Boolean {
        try {
            // Reject null or empty URIs
            if (uri == android.net.Uri.EMPTY || uri.toString().isBlank()) {
                return false
            }
            
            // Only accept content:// and file:// schemes (standard Android file sharing)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "content" && scheme != "file") {
                android.util.Log.w("ShareReceiverActivity", "Rejected URI with invalid scheme: $scheme")
                return false
            }
            
            // Verify URI can be queried (basic sanity check)
            contentResolver.getType(uri)
            
            return true
        } catch (e: Exception) {
            android.util.Log.w("ShareReceiverActivity", "URI validation failed: ${uri}", e)
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun finishImmediate() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun extractShareUris(intent: Intent): List<Pair<Uri, String?>> {
            val results = mutableListOf<Pair<Uri, String?>>()
            val mime = intent.type

            addClipDataUris(intent, mime, results)
            addSingleStreamUri(intent, mime, results)
            addMultipleStreamUris(intent, mime, results)

            return results
        }

        private fun addClipDataUris(
            intent: Intent,
            mime: String?,
            results: MutableList<Pair<Uri, String?>>
        ) {
            val clip = intent.clipData ?: return
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                if (results.none { it.first == uri }) {
                    results.add(uri to mime)
                }
            }
        }

        private fun addSingleStreamUri(
            intent: Intent,
            mime: String?,
            results: MutableList<Pair<Uri, String?>>
        ) {
            val singleStream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            singleStream?.let { uri ->
                if (results.none { it.first == uri }) results.add(uri to mime)
            }
        }

        @Suppress("DEPRECATION")
        private fun addMultipleStreamUris(
            intent: Intent,
            mime: String?,
            results: MutableList<Pair<Uri, String?>>
        ) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                if (results.none { it.first == uri }) results.add(uri to mime)
            }
        }
    }
}
