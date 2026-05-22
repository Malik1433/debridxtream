package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class GlobalCrashHandlerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var handler: GlobalCrashHandler

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkConstructor(Intent::class)

        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences("app_stability", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor

        handler = GlobalCrashHandler(context)
        // Neutralize the process-killing logic so the test execution is not aborted
        handler.exitLogic = {}
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testFirstCrashRestartsMainActivity() {
        // Given startup crash count is 0
        every { sharedPreferences.getInt("startup_crash_count", 0) } returns 0

        // When uncaughtException is invoked
        handler.uncaughtException(Thread.currentThread(), RuntimeException("Test crash 1"))

        // Then startup_crash_count is incremented to 1
        verify { editor.putInt("startup_crash_count", 1) }
        verify { editor.apply() }

        // And an activity is started
        verify { context.startActivity(any()) }
    }

    @Test
    fun testThirdCrashRestartsRecoveryActivity() {
        // Given startup crash count is 2 (this will be the 3rd crash)
        every { sharedPreferences.getInt("startup_crash_count", 0) } returns 2

        // When uncaughtException is invoked
        handler.uncaughtException(Thread.currentThread(), RuntimeException("Test crash 3"))

        // Then startup_crash_count is incremented to 3
        verify { editor.putInt("startup_crash_count", 3) }
        verify { editor.apply() }

        // And an activity is started
        verify { context.startActivity(any()) }
    }
}
