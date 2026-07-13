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

/**
 * Full-screen gate shown when the device is not activated. Displays the activation
 * code for the user to give the admin, and auto-advances into the app as soon as the
 * Firestore license flips to `active` (via [LicenseManager]'s realtime listener).
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var manager: LicenseManager
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)
        manager = LicenseManager.getInstance(this)

        val codeTv = findViewById<TextView>(R.id.tv_activation_code)
        val statusTv = findViewById<TextView>(R.id.tv_activation_status)
        val titleTv = findViewById<TextView>(R.id.tv_activation_title)
        val deviceTv = findViewById<TextView>(R.id.tv_activation_device)
        val retry = findViewById<Button>(R.id.btn_activation_retry)

        codeTv.text = manager.activationCode
        deviceTv.text = "Device ID: ${manager.installId.take(8)}"

        retry.setOnClickListener {
            statusTv.text = "Checking…"
            manager.stop()
            manager.start() // force a fresh fetch of the license doc
        }
        retry.post { retry.requestFocus() }

        manager.start()

        lifecycleScope.launch {
            manager.state.collectLatest { state ->
                when (state) {
                    is LicenseState.Active -> goToApp()
                    is LicenseState.Loading -> statusTv.text = "Checking…"
                    is LicenseState.Locked -> when (state.reason) {
                        LicenseState.Reason.PENDING -> {
                            titleTv.text = "Share this code with your provider"
                            statusTv.text = "Waiting for activation…"
                        }
                        LicenseState.Reason.DEACTIVATED -> {
                            titleTv.text = "This device is deactivated"
                            statusTv.text = "Contact your provider to reactivate"
                        }
                        LicenseState.Reason.EXPIRED -> {
                            titleTv.text = "Your subscription has expired"
                            statusTv.text = "Contact your provider to renew"
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
