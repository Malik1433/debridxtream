package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4 — which categories say "this cannot work yet", and which rows survive that.
 *
 * The rules matter more than they look: get `liveKeys` wrong and a row the customer CAN use is
 * dimmed and unclickable, which is a worse defect than the dead tap this replaces. So each blocked
 * state pins both halves — that it fires, and exactly what stays live.
 *
 * The strings are irrelevant to the rules, so the Context only has to return something.
 */
class SettingsUnavailableTest {

    private val context: Context = mockk(relaxed = true) {
        every { getString(any()) } returns "text"
    }

    private fun blocked(
        category: SettingCategory,
        hasServer: Boolean,
        hasCatalogue: Boolean,
    ) = SettingsUnavailable.of(context, category, hasServer, hasCatalogue)

    @Test
    fun `a synced server blocks nothing`() {
        SettingCategory.values().forEach { category ->
            assertNull(
                "$category should not be blocked when everything is present",
                blocked(category, hasServer = true, hasCatalogue = true),
            )
        }
    }

    @Test
    fun `without a server Live TV, Home and Data are blocked and nothing else is`() {
        val blockedCategories = SettingCategory.values()
            .filter { blocked(it, hasServer = false, hasCatalogue = false) != null }
            .toSet()
        assertEquals(
            setOf(SettingCategory.LIVE_TV, SettingCategory.HOME, SettingCategory.DATA),
            blockedCategories,
        )
    }

    @Test
    fun `Account is never blocked - it is where the fix lives`() {
        assertNull(blocked(SettingCategory.ACCOUNT, hasServer = false, hasCatalogue = false))
        assertNull(blocked(SettingCategory.ABOUT, hasServer = false, hasCatalogue = false))
    }

    @Test
    fun `the rows that do not need a provider stay live`() {
        val home = blocked(SettingCategory.HOME, hasServer = false, hasCatalogue = false)
        assertNotNull(home)
        // App Language and App Layout are about this app on this device.
        assertEquals(setOf("app_language", "ui_mode"), home!!.liveKeys)

        val data = blocked(SettingCategory.DATA, hasServer = false, hasCatalogue = false)
        assertEquals(setOf("clear_cache"), data!!.liveKeys)
    }

    @Test
    fun `a signed-in server with no catalogue blocks only Home, and offers the fix`() {
        val blockedCategories = SettingCategory.values()
            .filter { blocked(it, hasServer = true, hasCatalogue = false) != null }
            .toSet()
        assertEquals(setOf(SettingCategory.HOME), blockedCategories)

        val home = blocked(SettingCategory.HOME, hasServer = true, hasCatalogue = false)
        assertNotNull("the one fix Settings can perform itself carries a button", home!!.actionLabel)
    }

    @Test
    fun `no server outranks no catalogue - the first thing to fix is named first`() {
        val home = blocked(SettingCategory.HOME, hasServer = false, hasCatalogue = false)
        // Without a server, refreshing would fetch nothing, so that button must not be offered.
        assertNull(home!!.actionLabel)
    }

    @Test
    fun `a blocked category always says both the cause and the fix`() {
        SettingCategory.values().forEach { category ->
            blocked(category, hasServer = false, hasCatalogue = false)?.let {
                assertTrue("$category cause", it.cause.isNotBlank())
                assertTrue("$category fix", it.fix.isNotBlank())
            }
        }
    }
}
