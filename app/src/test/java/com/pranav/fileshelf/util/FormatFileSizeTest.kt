package com.pranav.fileshelf.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Pure JVM tests for [formatFileSize]. No Android framework, no Robolectric.
 *
 * `formatFileSize` uses `String.format` without an explicit locale, so a
 * locale that uses comma decimal separators (e.g. de_DE) would produce
 * "1,5 KB". Force US locale at fixture time so assertions are stable.
 */
class FormatFileSizeTest {

    private val originalLocale: Locale = Locale.getDefault()

    @org.junit.Before fun setLocale() { Locale.setDefault(Locale.US) }
    @org.junit.After  fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun `zero bytes`() {
        assertThat(formatFileSize(0)).isEqualTo("0 B")
    }

    @Test fun `bytes just below 1 KB stay in bytes`() {
        assertThat(formatFileSize(1023)).isEqualTo("1023 B")
    }

    @Test fun `exactly 1024 bytes formats as 1_0 KB`() {
        assertThat(formatFileSize(1024)).isEqualTo("1.0 KB")
    }

    @Test fun `1_5 KB`() {
        assertThat(formatFileSize(1536)).isEqualTo("1.5 KB")
    }

    @Test fun `1 MB boundary`() {
        assertThat(formatFileSize(1024L * 1024)).isEqualTo("1.0 MB")
    }

    @Test fun `1 GB boundary`() {
        assertThat(formatFileSize(1024L * 1024 * 1024)).isEqualTo("1.0 GB")
    }

    @Test fun `large GB value`() {
        assertThat(formatFileSize(5L * 1024 * 1024 * 1024)).isEqualTo("5.0 GB")
    }
}
