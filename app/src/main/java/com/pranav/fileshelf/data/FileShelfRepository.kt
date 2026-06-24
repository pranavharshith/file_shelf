package com.pranav.fileshelf.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File


object FileShelfRepository {

    const val MAX_ITEMS = 20
    const val MAX_FILE_BYTES = 500L * 1024 * 1024
    const val TTL_MS = 24L * 60 * 60 * 1000

    private val writeMutex = Mutex()
    private val _files = MutableStateFlow<List<StagedFile>>(emptyList())
    val files: StateFlow<List<StagedFile>> = _files.asStateFlow()

    private val _pendingCopies = MutableStateFlow<List<PendingCopy>>(emptyList())
    val pendingCopies: StateFlow<List<PendingCopy>> = _pendingCopies.asStateFlow()

    /**
     * Refcount of in-flight "large" copies — files where the on-disk write
     * is slow enough that the user would otherwise stare at a stale bubble
     * count wondering if anything is happening. Drives the bubble's loading
     * spinner. Refcount (not boolean) so concurrent large copies appear as
     * one continuous spinner instead of flickering per file.
     */
    private val _largeCopiesActive = MutableStateFlow(0)
    val largeCopiesActive: StateFlow<Int> = _largeCopiesActive.asStateFlow()

    fun incrementLargeCopy() {
        _largeCopiesActive.value = _largeCopiesActive.value + 1
    }

    fun decrementLargeCopy() {
        _largeCopiesActive.value = (_largeCopiesActive.value - 1).coerceAtLeast(0)
    }

    fun shelfDir(context: Context): File {
        val dir = File(context.cacheDir, "shelf")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun shelfJsonFile(context: Context): File =
        File(shelfDir(context), "shelf.json")

    fun createDestFile(context: Context, displayName: String): File {
        // Input validation: Prevent crashes from malformed filenames
        if (displayName.isBlank()) {
            return File(shelfDir(context), "file_${System.currentTimeMillis()}")
        }
        
        // Limit filename length (255 is typical filesystem limit, use 200 to be safe)
        val truncatedName = if (displayName.length > 200) {
            val extension = displayName.substringAfterLast('.', "")
            val baseName = displayName.substringBeforeLast('.').take(190)
            if (extension.isNotEmpty()) "$baseName.$extension" else baseName
        } else {
            displayName
        }
        
        // Strip any characters not safe for a filename
        val safeName = truncatedName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = shelfDir(context)

        // Try the original name first; if taken, add (1), (2), etc. before the extension.
        val dotIndex = safeName.lastIndexOf('.')
        val base = if (dotIndex >= 0) safeName.substring(0, dotIndex) else safeName
        val ext  = if (dotIndex >= 0) safeName.substring(dotIndex) else ""   // includes the dot

        var candidate = File(dir, safeName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($counter)$ext")
            counter++
        }
        return candidate
    }

    suspend fun refresh(context: Context) {
        writeMutex.withLock {
            // Load on IO dispatcher to prevent ANR on main thread
            val items = withContext(Dispatchers.IO) {
                loadInternal(context)
            }
            _files.value = items
        }
    }

    fun loadSync(context: Context): List<StagedFile> = loadInternal(context)

    suspend fun add(context: Context, file: StagedFile) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val shelfDirectory = shelfDir(context)
            val list = loadInternal(context).toMutableList()
            while (list.size >= MAX_ITEMS) {
                removeOldestEntry(list, shelfDirectory)
            }
            list.add(file)
            saveAtomic(context, list)
            _files.value = list
        }
    }

    suspend fun remove(context: Context, id: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadInternal(context).toMutableList()
            val target = list.find { it.id == id } ?: return@withContext
            
            // SECURITY: Validate file path is within app's shelf directory before deletion
            val fileToDelete = File(target.localPath)
            val shelfDirectory = shelfDir(context)
            if (isPathWithinDirectory(fileToDelete, shelfDirectory)) {
                fileToDelete.delete()
            } else {
                // Log suspicious path traversal attempt
                android.util.Log.w("FileShelfRepository", "Rejected deletion attempt outside shelf dir: ${target.localPath}")
            }
            
            list.removeAll { it.id == id }
            saveAtomic(context, list)
            _files.value = list
        }
    }

    suspend fun clearAll(context: Context) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadInternal(context)
            val shelfDirectory = shelfDir(context)
            
            // SECURITY: Only delete files within shelf directory
            list.forEach { 
                val fileToDelete = File(it.localPath)
                if (isPathWithinDirectory(fileToDelete, shelfDirectory)) {
                    fileToDelete.delete()
                }
            }
            
            saveAtomic(context, emptyList())
            _files.value = emptyList()
        }
    }

    suspend fun findByNameAndSize(context: Context, name: String, size: Long?): StagedFile? {
        if (size == null || size <= 0) return null
        return loadInternal(context).find { it.displayName == name && it.sizeBytes == size }
    }

    suspend fun findByHash(context: Context, hash: String): StagedFile? =
        loadInternal(context).find { it.sha256 == hash }

    suspend fun cleanupExpired(context: Context) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val all = loadInternal(context)
            val list = all.filter { now - it.addedAt < TTL_MS }.toMutableList()
            val removed = all.filter { now - it.addedAt >= TTL_MS }
            val shelfDirectory = shelfDir(context)

            removed.forEach {
                val fileToDelete = File(it.localPath)
                if (isPathWithinDirectory(fileToDelete, shelfDirectory)) {
                    fileToDelete.delete()
                }
            }

            saveAtomic(context, list)
            _files.value = list
        }
    }

    fun addPendingCopy(pending: PendingCopy) {
        _pendingCopies.value = _pendingCopies.value + pending
    }

    fun removePendingCopy(id: String) {
        _pendingCopies.value = _pendingCopies.value.filter { it.id != id }
    }

    private fun loadInternal(context: Context): List<StagedFile> {
        val file = shelfJsonFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val root = JSONObject(file.readText())
            val items = root.optJSONArray("items") ?: JSONArray()
            val parsed = (0 until items.length()).mapNotNull { index ->
                parseItem(items.getJSONObject(index))
            }
            val valid = parsed.filter { File(it.localPath).exists() }
            if (valid.size != parsed.size) {
                saveAtomicSync(context, valid)
            }
            valid
        } catch (e: Exception) {
            android.util.Log.e("FileShelfRepository", "Failed to load shelf data", e)
            emptyList()
        }
    }

    private fun parseItem(obj: JSONObject): StagedFile? = try {
        StagedFile(
            id = obj.getString("id"),
            displayName = obj.getString("displayName"),
            mimeType = obj.getString("mimeType"),
            localPath = obj.getString("localPath"),
            sizeBytes = obj.getLong("sizeBytes"),
            sha256 = obj.getString("sha256"),
            addedAt = obj.getLong("addedAt")
        )
    } catch (e: Exception) {
        android.util.Log.w("FileShelfRepository", "Failed to parse item from JSON", e)
        null
    }

    private fun saveAtomic(context: Context, items: List<StagedFile>) {
        saveAtomicSync(context, items)
    }

    private fun saveAtomicSync(context: Context, items: List<StagedFile>) {
        val file = shelfJsonFile(context)
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeText(serialize(items))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun serialize(items: List<StagedFile>): String {
        val root = JSONObject()
        root.put("version", 1)
        val array = JSONArray()
        items.forEach { file ->
            array.put(
                JSONObject().apply {
                    put("id", file.id)
                    put("displayName", file.displayName)
                    put("mimeType", file.mimeType)
                    put("localPath", file.localPath)
                    put("sizeBytes", file.sizeBytes)
                    put("sha256", file.sha256)
                    put("addedAt", file.addedAt)
                }
            )
        }
        root.put("items", array)
        return root.toString()
    }

    private fun removeOldestEntry(list: MutableList<StagedFile>, shelfDirectory: File) {
        if (list.isEmpty()) return
        val oldest = list.minByOrNull { it.addedAt } ?: return

        // Use the same canonical-path guard as every other deletion site in
        // this file. The old string.contains("/shelf/") check was bypassable
        // by a path like "/data/user/0/com.attacker/shelf/../../../etc/file".
        val fileToDelete = File(oldest.localPath)
        if (isPathWithinDirectory(fileToDelete, shelfDirectory)) {
            fileToDelete.delete()
        } else {
            android.util.Log.w(
                "FileShelfRepository",
                "Rejected pruning of file outside shelf dir: ${oldest.localPath}"
            )
        }

        list.removeAll { it.id == oldest.id }
    }
    
    /**
     * Security helper: Validates that a file path is within the allowed directory.
     * Prevents path traversal attacks (e.g., ../../etc/passwd).
     */
    private fun isPathWithinDirectory(file: File, directory: File): Boolean {
        return try {
            val canonicalFile = file.canonicalPath
            val canonicalDir = directory.canonicalPath
            canonicalFile.startsWith(canonicalDir)
        } catch (e: Exception) {
            android.util.Log.e("FileShelfRepository", "Path validation failed", e)
            false
        }
    }
}
