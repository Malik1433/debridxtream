package com.tvonnet.debridxtreamiptv.ui.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.network.CompanionConfigServer
import com.tvonnet.debridxtreamiptv.network.RemotePairingManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.widget.Toast
import kotlinx.coroutines.flow.collect
import dagger.hilt.android.AndroidEntryPoint
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

/**
 * CompanionSetupFragment: Displays the QR code and instructions for the Companion App.
 * Manages the local Ktor server lifecycle.
 */
@AndroidEntryPoint
class CompanionSetupFragment : Fragment() {

    @Inject
    lateinit var companionConfigServer: CompanionConfigServer
    @Inject
    lateinit var remotePairingManager: RemotePairingManager

    private lateinit var ivQrCode: ImageView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvPairingCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var vStatusIndicator: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_companion_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivQrCode = view.findViewById(R.id.iv_qr_code)
        tvServerUrl = view.findViewById(R.id.tv_server_urlManual)
        tvStatus = view.findViewById(R.id.tv_status)
        vStatusIndicator = view.findViewById(R.id.v_status_indicator)
        tvPairingCode = view.findViewById(R.id.tv_pairing_code)

        companionConfigServer.onConfigSynced = {
            onConfigReceived()
        }

        setupUI()
        setupRemotePairing()
    }

    private fun onConfigReceived() {
        tvStatus.text = "Sync Successful! App will refresh."
        vStatusIndicator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green))
        
        // Simple visual feedback
        vStatusIndicator.animate()
            .scaleX(2f)
            .scaleY(2f)
            .setDuration(400)
            .withEndAction {
                vStatusIndicator.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(400)
                    .start()
            }
            .start()

        android.widget.Toast.makeText(requireContext(), "Configuration Sync Complete!", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        remotePairingManager.stopPolling()
        companionConfigServer.stop()
    }

    override fun onResume() {
        super.onResume()
        companionConfigServer.start()
        updateStatus(true)
    }

    override fun onPause() {
        super.onPause()
        companionConfigServer.stop()
        updateStatus(false)
    }

    private fun setupUI() {
        // Use the deployed Vercel Dashboard URL
        val dashboardUrl = "https://web-dashboard-five-iota.vercel.app"
        tvServerUrl.text = "Visit: $dashboardUrl"
        generateQrCode(dashboardUrl)
        
        // Optional: Show local IP as backup or status
        val ipAddress = getLocalIpAddress()
        if (ipAddress != null) {
            tvStatus.text = "TV IP: $ipAddress (Server Running)"
            vStatusIndicator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            tvStatus.text = "WiFi Disconnected (Remote Pair Only)"
             vStatusIndicator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red))
        }
    }

    private fun setupRemotePairing() {
        val code = remotePairingManager.generatePairingCode()
        tvPairingCode.text = code
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                remotePairingManager.pairingState.collect { state ->
                    if (state is RemotePairingManager.PairingState.Success) {
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        // Small delay to show success before exiting
                        delay(2000)
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        
        remotePairingManager.startPolling()
    }

    private fun generateQrCode(url: String) {
        try {
            val bitmap = com.tvonnet.debridxtreamiptv.util.QrGenerator.generateQrCode(url, 512)
            if (bitmap != null) {
                ivQrCode.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e("CompanionSetup", "Error generating QR code", e)
        }
    }

    private fun updateStatus(isRunning: Boolean) {
        if (isRunning) {
            tvStatus.text = "Server Running (Waiting for Phone)"
            vStatusIndicator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            tvStatus.text = "Server Stopped"
            vStatusIndicator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_disabled))
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IPUtils", "Error getting IP", e)
        }
        return null
    }

    companion object {
        fun newInstance() = CompanionSetupFragment()
    }
}
