package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EpgSyncControllerTest {

    private lateinit var context: Context
    private lateinit var scheduler: FakeScheduler
    private lateinit var controller: EpgSyncController

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        scheduler = FakeScheduler()
        controller = EpgSyncController(scheduler)
    }

    @Test
    fun `when auto sync enabled schedules with provided interval`() {
        val options = EpgSyncOptions(
            enabled = true,
            intervalHours = 12L,
            wifiOnly = true,
            batteryNotLow = false
        )

        controller.applyOptions(context, options)

        assertTrue("schedulePeriodicSync should be called", scheduler.scheduleCalled)
        assertEquals(12L, scheduler.intervalHours)
        assertTrue(scheduler.wifiOnly)
        assertFalse("battery constraint should be disabled", scheduler.batteryNotLow)
        assertFalse("cancelSync should not run", scheduler.cancelCalled)
    }

    @Test
    fun `when auto sync disabled cancels existing work`() {
        val options = EpgSyncOptions(
            enabled = false,
            intervalHours = 6L,
            wifiOnly = true,
            batteryNotLow = true
        )

        controller.applyOptions(context, options)

        assertTrue("cancelSync should be called", scheduler.cancelCalled)
        assertFalse("schedulePeriodicSync should not run", scheduler.scheduleCalled)
    }

    private class FakeScheduler : EpgSyncSchedulerDelegate {
        var scheduleCalled = false
        var cancelCalled = false
        var intervalHours: Long = -1
        var wifiOnly: Boolean = false
        var batteryNotLow: Boolean = false

        override fun schedulePeriodicSync(
            context: Context,
            intervalHours: Long,
            wifiOnly: Boolean,
            batteryNotLow: Boolean
        ) {
            scheduleCalled = true
            this.intervalHours = intervalHours
            this.wifiOnly = wifiOnly
            this.batteryNotLow = batteryNotLow
        }

        override fun cancelSync(context: Context) {
            cancelCalled = true
        }
    }
}
