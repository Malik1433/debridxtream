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
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import java.util.*


class CompanionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanionSetupBinding
    private val db = FirebaseFirestore.getInstance()
    private lateinit var deviceCode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceCode = getPersistentCode()
        setupUI()
        startListening()
    }

    private fun getPersistentCode(): String {
        val prefs = CredentialsPreferences(applicationContext)
        var code = prefs.getSyncCode()
        if (code == null) {
            code = generateRandomCode()
            prefs.saveSyncCode(code)
        }
        return code
    }

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

    private fun generateRandomCode(): String {
        val chars = "0123456789"
        return (1..6)
            .map { chars[Random().nextInt(chars.length)] }
            .joinToString("")
    }

    private fun generateQrCode(url: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(url, BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.pbLoading.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListening() {
        val docRef = db.collection("device_codes").document(deviceCode)
        
        // Initialize the document for the web app to find
        docRef.set(mapOf(
            "status" to "waiting",
            "createdAt" to System.currentTimeMillis()
        ))

        docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.getString("status")
                if (status == "completed") {
                    handleConfigurationReceived(snapshot.data)
                }
            }
        }
    }

    private fun handleConfigurationReceived(data: Map<String, Any>?) {
        if (data == null) return

        val iptv = data["iptv"] as? Map<*, *>
        val debridTokenLegacy = data["debrid"] as? String
        val mediafusionLegacy = data["mediafusion"] as? String
        val debridConfig = data["debridConfig"] as? Map<*, *>
        val stremioUrlsLegacy = data["stremioAddonUrls"] as? List<*>

        // Save IPTV Config using project-standard CredentialsPreferences
        // We set loggedIn to FALSE initially so LoginFragment can trigger its own validation/login flow
        iptv?.let {
            val server = it["url"] as? String
            val username = it["username"] as? String
            val password = it["password"] as? String
            
            if (server != null && username != null && password != null) {
                CredentialsPreferences(applicationContext).saveSyncedCredentials(server, username, password)
            }
        }


        // Save Debrid Config using project-standard DebridPreferences
        val debridPrefs = DebridPreferences(applicationContext)

        // New schema: debridConfig { token, mediaFusionUrl, stremioAddonUrls }
        debridConfig?.let { cfg ->
            val token = cfg["token"] as? String
            val mediaFusionUrl = cfg["mediaFusionUrl"] as? String
            val stremioAddonUrls = (cfg["stremioAddonUrls"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            if (!token.isNullOrBlank()) {
                debridPrefs.saveRealDebridToken(token)
            }
            if (!mediaFusionUrl.isNullOrBlank()) {
                debridPrefs.saveMediaFusionUrl(mediaFusionUrl)
            }
            if (stremioAddonUrls.isNotEmpty()) {
                debridPrefs.setStremioAddonUrls(stremioAddonUrls)
            }
        }

        // Legacy fields (backward compatible)
        debridTokenLegacy?.takeIf { it.isNotBlank() }?.let { debridPrefs.saveRealDebridToken(it) }
        mediafusionLegacy?.takeIf { it.isNotBlank() }?.let { debridPrefs.saveMediaFusionUrl(it) }
        val stremioAddonUrls = stremioUrlsLegacy
            ?.mapNotNull { it as? String }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (stremioAddonUrls.isNotEmpty()) {
            debridPrefs.setStremioAddonUrls(stremioAddonUrls)
        }

        Toast.makeText(this, "Configuration Received! Happy Streaming.", Toast.LENGTH_LONG).show()

        
        // Return result to LoginFragment
        setResult(RESULT_OK)
        finish()
    }


    override fun onDestroy() {
        super.onDestroy()
        // Optional: Cleanup the code from Firestore when leaving
        db.collection("device_codes").document(deviceCode).delete()
    }
}
