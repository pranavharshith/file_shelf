package com.pranav.fileshelf

import android.app.Application
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileShelfApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // SAFETY: Set instance FIRST before any code tries to access it
        _instance = this
        
        NotificationHelper.createChannels(this)
        appScope.launch {
            // Clean up expired files
            FileShelfRepository.cleanupExpired(this@FileShelfApp)
            
            // STABILITY: Clean up orphaned files (incomplete copies from crashes)
            cleanupOrphanedFiles()
        }
    }
    
    /**
     * Removes files in shelf directory that aren't tracked in shelf.json.
     * This handles incomplete copies from app crashes or storage issues.
     */
    private suspend fun cleanupOrphanedFiles() = withContext(Dispatchers.IO) {
        try {
            val shelfDir = FileShelfRepository.shelfDir(this@FileShelfApp)
            val trackedFiles = FileShelfRepository.loadSync(this@FileShelfApp)
                .map { File(it.localPath).name }
                .toSet()
            
            shelfDir.listFiles()?.forEach { file ->
                // Skip the metadata JSON file and all tracked content files.
                // .tmp files ARE safe to delete here: they are transient atomic-write
                // artifacts. If we reach this cleanup, the rename already completed or
                // failed — a leftover .tmp is always garbage and should be removed to
                // prevent storage accumulation on crash-prone devices.
                if (file.name != "shelf.json" && !trackedFiles.contains(file.name)) {
                    val deleted = file.delete()
                    if (deleted) {
                        android.util.Log.i("FileShelfApp", "Cleaned up orphaned file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileShelfApp", "Failed to cleanup orphaned files", e)
        }
    }

    companion object {
        // SAFETY: Use nullable + synchronized access to prevent crashes
        @Volatile
        private var _instance: FileShelfApp? = null
        
        val instance: FileShelfApp
            get() = _instance ?: throw IllegalStateException(
                "FileShelfApp not initialized. This is a critical bug - onCreate() was not called."
            )
        
        /**
         * Safe accessor that returns null if app not initialized yet.
         * Use this when unsure if Application.onCreate() has completed.
         */
        fun instanceOrNull(): FileShelfApp? = _instance
    }
}
