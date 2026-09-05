package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRefreshState
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridUiState
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsManageQrDialog

/**
 * What the screen IS at this moment — exactly one of these, always.
 *
 * The states used to be decided in two unrelated places: a setup gate driven by the device's
 * entitlements, and a fetch state driven by the ViewModel. Nothing stopped them contradicting
 * each other, and nothing had to: a gate that says "not connected" over a spinner that says
 * "loading" is two answers to one question.
 */
internal enum class DebridScreen {
    /** No add-ons on this account yet — design 1b. */
    NOT_CONNECTED,

    /** Nothing has arrived and nothing has failed — design 1c. */
    LOADING,

    /** Nothing arrived and the fetch failed — design 1d, as the whole page. */
    FAILED,

    /** Rows are on screen but a refresh failed — design 1d, as a panel above them. */
    STALE,

    /** Rows are on screen and a newer fetch is running. */
    REFRESHING,

    /** Rows, current. */
    READY,
}

/**
 * The one place the question is answered. Pure, so it is unit-tested rather than device-tested.
 *
 * Order matters: not-connected outranks everything, because a fetch against no add-ons cannot
 * succeed and its failure would only be noise on top of the real reason.
 */
internal fun debridScreenOf(state: DebridUiState?, configured: Boolean): DebridScreen = when {
    !configured -> DebridScreen.NOT_CONNECTED
    state == null || state is DebridUiState.Loading -> DebridScreen.LOADING
    state is DebridUiState.Error -> DebridScreen.FAILED
    state is DebridUiState.Content && state.refreshState == DebridRefreshState.Failed ->
        DebridScreen.STALE
    state is DebridUiState.Content && state.refreshState == DebridRefreshState.Refreshing ->
        DebridScreen.REFRESHING
    else -> DebridScreen.READY
}

/**
 * The views for everything this screen can be besides "here are your rows" — design
 * "DX Play Debrid Phone.dc.html", frames 1b, 1c and 1d.
 *
 * It owns the views; the fragment owns the state. Every lookup is null-safe on purpose: these
 * views exist in the phone layout only, so on the television every call here is a no-op and the
 * status line it has always used still speaks.
 */
internal class StremioStateViews(
    private val fragment: StremioHomeFragment,
    root: View,
) {
    private val skeleton: View? = root.findViewById(R.id.debrid_skeleton)
    private val errorPanel: View? = root.findViewById(R.id.debrid_error_panel)
    private val syncBadge: TextView? = root.findViewById(R.id.debrid_sync_state)
    private val setupGate: View? = root.findViewById(R.id.debrid_setup_overlay)

    private val errorKicker: TextView? = root.findViewById(R.id.debrid_error_kicker)
    private val errorTitle: TextView? = root.findViewById(R.id.debrid_error_title)
    private val errorMessage: TextView? = root.findViewById(R.id.debrid_error_message)

    private var connected = true
    private var lastState: DebridUiState? = null

    init {
        root.findViewById<View>(R.id.debrid_error_retry)?.setOnClickListener {
            fragment.debridVm.loadCatalog(force = true)
        }
        root.findViewById<View>(R.id.debrid_error_addons)?.setOnClickListener { openSettings() }
        wireSetupGate(root)
    }

    fun onState(state: DebridUiState) {
        lastState = state
        apply()
    }

    /**
     * Driven from [Entitlements.isDebridConfigured], and re-asked in onResume: add-ons can arrive
     * while this screen sits in the background, from the phone or from Settings on this TV.
     */
    fun setConnected(configured: Boolean) {
        connected = configured
        apply()
    }

    private fun apply() {
        val screen = debridScreenOf(lastState, connected)
        setupGate?.isVisible = screen == DebridScreen.NOT_CONNECTED
        skeleton?.isVisible = screen == DebridScreen.LOADING
        syncBadge?.isVisible =
            screen == DebridScreen.LOADING || screen == DebridScreen.REFRESHING
        errorPanel?.isVisible =
            screen == DebridScreen.FAILED || screen == DebridScreen.STALE
        when (screen) {
            DebridScreen.FAILED -> {
                errorKicker?.setText(R.string.debrid_catalog_unavailable)
                errorTitle?.setText(R.string.debrid_error_title)
                errorMessage?.text = (lastState as? DebridUiState.Error)?.message.orEmpty()
            }
            // The rows STAY. The ViewModel deliberately kept them, and a customer who cannot tell
            // stale content from current content is worse off than one who is simply told.
            DebridScreen.STALE -> {
                errorKicker?.setText(R.string.debrid_stale_kicker)
                errorTitle?.setText(R.string.debrid_stale_title)
                errorMessage?.setText(R.string.debrid_stale_body)
            }
            else -> Unit
        }
    }

    private fun wireSetupGate(root: View) {
        val context = root.context
        root.findViewById<TextView>(R.id.debrid_setup_url)?.text =
            SettingsManageQrDialog.manageUrl(context, code = "")
                .removePrefix("https://").removePrefix("http://")

        root.findViewById<View>(R.id.debrid_setup_action)?.setOnClickListener { openSettings() }

        // "I've added them" asks the device again rather than pretending. If they really are
        // there the gate lifts and the catalogue starts; if not, the customer is told plainly
        // instead of being dropped on rows that will never fill.
        root.findViewById<View>(R.id.debrid_setup_recheck)?.setOnClickListener {
            if (Entitlements.isDebridConfigured(context)) {
                setConnected(true)
                fragment.debridVm.bootstrapCatalog()
                fragment.debridVm.loadCatalog(force = true)
            } else {
                Toast.makeText(context, R.string.debrid_still_not_connected, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun openSettings() = SectionNavigator.navigate(fragment, "settings")
}
