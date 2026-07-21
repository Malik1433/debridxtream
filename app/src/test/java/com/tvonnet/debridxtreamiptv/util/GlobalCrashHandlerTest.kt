package com.tvonnet.debridxtreamiptv.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.ui.MainActivity
import com.tvonnet.debridxtreamiptv.ui.recovery.RecoveryActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phase 8: REAL crash-handler behaviour test. The previous version mocked SharedPreferences and
 * verified `editor.apply()` — but Phase 0 changed the handler to `.commit()` (durable write before
 * killProcess), so that stale assertion failed on every run. Under Robolectric this uses a real
 * SharedPreferences + a real Application, and asserts the actual outcome: the crash count is
 * persisted and the correct recovery Activity is launched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GlobalCrashHandlerTest {

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var handler: GlobalCrashHandler

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs = app.getSharedPreferences("app_stability", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        handler = GlobalCrashHandler(app)
        // Neutralise the process kill so the test JVM survives.
        handler.exitLogic = {}
    }

    @Test
    fun `first crash increments count to 1 and restarts MainActivity`() {
        handler.uncaughtException(Thread.currentThread(), RuntimeException("crash 1"))

        assertEquals(1, prefs.getInt("startup_crash_count", 0))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun `second crash increments to 2 and still restarts MainActivity`() {
        prefs.edit().putInt("startup_crash_count", 1).commit()

        handler.uncaughtException(Thread.currentThread(), RuntimeException("crash 2"))

        assertEquals(2, prefs.getInt("startup_crash_count", 0))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun `third crash increments to 3 and launches RecoveryActivity`() {
        prefs.edit().putInt("startup_crash_count", 2).commit()

        handler.uncaughtException(Thread.currentThread(), RuntimeException("crash 3"))

        assertEquals(3, prefs.getInt("startup_crash_count", 0))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(RecoveryActivity::class.java.name, started?.component?.className)
    }
}
