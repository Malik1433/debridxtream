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
import kotlinx.coroutines.launch
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tvonnet.debridxtreamiptv.util.GlobalConfig

@AndroidEntryPoint
class LoginFragment : Fragment() {
    
    private lateinit var etServerUrl: com.google.android.material.textfield.TextInputEditText
    private lateinit var etUsername: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPassword: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnLogin: View
    private lateinit var btnSetupPhone: View
    private lateinit var progressBar: ProgressBar
    private lateinit var logoView: View
    private lateinit var loginContainer: View
    
    @Inject
    lateinit var repository: XtreamRepository
    
    @Inject
    lateinit var credentialsPrefs: CredentialsPreferences
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Dependencies injected by Hilt
        
        etServerUrl = view.findViewById(R.id.et_server_url)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnLogin = view.findViewById(R.id.btn_login)
        btnSetupPhone = view.findViewById(R.id.btn_setup_phone)
        progressBar = view.findViewById(R.id.progress_bar)
        logoView = view.findViewById(R.id.iv_logo)
        loginContainer = view.findViewById(R.id.card_login_container)
        
        btnLogin.setOnClickListener {
            onLoginClick()
        }
        
        btnSetupPhone.setOnClickListener {
            onSetupPhoneClick()
        }

        startEntranceAnimations()
    }

    override fun onResume() {
        super.onResume()
        checkAutoSyncCredentials()
    }

    /**
     * Checks if credentials were saved via Companion Sync and triggers auto-login
     */
    private fun checkAutoSyncCredentials() {
        val server = credentialsPrefs.getServerUrl()
        val user = credentialsPrefs.getUsername()
        val pass = credentialsPrefs.getPassword()
        val loggedIn = credentialsPrefs.isLoggedIn()

        if (!loggedIn && !server.isNullOrEmpty() && !user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            android.util.Log.i("LoginFragment", "Auto-sync credentials detected! Triggering login...")
            
            // Fill UI fields so user sees what's happening
            etServerUrl.setText(server)
            etUsername.setText(user)
            etPassword.setText(pass)
            
            // Initialize GlobalConfig immediately
            GlobalConfig.baseUrl = server
            GlobalConfig.username = user
            GlobalConfig.password = pass
            
            // Trigger login
            performLogin(server, user, pass)
        }
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
                android.util.Log.d("LoginFragment", "Repository initialized")
                
                // Try login first
                val loginResult = withContext(Dispatchers.IO) {
                    repository.login(username, password)
                }
                android.util.Log.d("LoginFragment", "Login result: ${loginResult.isSuccess}")
                
                if (loginResult.isSuccess) {
                    android.util.Log.d("LoginFragment", "Login successful, saving credentials")
                    // Save credentials
                    credentialsPrefs.saveCredentials(server, username, password)
                    
                    // Initialize GlobalConfig for session
                    GlobalConfig.baseUrl = server
                    GlobalConfig.username = username
                    GlobalConfig.password = password
                    
                    progressBar.visibility = View.GONE
                    android.util.Log.d("LoginFragment", "Navigating to initial sync")
                    navigateToInitialSync()
                } else {
                    val errorMsg = "Login failed: ${loginResult.exceptionOrNull()?.message}"
                    android.util.Log.e("LoginFragment", errorMsg, loginResult.exceptionOrNull())
                    Toast.makeText(
                        context,
                        errorMsg,
                        Toast.LENGTH_LONG
                    ).show()
                    btnLogin.isEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginFragment", "Exception during login", e)
                Toast.makeText(
                    context,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                btnLogin.isEnabled = true
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun onSetupPhoneClick() {
        android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.companion.CompanionSetupActivity::class.java).also {
            startActivity(it)
        }
    }
    
    private fun navigateToInitialSync() {
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
