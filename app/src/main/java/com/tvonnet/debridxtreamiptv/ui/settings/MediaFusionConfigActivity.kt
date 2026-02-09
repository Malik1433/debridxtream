package com.tvonnet.debridxtreamiptv.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MediaFusionConfigActivity : AppCompatActivity() {

    @Inject
    lateinit var preferences: DebridPreferences

    private lateinit var etManualLink: EditText
    private lateinit var qrCodeImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview) // Layout file name preserved

        // Initialize Views
        etManualLink = findViewById(R.id.et_manual_link)
        qrCodeImage = findViewById(R.id.qrCodeImage)
        
        val btnConnectManual = findViewById<Button>(R.id.btn_connect_manual)
        val btnScanQr = findViewById<Button>(R.id.scanQrButton)

        // 1. Generate Setup QR Code
        // Points to the official configuration page
        generateQrCode("https://mediafusion.elfhosted.com/configure")

        // 2. Setup Manual Connect
        btnConnectManual.setOnClickListener {
            val inputUrl = etManualLink.text.toString().trim()
            if (isValidMediaFusionUrl(inputUrl)) {
                saveConfiguration(inputUrl)
            } else {
                Toast.makeText(this, "Invalid URL. Must contain 'manifest.json' or be a valid link.", Toast.LENGTH_LONG).show()
            }
        }

        // 3. Setup Camera Scan
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
            Log.e("MediaFusionConfig", "Error generating QR code", e)
        }
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setPrompt("Scan Result QR Code from Phone")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    private fun isValidMediaFusionUrl(url: String): Boolean {
        return url.isNotEmpty() && (url.contains("mediafusion") || url.contains("manifest.json") || url.startsWith("stremio://") || url.startsWith("https://"))
    }

    private fun saveConfiguration(url: String) {
        try {
            var httpsUrl = url
            if (url.startsWith("stremio://")) {
                httpsUrl = url.replace("stremio://", "https://")
            }
            
            // Basic cleanup
            httpsUrl = httpsUrl.trim()
            
            Log.d("MediaFusionConfig", "Saving Config URL: $httpsUrl")
            preferences.saveMediaFusionUrl(httpsUrl)
            
            Toast.makeText(this, "Connected! You can now watch premium content.", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val scannedUrl = result.contents
                Log.d("MediaFusionConfig", "Scanned QR: $scannedUrl")
                if (isValidMediaFusionUrl(scannedUrl)) {
                    saveConfiguration(scannedUrl)
                } else {
                    Toast.makeText(this, "Invalid MediaFusion QR Code", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
