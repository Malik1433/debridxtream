package com.tvonnet.debridxtreamiptv.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
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
 * Cinematic 2.0 Login Screen for DebridXtream.
 *
 * Features an immersive blurred mosaic background and a centered glassmorphism login panel.
 * Optimized for high-end Android TV experience with radiant focus states.
 */
@AndroidEntryPoint
class LoginFragment : Fragment() {
    
    private lateinit var etServerUrl: com.google.android.material.textfield.TextInputEditText
    private lateinit var etUsername: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPassword: com.google.android.material.textfield.TextInputEditText
    private lateinit var tilServerUrl: com.google.android.material.textfield.TextInputLayout
    private lateinit var tilUsername: com.google.android.material.textfield.TextInputLayout
    private lateinit var tilPassword: com.google.android.material.textfield.TextInputLayout
    private lateinit var btnLogin: View
    private lateinit var btnSetupPhone: View
    private lateinit var progressBar: ProgressBar
    private var loginInProgress = false
    private var autoSyncLoginAttempted = false

    // UI Panels for Cinematic 2.0
    private lateinit var bgMosaicContainer: View
    private lateinit var ivBgMosaic: ImageView
    private lateinit var loginContainer: View
    private lateinit var panelRight: View
    
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
        tilServerUrl = view.findViewById(R.id.til_server_url)
        tilUsername = view.findViewById(R.id.til_username)
        tilPassword = view.findViewById(R.id.til_password)
        btnLogin = view.findViewById(R.id.btn_login)
        btnSetupPhone = view.findViewById(R.id.btn_setup_phone)
        progressBar = view.findViewById(R.id.progress_bar)

        // Cinematic Containers
        bgMosaicContainer = view.findViewById(R.id.bg_mosaic_container)
        ivBgMosaic = view.findViewById(R.id.iv_bg_mosaic)
        loginContainer = view.findViewById(R.id.card_login_container)
        panelRight = view.findViewById(R.id.panel_right)
        
        btnLogin.setOnClickListener {
            onLoginClick()
        }
        
        btnSetupPhone.setOnClickListener {
            onSetupPhoneClick()
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onLoginClick()
                true
            } else {
                false
            }
        }

        setupFocusListeners()
        startEntranceAnimations()
        etServerUrl.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        checkAutoSyncCredentials()
    }

    private fun checkAutoSyncCredentials() {
        if (loginInProgress || autoSyncLoginAttempted) return

        val server = credentialsPrefs.getServerUrl()
        val user = credentialsPrefs.getUsername()
        val pass = credentialsPrefs.getPassword()
        val loggedIn = credentialsPrefs.isLoggedIn()

        if (!loggedIn && !server.isNullOrEmpty() && !user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            android.util.Log.i("LoginFragment", "Auto-sync credentials detected! Triggering login...")
            autoSyncLoginAttempted = true
            
            etServerUrl.setText(server)
            etUsername.setText(user)
            etPassword.setText(pass)

            validateInputs()?.let { normalizedServer ->
                performLogin(normalizedServer, user.trim(), pass)
            }
        }
    }

    /**
     * Enhanced Focus Listeners: Radiant Glow + Scale + Elevation
     */
    private fun setupFocusListeners() {
        val viewsToScale = listOf(
            etServerUrl, etUsername, etPassword, btnLogin, btnSetupPhone
        )
        
        viewsToScale.forEach { focusView ->
            focusView.setOnFocusChangeListener { v, hasFocus ->
                // Visual feedback
                val targetScale = if (hasFocus) 1.03f else 1.0f
                val targetZ = if (hasFocus) 12f else 2f
                
                v.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationZ(targetZ)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()

                // Special handling for InputLayout focus glow (simulated via background swap if needed, 
                // but handled by Til boxStrokeColor in XML primarily. 
                // Here we can add extra glow effects if we had a layer-list background)
                if (v is com.google.android.material.textfield.TextInputEditText) {
                    val parent = v.parent.parent
                    if (parent is com.google.android.material.textfield.TextInputLayout) {
                        if (hasFocus) {
                            parent.setBoxStrokeWidth(3) // Thicker border on focus
                        } else {
                            parent.setBoxStrokeWidth(1)
                        }
                    }
                }
            }
        }
    }

    /**
     * Cinematic 2.0 Entrance Sequence:
     * 1. Background mosaic fades in and scales slightly (immersion)
     * 2. Glass panel unfolds from the center (scale + fade)
     * 3. Branding elements and fields emerge with staggered upward motion
     */
    private fun startEntranceAnimations() {
        // Initial hidden states
        bgMosaicContainer.alpha = 0f
        ivBgMosaic.scaleX = 1.1f
        ivBgMosaic.scaleY = 1.1f
        
        loginContainer.alpha = 0f
        loginContainer.scaleX = 0.9f
        loginContainer.scaleY = 0.9f

        val cardElements = listOf(
            view?.findViewById<View>(R.id.iv_logo),
            view?.findViewById<View>(R.id.tv_card_heading),
            view?.findViewById<View>(R.id.tv_card_subtitle),
            view?.findViewById<View>(R.id.til_server_url),
            view?.findViewById<View>(R.id.til_username),
            view?.findViewById<View>(R.id.til_password),
            btnLogin,
            view?.findViewById<View>(R.id.ll_or_divider),
            btnSetupPhone
        )
        cardElements.forEach { it?.alpha = 0f; it?.translationY = 32f }

        // 1. Background Entrance
        bgMosaicContainer.animate()
            .alpha(1f)
            .setDuration(1200)
            .start()
            
        ivBgMosaic.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(3000) // Slow drift effect
            .setInterpolator(android.view.animation.LinearInterpolator())
            .start()

        // 2. Glass Panel "Unfolding"
        loginContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .setStartDelay(200)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
            .start()

        // 3. Staggered Elements
        cardElements.forEachIndexed { index, element ->
            element?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(600)
                ?.setStartDelay(500L + (index * 60L))
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()
        }
    }
    
    private fun onLoginClick() {
        if (loginInProgress) return

        validateInputs()?.let { server ->
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()
            
            performLogin(server, username, password)
        }
    }
    
    private fun performLogin(server: String, username: String, password: String) {
        if (loginInProgress) return
        loginInProgress = true
        setLoginControlsEnabled(false)
        progressBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.initialize(server, username, password)
                
                val loginResult = withContext(Dispatchers.IO) {
                    repository.login(username, password)
                }
                
                if (loginResult.isSuccess) {
                    credentialsPrefs.saveCredentials(server, username, password)
                    GlobalConfig.baseUrl = server
                    GlobalConfig.username = username
                    GlobalConfig.password = password
                    
                    progressBar.visibility = View.GONE
                    navigateToInitialSync()
                } else {
                    val errorMsg = loginResult.exceptionOrNull()?.message ?: "Login failed"
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                    loginInProgress = false
                    setLoginControlsEnabled(true)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                loginInProgress = false
                setLoginControlsEnabled(true)
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
    
    private fun validateInputs(): String? {
        val server = etServerUrl.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()

        tilServerUrl.error = null
        tilUsername.error = null
        tilPassword.error = null
        
        if (server.isEmpty()) {
            setServerError("Server URL is required")
            return null
        }
        
        if (username.isEmpty()) {
            tilUsername.error = "Username is required"
            return null
        }
        
        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            return null
        }
        
        return normalizeServerUrl(server)
    }

    private fun normalizeServerUrl(rawServer: String): String? {
        val candidate = if (rawServer.contains("://")) rawServer else "http://$rawServer"
        val uri = Uri.parse(candidate)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host

        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            setServerError("Enter a valid server URL")
            return null
        }

        return candidate.trimEnd('/')
    }

    private fun setServerError(message: String) {
        tilServerUrl.error = message
    }

    private fun setLoginControlsEnabled(enabled: Boolean) {
        etServerUrl.isEnabled = enabled
        etUsername.isEnabled = enabled
        etPassword.isEnabled = enabled
        btnLogin.isEnabled = enabled
        btnSetupPhone.isEnabled = enabled
    }
}
