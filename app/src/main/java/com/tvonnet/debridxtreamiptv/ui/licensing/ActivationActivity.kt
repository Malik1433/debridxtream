package com.tvonnet.debridxtreamiptv.ui.licensing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
import com.tvonnet.debridxtreamiptv.data.licensing.LicenseState
import com.tvonnet.debridxtreamiptv.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices

/**
 * Full-screen gate shown when the device is not activated. Displays the activation
 * code for the user to give the admin, and auto-advances into the app as soon as the
 * Firestore license flips to `active` (via [LicenseManager]'s realtime listener).
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var manager: LicenseManager
    private var navigated = false

    // M19: this activity deliberately does NOT take phoneScaledContext. §1e assumed it was at
    // the TV's px÷2 sizes like the player; measuring it showed the opposite — the layout uses
    // REAL sizes (28sp title, 56sp code, 16sp status), already readable in the hand. At 1.6x
    // the centred column would total ~474dp on a 411dp-tall handset and clip both ends.
    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: BEFORE super.onCreate on purpose — the orientation has to be settled before the
        // first inflate, or the layout chosen for the old one stays on screen inside the new
        // window (M11 proved that the hard way, in the opposite direction).
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)
        manager = LicenseManager.getInstance(this)

        val codeTv = findViewById<TextView>(R.id.tv_activation_code)
        val statusTv = findViewById<TextView>(R.id.tv_activation_status)
        val titleTv = findViewById<TextView>(R.id.tv_activation_title)
        val deviceTv = findViewById<TextView>(R.id.tv_activation_device)
        val retry = findViewById<Button>(R.id.btn_activation_retry)

        codeTv.text = manager.activationCode
        // The code above is the ONLY thing a provider can look a device up by. This line used
        // to print installId.take(8) — a fragment that matches nothing, which sent people off
        // to activate with a string no lookup accepts. Show the full id, labelled as support
        // detail, so it can be read out when someone genuinely needs it (and never mistaken
        // for the code).
        deviceTv.text = "Support ID: ${manager.installId}"

        retry.setOnClickListener {
            statusTv.text = statusTv.context.getString(R.string.c_checking)
            manager.stop()
            manager.start() // force a fresh fetch of the license doc
        }
        retry.post { retry.requestFocus() }

        manager.start()

        lifecycleScope.launch {
            manager.state.collectLatest { state ->
                when (state) {
                    is LicenseState.Active -> goToApp()
                    is LicenseState.Loading -> statusTv.text = statusTv.context.getString(R.string.c_checking)
                    is LicenseState.Locked -> when (state.reason) {
                        // Until the licence doc reaches the server the provider's lookup by
                        // code returns "no device found", so asking the customer to go and
                        // activate would send them into exactly that wall. Say what is
                        // actually happening instead; this clears itself in a second or two.
                        LicenseState.Reason.PENDING -> if (!manager.isRegisteredOnServer) {
                            titleTv.text = titleTv.context.getString(R.string.c_registering_this_device)
                            statusTv.text = statusTv.context.getString(R.string.c_give_it_a_moment_then)
                        } else {
                            titleTv.text = titleTv.context.getString(R.string.ui_share_this_code_with_your)
                            statusTv.text = statusTv.context.getString(R.string.ui_waiting_for_activation)
                        }
                        LicenseState.Reason.TRIAL_ENDED -> {
                            titleTv.text = titleTv.context.getString(R.string.c_your_free_trial_has_ended)
                            statusTv.text = statusTv.context.getString(R.string.c_share_this_code_with_your)
                        }
                        LicenseState.Reason.DEACTIVATED -> {
                            titleTv.text = titleTv.context.getString(R.string.c_this_device_is_deactivated)
                            statusTv.text = statusTv.context.getString(R.string.c_contact_your_provider_to_reactivate)
                        }
                        LicenseState.Reason.EXPIRED -> {
                            titleTv.text = titleTv.context.getString(R.string.c_your_subscription_has_expired)
                            statusTv.text = statusTv.context.getString(R.string.c_contact_your_provider_to_renew)
                        }
                        // Nothing is wrong with the licence — the device just has not been able to
                        // reach us for a while. Say that, and say what fixes it. Telling someone
                        // their subscription expired when it has not is how support tickets start.
                        LicenseState.Reason.OFFLINE_TOO_LONG -> {
                            titleTv.text = titleTv.context.getString(R.string.c_please_connect_to_the_internet)
                            statusTv.text = statusTv.context.getString(R.string.c_this_device_needs_to_check)
                        }
                    }
                }
            }
        }
    }

    private fun goToApp() {
        if (navigated) return
        navigated = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
