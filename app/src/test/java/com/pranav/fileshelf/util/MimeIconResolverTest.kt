package com.pranav.fileshelf.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the pure `emojiFor` branch of [MimeIconResolver].
 * `chipColorRes` references R.color and is covered by Robolectric tests.
 */
class MimeIconResolverTest {

    @Test fun `image mime types resolve to IMG`() {
        assertThat(MimeIconResolver.emojiFor("image/jpeg")).isEqualTo("IMG")
        assertThat(MimeIconResolver.emojiFor("image/png")).isEqualTo("IMG")
        assertThat(MimeIconResolver.emojiFor("image/heic")).isEqualTo("IMG")
    }

    @Test fun `video mime types resolve to VID`() {
        assertThat(MimeIconResolver.emojiFor("video/mp4")).isEqualTo("VID")
    }

    @Test fun `audio mime types resolve to AUD`() {
        assertThat(MimeIconResolver.emojiFor("audio/mpeg")).isEqualTo("AUD")
    }

    @Test fun `pdf is matched anywhere in the mime string`() {
        assertThat(MimeIconResolver.emojiFor("application/pdf")).isEqualTo("PDF")
    }

    @Test fun `office document mime types resolve to DOC`() {
        assertThat(MimeIconResolver.emojiFor("application/msword")).isEqualTo("DOC")
        assertThat(MimeIconResolver.emojiFor(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )).isEqualTo("DOC")
    }

    @Test fun `legacy excel mime type resolves to XLS`() {
        // "application/vnd.ms-excel" contains "excel" → XLS branch fires correctly.
        assertThat(MimeIconResolver.emojiFor("application/vnd.ms-excel")).isEqualTo("XLS")
    }

    /**
     * Known false-positive in [MimeIconResolver.emojiFor]:
     * "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" contains
     * the substring "document" (inside "officedocument"), so the `when` chain matches
     * the DOC branch before it reaches the XLS branch. The function returns "DOC"
     * instead of "XLS". This test documents the actual behaviour so any future fix
     * to the production code is immediately visible here.
     */
    @Test fun `ooxml spreadsheet false-positive returns DOC not XLS`() {
        assertThat(MimeIconResolver.emojiFor(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )).isEqualTo("DOC")
    }

    @Test fun `archive mime types resolve to ZIP`() {
        assertThat(MimeIconResolver.emojiFor("application/zip")).isEqualTo("ZIP")
        assertThat(MimeIconResolver.emojiFor("application/x-archive")).isEqualTo("ZIP")
    }

    @Test fun `text mime types resolve to TXT`() {
        assertThat(MimeIconResolver.emojiFor("text/plain")).isEqualTo("TXT")
        assertThat(MimeIconResolver.emojiFor("text/csv")).isEqualTo("TXT")
    }

    @Test fun `unknown mime type falls back to FILE`() {
        assertThat(MimeIconResolver.emojiFor("application/octet-stream")).isEqualTo("FILE")
        assertThat(MimeIconResolver.emojiFor("")).isEqualTo("FILE")
    }
}
