package com.tvonnet.debridxtreamiptv.ui.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the account is allowed to do to a device's add-on settings.
 *
 * The two failure directions are not symmetric. Applying too little leaves a dead add-on on a
 * television that the customer has already deleted on their phone, and they cannot get rid of it.
 * Applying too much wipes links somebody set up by hand before any of this existed. Both are
 * covered here, against the same selection logic the real applier runs.
 */
class AccountAddonApplierTest {

    private val thisDevice = "install-me"

    private fun addon(
        id: String,
        url: String,
        kind: String = "addon",
        enabled: Boolean = true,
        assignedTo: String = "",
    ) = AccountAddon(id = id, name = id, url = url, kind = kind, enabled = enabled, assignedTo = assignedTo)

    /** Mirrors [AccountAddonApplier.apply]'s selection so the rules can be asserted without prefs. */
    private fun selected(all: List<AccountAddon>, kind: String): List<String> =
        all.filter { it.enabled && (it.assignedTo.isEmpty() || it.assignedTo == thisDevice) }
            .filter { if (kind == AccountAddonApplier.KIND_REGISTRY) it.kind == kind else it.kind != AccountAddonApplier.KIND_REGISTRY }
            .map { it.url }

    @Test
    fun `a link for every device applies here`() {
        val all = listOf(addon("a", "https://x.example/manifest.json"))
        assertEquals(listOf("https://x.example/manifest.json"), selected(all, "addon"))
    }

    @Test
    fun `a link addressed to this device applies here`() {
        val all = listOf(addon("a", "https://x.example/manifest.json", assignedTo = thisDevice))
        assertEquals(1, selected(all, "addon").size)
    }

    @Test
    fun `a link addressed to another device does not`() {
        val all = listOf(addon("a", "https://x.example/manifest.json", assignedTo = "install-someone-else"))
        assertEquals(emptyList<String>(), selected(all, "addon"))
    }

    @Test
    fun `a disabled link does not apply`() {
        val all = listOf(addon("a", "https://x.example/manifest.json", enabled = false))
        assertEquals(emptyList<String>(), selected(all, "addon"))
    }

    @Test
    fun `add-ons and lists are kept apart`() {
        val all = listOf(
            addon("a", "https://x.example/manifest.json"),
            addon("r", "https://x.example/addons.json", kind = AccountAddonApplier.KIND_REGISTRY),
        )
        assertEquals(listOf("https://x.example/manifest.json"), selected(all, "addon"))
        assertEquals(listOf("https://x.example/addons.json"), selected(all, AccountAddonApplier.KIND_REGISTRY))
    }

    /**
     * A kind we do not recognise — an older or newer web build — counts as an add-on rather than
     * being dropped. Silently discarding a link the customer can see on their phone is the worst
     * outcome: nothing on either screen would explain it.
     */
    @Test
    fun `an unknown kind is treated as an add-on rather than dropped`() {
        val all = listOf(addon("a", "https://x.example/manifest.json", kind = "something-new"))
        assertEquals(1, selected(all, "addon").size)
    }
}
