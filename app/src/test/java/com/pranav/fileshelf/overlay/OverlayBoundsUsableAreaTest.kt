package com.pranav.fileshelf.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-math tests for [OverlayBounds.UsableArea]. No Context required —
 * we construct the data class directly. The `usableArea(Context)` factory
 * is exercised in instrumented tests where a real WindowManager exists.
 */
class OverlayBoundsUsableAreaTest {

    private val area = OverlayBounds.UsableArea(
        left = 0, top = 0, right = 1080, bottom = 1920
    )

    // ---------- clampX ----------

    @Test fun `clampX leaves x unchanged when fully inside`() {
        assertThat(area.clampX(x = 500, viewWidth = 100)).isEqualTo(500)
    }

    @Test fun `clampX clamps to left edge when negative`() {
        assertThat(area.clampX(x = -50, viewWidth = 100)).isEqualTo(0)
    }

    @Test fun `clampX clamps so view fits inside right edge`() {
        // viewWidth=100 means max x = 1080-100 = 980
        assertThat(area.clampX(x = 1500, viewWidth = 100)).isEqualTo(980)
    }

    @Test fun `clampX never returns a value smaller than left when view is wider than area`() {
        // If viewWidth > width, max would be negative; coerceAtLeast(left) protects.
        assertThat(area.clampX(x = 0, viewWidth = 5000)).isEqualTo(0)
    }

    // ---------- clampY ----------

    @Test fun `clampY clamps to top when negative`() {
        assertThat(area.clampY(y = -10, viewHeight = 50)).isEqualTo(0)
    }

    @Test fun `clampY clamps so view fits inside bottom edge`() {
        assertThat(area.clampY(y = 5000, viewHeight = 100)).isEqualTo(1820)
    }

    // ---------- isOnScreen ----------

    @Test fun `isOnScreen true for fully inside rect`() {
        assertThat(area.isOnScreen(100, 100, 50, 50)).isTrue()
    }

    @Test fun `isOnScreen false when extending past right edge`() {
        assertThat(area.isOnScreen(1050, 100, 100, 50)).isFalse()
    }

    @Test fun `isOnScreen false when y is negative`() {
        assertThat(area.isOnScreen(100, -1, 50, 50)).isFalse()
    }

    // ---------- dismiss zone ----------

    @Test fun `dismissZoneTop is at 82 percent of height`() {
        // height = 1920, dismiss zone starts at 1920 * 0.82 = 1574 (rounded down)
        assertThat(area.dismissZoneTop()).isEqualTo(1574)
    }

    @Test fun `isInDismissZone true at and below dismiss zone top`() {
        assertThat(area.isInDismissZone(1574)).isTrue()
        assertThat(area.isInDismissZone(1900)).isTrue()
    }

    @Test fun `isInDismissZone false above dismiss zone top`() {
        assertThat(area.isInDismissZone(1573)).isFalse()
        assertThat(area.isInDismissZone(0)).isFalse()
    }

    // ---------- non-zero left/top offsets (insets case) ----------

    @Test fun `clampX respects non-zero left offset (status-bar inset case)`() {
        val inset = OverlayBounds.UsableArea(left = 50, top = 100, right = 1130, bottom = 2020)
        assertThat(inset.clampX(x = 0, viewWidth = 100)).isEqualTo(50)
        assertThat(inset.clampX(x = 9999, viewWidth = 100)).isEqualTo(1030)
    }

    @Test fun `width and height computed from corners`() {
        val inset = OverlayBounds.UsableArea(left = 50, top = 100, right = 1130, bottom = 2020)
        assertThat(inset.width).isEqualTo(1080)
        assertThat(inset.height).isEqualTo(1920)
    }
}
