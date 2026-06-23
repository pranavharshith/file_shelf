package com.pranav.fileshelf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Repository tests using a real (Robolectric-backed) Context and the actual
 * JSON serialization path. Catches field reorderings and schema regressions.
 *
 * [FileShelfRepository] is a singleton `object`; each test wipes shelfDir
 * and calls [FileShelfRepository.refresh] (which updates `_files` on the
 * calling dispatcher — no `withContext(Main)`) so the StateFlow is reset
 * without touching the Android main looper.
 *
 * All `@Test` methods use BLOCK bodies around `runBlocking` — NOT expression
 * bodies — because `fun test() = runBlocking { }` compiles to a JVM method
 * returning `kotlin.Unit` (the object), whereas JUnit4 requires `void`.
 * Block bodies (`fun test() { runBlocking { } }`) compile to void.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FileShelfRepositoryTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FileShelfRepository.shelfDir(context).listFiles()?.forEach { it.delete() }
        runBlocking { FileShelfRepository.refresh(context) }
    }

    @After fun tearDown() {
        FileShelfRepository.shelfDir(context).listFiles()?.forEach { it.delete() }
        runBlocking { FileShelfRepository.refresh(context) }
    }

    private fun stageFile(
        id: String = "id1",
        name: String = "a.txt",
        bytes: Long = 100,
        addedAt: Long = System.currentTimeMillis()
    ): StagedFile {
        val file = File(FileShelfRepository.shelfDir(context), name)
        file.writeText("x".repeat(bytes.coerceAtMost(1_000).toInt()))
        return StagedFile(
            id = id,
            displayName = name,
            mimeType = "text/plain",
            localPath = file.absolutePath,
            sizeBytes = bytes,
            sha256 = "hash-$id",
            addedAt = addedAt
        )
    }

    @Test fun `add persists to JSON and emits in flow`() {
        runBlocking {
            FileShelfRepository.add(context, stageFile())
            assertThat(FileShelfRepository.loadSync(context)).hasSize(1)
            assertThat(FileShelfRepository.loadSync(context)[0].id).isEqualTo("id1")
            assertThat(FileShelfRepository.files.value).hasSize(1)
        }
    }

    @Test fun `remove deletes local file and clears from flow`() {
        runBlocking {
            val f = stageFile()
            FileShelfRepository.add(context, f)
            assertThat(File(f.localPath).exists()).isTrue()

            FileShelfRepository.remove(context, f.id)

            assertThat(File(f.localPath).exists()).isFalse()
            assertThat(FileShelfRepository.files.value).isEmpty()
        }
    }

    @Test fun `clearAll empties files flow and JSON`() {
        runBlocking {
            FileShelfRepository.add(context, stageFile(id = "1", name = "a.txt"))
            FileShelfRepository.add(context, stageFile(id = "2", name = "b.txt"))
            FileShelfRepository.clearAll(context)
            assertThat(FileShelfRepository.files.value).isEmpty()
            assertThat(FileShelfRepository.loadSync(context)).isEmpty()
        }
    }

    @Test fun `add prunes oldest entry when MAX_ITEMS exceeded`() {
        runBlocking {
            repeat(FileShelfRepository.MAX_ITEMS) { i ->
                FileShelfRepository.add(
                    context, stageFile(id = "id$i", name = "f$i.txt", addedAt = i.toLong())
                )
            }
            assertThat(FileShelfRepository.files.value).hasSize(FileShelfRepository.MAX_ITEMS)

            FileShelfRepository.add(
                context, stageFile(id = "newest", name = "newest.txt", addedAt = 99_999L)
            )

            val ids = FileShelfRepository.files.value.map { it.id }
            assertThat(ids).contains("newest")
            assertThat(ids).doesNotContain("id0")
            assertThat(ids).hasSize(FileShelfRepository.MAX_ITEMS)
        }
    }

    @Test fun `findByNameAndSize returns null when size is null`() {
        runBlocking {
            assertThat(FileShelfRepository.findByNameAndSize(context, "x", null)).isNull()
        }
    }

    @Test fun `findByNameAndSize requires both name and size to match`() {
        runBlocking {
            FileShelfRepository.add(context, stageFile(id = "id1", name = "match.txt", bytes = 42))
            assertThat(FileShelfRepository.findByNameAndSize(context, "match.txt", 42)?.id)
                .isEqualTo("id1")
            assertThat(FileShelfRepository.findByNameAndSize(context, "match.txt", 99)).isNull()
            assertThat(FileShelfRepository.findByNameAndSize(context, "other.txt", 42)).isNull()
        }
    }

    @Test fun `findByHash returns matching entry`() {
        runBlocking {
            FileShelfRepository.add(context, stageFile(id = "id1"))
            assertThat(FileShelfRepository.findByHash(context, "hash-id1")?.id).isEqualTo("id1")
            assertThat(FileShelfRepository.findByHash(context, "wrong-hash")).isNull()
        }
    }

    @Test fun `cleanupExpired drops entries older than TTL_MS`() {
        runBlocking {
            val now = System.currentTimeMillis()
            FileShelfRepository.add(
                context,
                stageFile(id = "old", name = "old.txt",
                    addedAt = now - FileShelfRepository.TTL_MS - 1_000L)
            )
            FileShelfRepository.add(
                context,
                stageFile(id = "new", name = "new.txt", addedAt = now)
            )
            FileShelfRepository.cleanupExpired(context)
            assertThat(FileShelfRepository.files.value.map { it.id }).containsExactly("new")
        }
    }

    @Test fun `pendingCopies flow reflects add and remove`() {
        val pending = PendingCopy(id = "p1", displayName = "x.txt")
        FileShelfRepository.addPendingCopy(pending)
        assertThat(FileShelfRepository.pendingCopies.value).hasSize(1)
        FileShelfRepository.removePendingCopy("p1")
        assertThat(FileShelfRepository.pendingCopies.value).isEmpty()
    }

    @Test fun `loadSync drops entries whose backing file is missing`() {
        runBlocking {
            val f = stageFile(id = "ghost", name = "ghost.txt")
            FileShelfRepository.add(context, f)
            File(f.localPath).delete()
            assertThat(FileShelfRepository.loadSync(context)).isEmpty()
        }
    }

    @Test fun `createDestFile sanitizes path-traversal characters`() {
        val dest = FileShelfRepository.createDestFile(context, "../../etc/passwd.txt")
        assertThat(dest.parentFile?.canonicalPath)
            .isEqualTo(FileShelfRepository.shelfDir(context).canonicalPath)
        assertThat(dest.name).doesNotContain("/")
        assertThat(dest.name).doesNotContain("\\")
    }

    @Test fun `createDestFile gives fallback name for blank input`() {
        val dest = FileShelfRepository.createDestFile(context, "   ")
        assertThat(dest.name).startsWith("file_")
    }

    @Test fun `createDestFile suffixes duplicate names`() {
        val dir = FileShelfRepository.shelfDir(context)
        File(dir, "doc.pdf").writeText("first")
        val second = FileShelfRepository.createDestFile(context, "doc.pdf")
        assertThat(second.name).isEqualTo("doc(1).pdf")
        second.writeText("second")
        val third = FileShelfRepository.createDestFile(context, "doc.pdf")
        assertThat(third.name).isEqualTo("doc(2).pdf")
    }
}
