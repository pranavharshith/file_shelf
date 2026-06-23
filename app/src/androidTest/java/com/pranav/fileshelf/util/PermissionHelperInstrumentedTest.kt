package com.pranav.fileshelf.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for SharedPreferences-backed flags in [PermissionHelper].
 * We use the real on-device SharedPreferences since that's the integration we
 * actually rely on.
 */
@RunWith(AndroidJUnit4::class)
class PermissionHelperInstrumentedTest {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Ensure clean prefs at the start of each test.
        ctx.getSharedPreferences("file_shelf_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        ctx.getSharedPreferences("file_shelf_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun floatingShelfFlag_defaultsFalse_andRoundTrips() {
        assertThat(PermissionHelper.isFloatingShelfEnabled(ctx)).isFalse()
        PermissionHelper.setFloatingShelfEnabled(ctx, true)
        assertThat(PermissionHelper.isFloatingShelfEnabled(ctx)).isTrue()
        PermissionHelper.setFloatingShelfEnabled(ctx, false)
        assertThat(PermissionHelper.isFloatingShelfEnabled(ctx)).isFalse()
    }

    @Test fun onboarding_setAndClear() {
        assertThat(PermissionHelper.isOnboardingComplete(ctx)).isFalse()
        PermissionHelper.setOnboardingComplete(ctx)
        assertThat(PermissionHelper.isOnboardingComplete(ctx)).isTrue()
        PermissionHelper.clearOnboarding(ctx)
        assertThat(PermissionHelper.isOnboardingComplete(ctx)).isFalse()
    }

    @Test fun dragHintSeen_isSticky() {
        assertThat(PermissionHelper.hasSeenDragHint(ctx)).isFalse()
        PermissionHelper.setDragHintSeen(ctx)
        assertThat(PermissionHelper.hasSeenDragHint(ctx)).isTrue()
    }

    @Test fun overlaySettingsIntent_targetsThisPackage() {
        val intent = PermissionHelper.overlaySettingsIntent(ctx)
        assertThat(intent.data?.toString())
            .isEqualTo("package:${ctx.packageName}")
    }

    @Test fun canStartOverlay_falseUntilFlagsAreOn() {
        // Without floatingShelfEnabled, canStartOverlay must be false even if
        // the OS has granted overlay permission.
        PermissionHelper.setFloatingShelfEnabled(ctx, false)
        assertThat(PermissionHelper.canStartOverlay(ctx)).isFalse()
    }
}
