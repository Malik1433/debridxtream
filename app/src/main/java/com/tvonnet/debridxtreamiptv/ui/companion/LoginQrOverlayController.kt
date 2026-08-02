package com.tvonnet.debridxtreamiptv.ui.companion

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.tvonnet.debridxtreamiptv.R

/**
 * Fullscreen "Connect via Phone / QR" pairing overlay on the login screen.
 *
 * Reuses the CompanionSetupActivity pairing mechanism: a persistent 6-digit sync code,
 * a QR of the companion setup URL, and a Firestore `device_codes/<code>` listener.
 * On a completed sync the payload is applied via [CompanionConfigApplier] and
 * [onPaired] fires so the login screen can auto-login.
 */
class LoginQrOverlayController(
    private val overlay: ViewGroup,
    private val onPaired: () -> Unit
) {

    private val context get() = overlay.context
    private val handler = Handler(Looper.getMainLooper())
    private val db = FirebaseFirestore.getInstance()

    private var listener: ListenerRegistration? = null
    private var deviceCode: String? = null
    private var paired = false
    private var statusIdx = 0

    private val scanAnimator: ObjectAnimator
    private val pulseAnimator: ObjectAnimator

    private val statuses = listOf(
        "WAITING FOR PHONE TO CONNECT…",
        "SCANNING FOR DEVICE…",
        "ESTABLISHING SECURE CHANNEL…"
    )

    private val tvStatus: TextView = overlay.findViewById(R.id.tv_qr_status)
    private val btnClose: View = overlay.findViewById(R.id.btn_qr_close)

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    init {
        val density = overlay.resources.displayMetrics.density
        scanAnimator = ObjectAnimator.ofFloat(
            overlay.findViewById<View>(R.id.view_qr_scanline), View.TRANSLATION_Y, 0f, 128f * density
        ).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimator = ObjectAnimator.ofFloat(
            overlay.findViewById<View>(R.id.view_qr_pulse_dot), View.ALPHA, 0.35f, 1f
        ).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        btnClose.setOnClickListener { hide() }
    }

    fun show() {
        if (isShowing) return
        paired = false
        statusIdx = 0
        tvStatus.text = statuses[0]

        val code = persistentCode()
        deviceCode = code
        bindPairingCode(code)
        renderQr(code)

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(280).start()

        scanAnimator.start()
        pulseAnimator.start()
        handler.postDelayed(statusCycler, 2200)
        startListening(code)

        btnClose.post { btnClose.requestFocus() }
    }

    fun hide() {
        if (!isShowing) return
        stopPairing()
        overlay.animate().alpha(0f).setDuration(180).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    /** Call from the host fragment's onDestroyView. */
    fun release() {
        stopPairing()
        overlay.animate().cancel()
    }

    private fun stopPairing() {
        listener?.remove()
        listener = null
        // NOTE: the device_codes doc is intentionally NOT deleted anymore — the device
        // key is permanent, so the user can open the companion page anytime later and
        // push updated config (a persistent listener applies it; see CompanionConfigSync).
        handler.removeCallbacks(statusCycler)
        scanAnimator.cancel()
        pulseAnimator.cancel()
    }

    private val statusCycler = object : Runnable {
        override fun run() {
            statusIdx = (statusIdx + 1) % statuses.size
            tvStatus.text = statuses[statusIdx]
            handler.postDelayed(this, 2200)
        }
    }

    /**
     * The permanent DEVICE KEY — the licensing activation code (stable per install,
     * same key shown on the activation screen and in the admin panel). Replaces the
     * old random 6-digit sync code so one key identifies the device everywhere.
     */
    private fun persistentCode(): String =
        com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
            .getInstance(context.applicationContext).activationCode

    private fun bindPairingCode(code: String) {
        // Full key (dash included) in one view — per-char tiles used to clip on TV
        // and users misread the cut-off key when pairing.
        overlay.findViewById<TextView>(R.id.tv_pairing_code)?.text = code
    }

    private fun renderQr(code: String) {
        val baseUrl = context.getString(R.string.companion_setup_url)
        val setupUrl = if (baseUrl.contains("?")) "$baseUrl&code=$code" else "$baseUrl?code=$code"
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(setupUrl, BarcodeFormat.QR_CODE, 400, 400)
            overlay.findViewById<ImageView>(R.id.iv_qr_code).setImageBitmap(bitmap)
        } catch (e: Exception) {
            android.util.Log.e("LoginQrOverlay", "QR generation failed", e)
        }
    }

    private fun startListening(code: String) {
        try {
            FirebaseFirestore.setLoggingEnabled(false)
        } catch (_: Exception) {
        }

        val docRef = db.collection("device_codes").document(code)
        docRef.set(
            mapOf(
                "status" to "waiting",
                "createdAt" to System.currentTimeMillis()
            )
        )

        listener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                tvStatus.text = "CONNECTION ERROR · RETRYING…"
                return@addSnapshotListener
            }
            val status = snapshot?.getString("status")
            val pairingFinished = status == "completed" || status == "success"
            if (snapshot != null && !paired && pairingFinished) {
                paired = true
                val applied = CompanionConfigApplier.apply(context, snapshot.data)
                tvStatus.text = "✓ PHONE CONNECTED · SYNCING…"
                handler.removeCallbacks(statusCycler)
                if (applied) {
                    hide()
                    onPaired()
                }
            }
        }
    }

    /**
     * The QR now opens the ACCOUNT claim page (§9 U9), and that route never writes to
     * `device_codes` — so the listener above will not fire for it. What arrives instead is
     * credentials, read from the account by [AccountPlaylistSync] once the customer confirms the TV
     * on their phone.
     *
     * Without this, the overlay would sit on "SCANNING FOR DEVICE…" forever while the login it was
     * waiting for had already happened behind it. The `device_codes` listener stays for the legacy
     * page, which older builds still point at.
     */
    fun onConfiguredElsewhere() {
        if (paired) return
        paired = true
        tvStatus.text = "✓ TV ADDED · SYNCING…"
        handler.removeCallbacks(statusCycler)
        hide()
    }
}
