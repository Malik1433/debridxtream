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
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import java.util.Random

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
        deviceCode?.let { db.collection("device_codes").document(it).delete() }
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

    private fun persistentCode(): String {
        val prefs = CredentialsPreferences(context.applicationContext)
        var code = prefs.getSyncCode()
        if (code == null) {
            val chars = "0123456789"
            code = (1..6).map { chars[Random().nextInt(chars.length)] }.joinToString("")
            prefs.saveSyncCode(code)
        }
        return code
    }

    private fun bindPairingCode(code: String) {
        val row = overlay.findViewById<LinearLayout>(R.id.ll_pairing_code)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as? TextView)?.text = code.getOrNull(i)?.toString() ?: ""
        }
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
            if (snapshot != null && !paired && (status == "completed" || status == "success")) {
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
}
