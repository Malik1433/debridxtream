package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.View
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator

/**
 * The provider strip under the phone's Debrid hero (design frame 1a).
 *
 * It answers the one question this whole section rests on and nothing else on the screen answers:
 * is a debrid account actually connected, and how many add-ons are searching for sources.
 *
 * **Only what the device knows.** The design mocks "Premium · 412 days left"; that comes from the
 * provider's account API, which this app does not call, so the strip says what is true here — the
 * account is connected or it is not, and the add-on count. Inventing an expiry would be the worst
 * kind of polish: a number that looks authoritative and is made up.
 *
 * Tapping it opens Settings, where the keys and the add-ons actually live.
 */
internal class StremioProviderStrip(
    private val fragment: StremioHomeFragment,
    header: View,
) {
    private val root: View = header.findViewById(R.id.debrid_provider_strip)
    private val dot: View = header.findViewById(R.id.provider_dot)
    private val title: TextView = header.findViewById(R.id.provider_title)
    private val subtitle: TextView = header.findViewById(R.id.provider_subtitle)

    init {
        root.setOnClickListener { SectionNavigator.navigate(fragment, "settings") }
        refresh()
    }

    /** Re-read on resume: a key or an add-on can arrive while this screen sits in the background. */
    fun refresh() {
        val context = root.context
        val prefs = DebridPreferences(context)
        val addons = prefs.getStremioAddonUrls().size + prefs.getAddonRegistryUrls().size
        val connected = !prefs.getRealDebridToken().isNullOrBlank()

        dot.setBackgroundResource(
            if (connected) R.drawable.bg_phone_dot_green else R.drawable.bg_phone_dot_amber
        )
        title.setText(
            when {
                connected -> R.string.phone_debrid_connected
                addons > 0 -> R.string.phone_debrid_addons_only
                else -> R.string.phone_debrid_not_connected
            }
        )
        subtitle.text = if (addons > 0) {
            context.resources.getQuantityString(R.plurals.s_summary_addons, addons, addons)
        } else {
            context.getString(R.string.phone_debrid_addons_hint)
        }
    }
}
