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

/**
 * Premium split-screen login screen for DebridXtream.
 *
 * Layout: Left branding panel | Right login card
 * Provides two entry paths: manual credentials and QR/Mobile connect.
 */
@AndroidEntryPoint
class LoginFragment : Fragment() {
    
    private lateinit var etServerUrl: com.google.android.material.textfield.TextInputEditText
    private lateinit var etUsername: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPassword: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnLogin: View
    private lateinit var btnSetupPhone: View
    private lateinit var progressBar: ProgressBar

    // Split-screen panels
    private lateinit var panelLeft: View
    private lateinit var panelRight: View
    private lateinit var llTitleContainer: View
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
        
        // Input fields
        etServerUrl = view.findViewById(R.id.et_server_url)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnLogin = view.findViewById(R.id.btn_login)
        btnSetupPhone = view.findViewById(R.id.btn_setup_phone)
        progressBar = view.findViewById(R.id.progress_bar)

        // Panels for animation
        panelLeft = view.findViewById(R.id.panel_left)
        panelRight = view.findViewById(R.id.panel_right)
        llTitleContainer = view.findViewById(R.id.ll_title_container)
        loginContainer = view.findViewById(R.id.card_login_container)
        
        btnLogin.setOnClickListener {
            onLoginClick()
        }
        
        btnSetupPhone.setOnClickListener {
            onSetupPhoneClick()
        }

        setupFocusListeners()
        startEntranceAnimations()
    }

    override fun onResume() {
        super.onResume()
        checkAutoSyncCredentials()
    }

    /**
     * Checks if credentials were saved via Companion Sync and triggers auto-login.
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

    /**
     * Adds scale + elevation effects on D-pad focus for all interactive elements.
     */
    private fun setupFocusListeners() {
        val viewsToScale = listOf(
            etServerUrl, etUsername, etPassword, btnLogin, btnSetupPhone
        )
        
        viewsToScale.forEach { focusView ->
            focusView.setOnFocusChangeListener { v, hasFocus ->
                val targetScale = if (hasFocus) 1.03f else 1.0f
                val targetZ = if (hasFocus) 6f else 0f
                v.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationZ(targetZ)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * Choreographed entrance animation sequence for both panels.
     *
     * Sequence:
     *   1. Left panel slides in from left with fade
     *   2. Right panel slides in from right with fade (slight delay)
     *   3. Card content fields appear with staggered fade-up
     */
    private fun startEntranceAnimations() {
        // Initial states
        panelLeft.alpha = 0f
        panelLeft.translationX = -80f
        
        panelRight.alpha = 0f
        panelRight.translationX = 80f

        loginContainer.alpha = 0f
        loginContainer.scaleX = 0.96f
        loginContainer.scaleY = 0.96f

        val cardElements = listOf(
            view?.findViewById<View>(R.id.tv_card_heading),
            view?.findViewById<View>(R.id.tv_card_subtitle),
            view?.findViewById<View>(R.id.til_server_url),
            view?.findViewById<View>(R.id.til_username),
            view?.findViewById<View>(R.id.til_password),
            btnLogin,
            view?.findViewById<View>(R.id.ll_or_divider),
            btnSetupPhone
        )
        cardElements.forEach { it?.alpha = 0f; it?.translationY = 24f }

        // 1. Left panel entrance
        panelLeft.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(900)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()

        // 2. Right panel entrance (delayed)
        panelRight.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(900)
            .setStartDelay(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()

        // 3. Card container scale-in
        loginContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(400)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            .start()

        // 4. Staggered card elements
        cardElements.forEachIndexed { index, element ->
            element?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(500)
                ?.setStartDelay(600L + (index * 80L))
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()
        }
    }
    
    private fun onLoginClick() {
        if (validateInputs()) {
            val server = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            performLogin(server, username, password)
        }
    }
    
    /**
     * Performs the actual IPTV login against the Xtream repository.
     *
     * @param server  The server URL.
     * @param username The user's username.
     * @param password The user's password.
     */
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
    
    /**
     * Validates the three required input fields before attempting login.
     *
     * @return true if all fields are non-empty.
     */
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
