package com.pranav.fileshelf

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: MainActivity launches and reaches RESUMED state without
 * crashing. We don't drive Compose UI here — that's a deeper Espresso/Compose
 * test investment we deliberately deferred. This protects against the most
 * common regression class: app crashes immediately on launch.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test fun launches_withoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }
}
