package com.tvonnet.debridxtreamiptv.ui.settings

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices

@AndroidEntryPoint
class MediaFusionConfigActivity : AppCompatActivity() {

    @Inject
    lateinit var preferences: DebridPreferences

    private lateinit var etManualLink: EditText
    private lateinit var qrCodeImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: one orientation for the whole app on a phone — landscape, like the TV.
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview) // Layout file name preserved

        etManualLink = findViewById(R.id.et_manual_link)
        qrCodeImage = findViewById(R.id.qrCodeImage)

        val btnConnectManual = findViewById<Button>(R.id.btn_connect_manual)
        val btnScanQr = findViewById<Button>(R.id.scanQrButton)
        btnConnectManual.text = getString(R.string.stremio_addons_save_button)
        btnScanQr.text = getString(R.string.stremio_addons_scan_button)

        generateQrCode(getString(R.string.companion_setup_url))

        btnConnectManual.setOnClickListener {
            val urls = parseAddonUrls(etManualLink.text.toString())
            if (urls.isNotEmpty()) {
                saveConfiguration(urls)
            } else {
                Toast.makeText(this, getString(R.string.stremio_addons_invalid_input), Toast.LENGTH_LONG).show()
            }
        }

        btnScanQr.setOnClickListener {
            startQrScan()
        }
    }

    private fun generateQrCode(url: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(url, BarcodeFormat.QR_CODE, 600, 600)
            qrCodeImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("StremioConfig", "Error generating QR code", e)
        }
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setPrompt("Scan Manifest QR Code")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    private fun parseAddonUrls(raw: String): List<String> {
        val seen = LinkedHashSet<String>()
        return raw
            .split('\n', ',', ';')
            .mapNotNull { token -> normalizeAddonUrl(token) }
            .filter { isValidAddonUrl(it) }
            .filter { seen.add(it) }
    }

    private fun normalizeAddonUrl(url: String): String {
        return url.trim()
            .replaceFirst("stremio://", "https://", ignoreCase = true)
            .trimEnd('/')
    }

    private fun isValidAddonUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) && lower.contains("manifest.json")
    }

    private fun saveConfiguration(urls: List<String>) {
        try {
            Log.d("StremioConfig", "Saving ${urls.size} addon URL(s)")
            preferences.setStremioAddonUrls(urls)

            Toast.makeText(this, getString(R.string.stremio_addons_saved), Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val urls = parseAddonUrls(result.contents)
                if (urls.isNotEmpty()) {
                    saveConfiguration(urls)
                } else {
                    Toast.makeText(this, getString(R.string.stremio_addons_invalid_qr), Toast.LENGTH_LONG).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
