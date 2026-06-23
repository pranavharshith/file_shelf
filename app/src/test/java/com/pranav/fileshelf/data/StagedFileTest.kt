package com.pranav.fileshelf.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Smoke tests for the data class. Catches accidental field reordering or
 * `equals`/`copy` regressions if the type is ever migrated to a non-data class.
 */
class StagedFileTest {

    private val sample = StagedFile(
        id = "abc",
        displayName = "report.pdf",
        mimeType = "application/pdf",
        localPath = "/data/user/0/x/cache/shelf/report.pdf",
        sizeBytes = 12345,
        sha256 = "deadbeef",
        addedAt = 1_700_000_000_000L
    )

    @Test fun `equality is value-based`() {
        assertThat(sample).isEqualTo(sample.copy())
    }

    @Test fun `copy with new id breaks equality`() {
        assertThat(sample).isNotEqualTo(sample.copy(id = "xyz"))
    }

    @Test fun `copy preserves untouched fields`() {
        val renamed = sample.copy(displayName = "report-v2.pdf")
        assertThat(renamed.id).isEqualTo(sample.id)
        assertThat(renamed.sha256).isEqualTo(sample.sha256)
        assertThat(renamed.displayName).isEqualTo("report-v2.pdf")
    }
}
