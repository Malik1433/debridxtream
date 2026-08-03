package com.tvonnet.debridxtreamiptv.ui.companion

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.ActivityCompanionSetupBinding


class CompanionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanionSetupBinding
    private val db = FirebaseFirestore.getInstance()
    private lateinit var deviceCode: String
    private var isConfigured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceCode = getPersistentCode()
        setupUI()
        startListening()
    }

    /**
     * The permanent DEVICE KEY — the licensing activation code (same key as the
     * activation screen / admin panel). One key identifies the device everywhere.
     */
    private fun getPersistentCode(): String =
        com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
            .getInstance(applicationContext).activationCode

    private fun setupUI() {
        binding.tvDeviceCode.text = deviceCode
        
        // Generate QR Code with the setup URL from strings.xml
        val baseUrl = getString(R.string.companion_setup_url)
        val setupUrl = if (baseUrl.contains("?")) {
            "$baseUrl&code=$deviceCode"
        } else {
            "$baseUrl?code=$deviceCode"
        }
        generateQrCode(setupUrl)
    }

    private fun generateQrCode(url: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(url, BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.pbLoading.visibility = View.GONE
        } catch (e: Exception) {
            android.util.Log.e("CompanionSetup", "QR code generation failed", e)
            Toast.makeText(this, "Error generating QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListening() {
        // Keep Firestore logging quiet to avoid leaking setup payload details in debug output.
        try {
            FirebaseFirestore.setLoggingEnabled(false)
        } catch (e: Exception) {
            android.util.Log.w("CompanionSetup", "Could not enable Firestore logging", e)
        }

        val docRef = db.collection("device_codes").document(deviceCode)

        // Update initial status
        setSyncStatus("Initializing sync document...", R.color.warning_yellow)

        // Initialize the document for the web app to find
        docRef.set(mapOf(
            "status" to "waiting",
            "createdAt" to System.currentTimeMillis()
        )).addOnSuccessListener {
            android.util.Log.d("CompanionSetup", "Document initialized successfully on Firestore.")
        }.addOnFailureListener { exception ->
            android.util.Log.e("CompanionSetup", "Failed to initialize document on Firestore.", exception)
            setSyncStatus("Initialization failed: ${exception.localizedMessage}. Press BACK to retry.", R.color.error_red)
        }

        docRef.addSnapshotListener { snapshot, e -> onSetupSnapshot(snapshot, e) }
    }

    // Status text + indicator tint always change together — one place, verbatim colours.
    private fun setSyncStatus(text: String, colorRes: Int) {
        binding.tvConnectionStatus.text = text
        binding.vStatusIndicator.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this, colorRes)
            )
        )
    }

    private fun onSetupSnapshot(
        snapshot: com.google.firebase.firestore.DocumentSnapshot?,
        e: com.google.firebase.firestore.FirebaseFirestoreException?
    ) {
        if (e != null) {
            android.util.Log.e("CompanionSetup", "Listen failed.", e)
            setSyncStatus("Connection error: ${e.localizedMessage}. Press BACK to retry.", R.color.error_red)
            return
        }

        if (snapshot != null && snapshot.exists()) {
            val isFromCache = snapshot.metadata.isFromCache
            val status = snapshot.getString("status")
            android.util.Log.d("CompanionSetup", "Snapshot updated. Status=$status, isFromCache=$isFromCache")

            if (status == "completed" || status == "success") {
                setSyncStatus("Credentials sync completed!", R.color.success_green)
                handleConfigurationReceived(snapshot.data)
            } else if (isFromCache) {
                setSyncStatus("Running offline. Waiting to connect to server...", R.color.warning_yellow)
            } else {
                setSyncStatus("Connected to server. Ready for companion setup...", R.color.success_green)
            }
        } else {
            setSyncStatus("Waiting for server connection...", R.color.warning_yellow)
        }
    }

    private fun handleConfigurationReceived(data: Map<String, Any>?) {
        if (data == null) return
        if (isConfigured) return
        isConfigured = true

        CompanionConfigApplier.apply(applicationContext, data)

        Toast.makeText(this, "Configuration Received! Happy Streaming.", Toast.LENGTH_LONG).show()

        
        // Return result to LoginFragment
        setResult(RESULT_OK)
        finish()
    }


    override fun onDestroy() {
        super.onDestroy()
        // The device_codes doc is intentionally kept — the device key is permanent so
        // the companion page can push updated config anytime (see CompanionConfigSync).
    }
}
