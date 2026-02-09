package com.tvonnet.debridxtreamiptv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {
    
    private lateinit var etServerUrl: com.google.android.material.textfield.TextInputEditText
    private lateinit var etUsername: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPassword: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnLogin: View
    private lateinit var progressBar: ProgressBar
    private lateinit var logoView: View
    private lateinit var loginContainer: View

    // Remote Pairing UI
    private lateinit var tvPairingCode: android.widget.TextView
    private lateinit var ivQrCode: android.widget.ImageView
    private lateinit var tvServerStatus: android.widget.TextView
    
    @Inject
    lateinit var repository: XtreamRepository
    
    @Inject
    lateinit var credentialsPrefs: CredentialsPreferences

    @Inject
    lateinit var companionConfigServer: com.tvonnet.debridxtreamiptv.network.CompanionConfigServer

    @Inject
    lateinit var remotePairingManager: com.tvonnet.debridxtreamiptv.network.RemotePairingManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        etServerUrl = view.findViewById(R.id.et_server_url)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnLogin = view.findViewById(R.id.btn_login)
        progressBar = view.findViewById(R.id.progress_bar)
        logoView = view.findViewById(R.id.iv_logo)
        loginContainer = view.findViewById(R.id.card_login_container)

        // Remote Pairing UI Hooks
        tvPairingCode = view.findViewById(R.id.tv_pairing_code)
        ivQrCode = view.findViewById(R.id.iv_qr_code)
        tvServerStatus = view.findViewById(R.id.tv_server_status)
        
        btnLogin.setOnClickListener {
            onLoginClick()
        }

        setupCompanionSync()
        startEntranceAnimations()
    }

    private fun setupCompanionSync() {
        // 1. Local Server Sync
        companionConfigServer.onConfigSynced = {
            android.util.Log.d("LoginFragment", "Config synced via local server!")
            autoLoginAndNavigate()
        }

        // 2. Remote Pairing Sync
        val code = remotePairingManager.generatePairingCode()
        tvPairingCode.text = code
        
        // Generate QR for local/direct access as well
        val dashboardUrl = "https://web-dashboard-five-iota.vercel.app"
        try {
            val bitmap = com.tvonnet.debridxtreamiptv.util.QrGenerator.generateQrCode(dashboardUrl, 512)
            if (bitmap != null) {
                ivQrCode.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            android.util.Log.e("LoginFragment", "QR Error", e)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                remotePairingManager.pairingState.collect { state ->
                    when (state) {
                        is com.tvonnet.debridxtreamiptv.network.RemotePairingManager.PairingState.Success -> {
                            android.util.Log.d("LoginFragment", "Pairing success: ${state.message}")
                            autoLoginAndNavigate()
                        }
                        is com.tvonnet.debridxtreamiptv.network.RemotePairingManager.PairingState.Error -> {
                            tvServerStatus.text = "Error: ${state.message}"
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun autoLoginAndNavigate() {
        val server = credentialsPrefs.getServerUrl() ?: return
        val user = credentialsPrefs.getUsername() ?: return
        val pass = credentialsPrefs.getPassword() ?: return
        
        Toast.makeText(requireContext(), "Credentials Synced! Logging in...", Toast.LENGTH_SHORT).show()
        performLogin(server, user, pass)
    }

    override fun onResume() {
        super.onResume()
        companionConfigServer.start()
        remotePairingManager.startPolling()
        tvServerStatus.text = "Waiting for configuration..."
    }

    override fun onPause() {
        super.onPause()
        companionConfigServer.stop()
        remotePairingManager.stopPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        companionConfigServer.stop()
        remotePairingManager.stopPolling()
    }

    private fun startEntranceAnimations() {
        // Initial state
        logoView.alpha = 0f
        logoView.translationY = -50f
        
        loginContainer.alpha = 0f
        loginContainer.translationY = 100f

        // Animate
        logoView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        loginContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
    
    private fun onLoginClick() {
        if (validateInputs()) {
            val server = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            performLogin(server, username, password)
        }
    }
    
    private fun performLogin(server: String, username: String, password: String) {
        btnLogin.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        android.util.Log.d("LoginFragment", "Starting login: server=$server, user=$username")
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Initialize repository
                repository.initialize(server, username, password)
                
                // Try login first
                val loginResult = withContext(Dispatchers.IO) {
                    repository.login(username, password)
                }
                
                if (loginResult.isSuccess) {
                    credentialsPrefs.saveCredentials(server, username, password)
                    navigateToInitialSync()
                } else {
                    val errorMsg = "Login failed: ${loginResult.exceptionOrNull()?.message}"
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    btnLogin.isEnabled = true
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                btnLogin.isEnabled = true
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun navigateToInitialSync() {
        if (!isAdded) return
        requireActivity().supportFragmentManager.commit {
            replace(R.id.content_container, InitialSyncFragment())
        }
    }
    
    private fun validateInputs(): Boolean {
        val server = etServerUrl.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        
        if (server.isEmpty()) {
            etServerUrl.error = "Server URL is required"
            return false
        }
        
        if (username.isEmpty()) {
            etUsername.error = "Username is required"
            return false
        }
        
        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            return false
        }
        
        return true
    }
}
