package com.pranav.fileshelf

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the static [ShareReceiverActivity.extractShareUris] companion.
 * This is the public-facing intent contract for File Shelf — the single
 * highest-leverage test target in the app, because every share entry
 * (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`, with or without ClipData) flows
 * through this method. Bug here = silent data loss for the user.
 *
 * Robolectric is required because Intent / ClipData / Uri are framework types,
 * but we don't need an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareReceiverExtractUrisTest {

    private val uriA: Uri = Uri.parse("content://example/a.txt")
    private val uriB: Uri = Uri.parse("content://example/b.png")

    private fun sendIntent(stream: Uri?, type: String? = "text/plain"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            if (stream != null) putExtra(Intent.EXTRA_STREAM, stream)
        }

    @Test fun `empty intent yields empty list`() {
        val result = ShareReceiverActivity.extractShareUris(Intent(Intent.ACTION_SEND))
        assertThat(result).isEmpty()
    }

    @Test fun `single ACTION_SEND with EXTRA_STREAM extracts one uri`() {
        val result = ShareReceiverActivity.extractShareUris(sendIntent(uriA))
        assertThat(result).hasSize(1)
        assertThat(result[0].first).isEqualTo(uriA)
        assertThat(result[0].second).isEqualTo("text/plain")
    }

    @Test fun `ACTION_SEND_MULTIPLE with parcelable list extracts all uris`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uriA, uriB))
        }
        val result = ShareReceiverActivity.extractShareUris(intent)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.first }).containsExactly(uriA, uriB).inOrder()
        assertThat(result[0].second).isEqualTo("image/*")
    }

    @Test fun `ClipData uris are extracted and de-duplicated against EXTRA_STREAM`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uriA)
            clipData = ClipData(
                ClipDescription("share", arrayOf("image/png")),
                ClipData.Item(uriA)
            ).apply { addItem(ClipData.Item(uriB)) }
        }
        val result = ShareReceiverActivity.extractShareUris(intent)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.first }).containsExactly(uriA, uriB).inOrder()
    }

    @Test fun `null type is propagated as second-of-pair`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uriA)
        }
        val result = ShareReceiverActivity.extractShareUris(intent)
        assertThat(result).hasSize(1)
        assertThat(result[0].second).isNull()
    }

    @Test fun `duplicate uris across all sources collapse to one`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uriA)
            clipData = ClipData(
                ClipDescription("share", arrayOf("text/plain")),
                ClipData.Item(uriA)
            )
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uriA))
        }
        val result = ShareReceiverActivity.extractShareUris(intent)
        assertThat(result).hasSize(1)
        assertThat(result[0].first).isEqualTo(uriA)
    }
}
