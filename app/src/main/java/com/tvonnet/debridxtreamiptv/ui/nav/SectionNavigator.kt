package com.tvonnet.debridxtreamiptv.ui.nav

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioHomeFragment
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.live.guide.LiveTvGuideFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment

/**
 * Section routing shared by every nav rail (Home's and Live TV v2's).
 *
 * The tier gate and the Live TV classic/guide split live here so a second rail can't drift from
 * the first — a copy that forgot [Entitlements.isDebridAllowed] would hand Debrid to NORMAL
 * devices.
 */
object SectionNavigator {

    const val SECTION_SEARCH = "search"
    const val SECTION_HOME = "home"
    const val SECTION_LIVE = "live"
    const val SECTION_MOVIES = "movies"
    const val SECTION_SERIES = "series"
    const val SECTION_DEBRID = "debrid"
    const val SECTION_SETTINGS = "settings"

    /** NORMAL devices are IPTV-only; Debrid is premium (owner policy). */
    fun isAllowed(context: Context, section: String): Boolean =
        section != SECTION_DEBRID || Entitlements.isDebridAllowed(context)

    /**
     * The user's Live TV style decides which Live screen a "live" hop opens:
     * Classic -> the 3-column [LiveFragment], otherwise the EPG grid.
     */
    fun createFragment(context: Context, section: String): Fragment? = when (section) {
        SECTION_LIVE ->
            if (SettingsPreferences(context).getLiveTvStyle() == SettingsPreferences.STYLE_CLASSIC) {
                LiveFragment()
            } else {
                LiveTvGuideFragment()
            }
        SECTION_MOVIES -> VodFragment()
        SECTION_SERIES -> SeriesFragment()
        SECTION_DEBRID -> StremioHomeFragment()
        SECTION_SEARCH -> SearchFragment()
        SECTION_SETTINGS -> SettingsFragment()
        else -> null
    }

    /**
     * Replaces the hosted content with [section]. Returns false when the hop was refused (not
     * entitled, unknown section, or the host can't commit right now).
     *
     * [beforeCommit] runs only once the hop is committed, for host bookkeeping.
     */
    fun navigate(host: Fragment, section: String, beforeCommit: () -> Unit = {}): Boolean {
        if (!host.isAdded || host.parentFragmentManager.isStateSaved) return false
        val context = host.context ?: return false

        if (!isAllowed(context, section)) {
            Toast.makeText(context, R.string.premium_feature_locked, Toast.LENGTH_SHORT).show()
            return false
        }

        val target = createFragment(context, section) ?: return false
        beforeCommit()
        host.parentFragmentManager.commit {
            replace(R.id.content_container, target)
            addToBackStack(null)
        }
        return true
    }
}
