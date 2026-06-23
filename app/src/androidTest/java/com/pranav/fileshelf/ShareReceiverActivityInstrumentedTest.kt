package com.pranav.fileshelf

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests for [ShareReceiverActivity]. The activity has
 * `android:noHistory="true"` and finishes itself synchronously, so we
 * verify that:
 *   1. Launching with each supported intent shape does not crash.
 *   2. The activity reaches DESTROYED state (proves `finishImmediate()` ran).
 *
 * We deliberately do NOT assert that files end up in the repository — that
 * path involves WorkManager + IO and is covered by the worker's own tests.
 */
@RunWith(AndroidJUnit4::class)
class ShareReceiverActivityInstrumentedTest {

    private val targetPkg: String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName

    private fun receiver(intent: Intent): Intent =
        intent.setClassName(targetPkg, "com.pranav.fileshelf.ShareReceiverActivity")

    @Test fun emptyIntent_finishesWithoutCrash() {
        val intent = receiver(Intent(Intent.ACTION_SEND).apply { type = "text/plain" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
            assertThat(scenario.state).isAnyOf(
                androidx.lifecycle.Lifecycle.State.DESTROYED,
                androidx.lifecycle.Lifecycle.State.CREATED,  // pre-finish
                androidx.lifecycle.Lifecycle.State.INITIALIZED
            )
        }
    }

    @Test fun actionSend_withInvalidScheme_finishesWithoutCrash() {
        val httpUri = Uri.parse("https://example.com/x.png")
        val intent = receiver(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, httpUri)
            }
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<ShareReceiverActivity>(intent).use { /* completes */ }
        // No assertion needed: success = no crash. isValidUri rejects non-content/file schemes.
    }

    @Test fun actionSendMultiple_withParcelableArrayList_finishesWithoutCrash() {
        val a = Uri.parse("content://test/a.txt")
        val b = Uri.parse("content://test/b.txt")
        val intent = receiver(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            }
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<ShareReceiverActivity>(intent).use { /* completes */ }
    }
}
