package com.tvonnet.debridxtreamiptv.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.companion.AccountSignInOverlayController
import com.tvonnet.debridxtreamiptv.ui.companion.LoginQrOverlayController
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cinematic login screen — Claude Design "Login Screen.dc.html".
 *
 * Two-panel split: branding (DX tile, app name, feature chips) on the left,
 * custom-styled credential fields + Sign In on the right, ambient glow blobs
 * and a scan line behind everything, and an inline Phone/QR pairing overlay.
 */
@AndroidEntryPoint
class LoginFragment : Fragment() {

    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: View
    private lateinit var btnSetupPhone: View
    private lateinit var progressBar: ProgressBar
    private lateinit var ivLoginPlay: ImageView
    private lateinit var tvLoginText: TextView
    private lateinit var errorContainer: View
    private lateinit var tvError: TextView
    private lateinit var btnTogglePassword: ImageView

    private var loginInProgress = false
    private var autoSyncLoginAttempted = false
    private var passwordVisible = false

    private var qrOverlay: LoginQrOverlayController? = null
    private var accountOverlay: AccountSignInOverlayController? = null
    private val ambientAnimators = mutableListOf<ValueAnimator>()

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

        etServerUrl = view.findViewById(R.id.et_server_url)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnLogin = view.findViewById(R.id.btn_login)
        btnSetupPhone = view.findViewById(R.id.btn_setup_phone)
        progressBar = view.findViewById(R.id.progress_bar)
        ivLoginPlay = view.findViewById(R.id.iv_login_play)
        tvLoginText = view.findViewById(R.id.tv_login_text)
        errorContainer = view.findViewById(R.id.ll_login_error)
        tvError = view.findViewById(R.id.tv_login_error)
        btnTogglePassword = view.findViewById(R.id.btn_toggle_password)

        bindBranding(view)
        setupFields(view)
        setupButtons(view)
        setupQrOverlay(view)
        startEntranceAnimations(view)
        startAmbientAnimations(view)

        etServerUrl.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        checkAutoSyncCredentials()
    }

    override fun onDestroyView() {
        ambientAnimators.forEach { it.cancel() }
        ambientAnimators.clear()
        qrOverlay?.release()
        qrOverlay = null
        accountOverlay = null
        // The sync object outlives this fragment, so a callback left behind would hold the view.
        com.tvonnet.debridxtreamiptv.ui.companion.AccountPlaylistSync.onCredentialsApplied = null
        super.onDestroyView()
    }

    // ── Branding (left panel) ──────────────────────────────────────────────

    private fun bindBranding(view: View) {
        val title = view.findViewById<TextView>(R.id.tv_welcome_title)
        val name = SpannableString("DebridXtream").apply {
            setSpan(ForegroundColorSpan(CYAN), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        title.text = name

        view.findViewById<TextView>(R.id.tv_login_version).text =
            "v${BuildConfig.VERSION_NAME} · BUILD ${BuildConfig.VERSION_CODE}"

        view.findViewById<View>(R.id.view_grid).background = GridDrawable(
            spacingPx = 30f * resources.displayMetrics.density,
            color = 0x0600F0FF
        )
    }

    // ── Fields ─────────────────────────────────────────────────────────────

    private fun setupFields(view: View) {
        val fieldGroups = listOf(
            Triple(view.findViewById<View>(R.id.til_server_url), view.findViewById<TextView>(R.id.tv_label_server), view.findViewById<ImageView>(R.id.iv_icon_server)) to etServerUrl,
            Triple(view.findViewById<View>(R.id.til_username), view.findViewById<TextView>(R.id.tv_label_username), view.findViewById<ImageView>(R.id.iv_icon_username)) to etUsername,
            Triple(view.findViewById<View>(R.id.til_password), view.findViewById<TextView>(R.id.tv_label_password), view.findViewById<ImageView>(R.id.iv_icon_password)) to etPassword
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hideError()
                updateLoginButtonState()
            }
        }

        fieldGroups.forEach { (chrome, editText) ->
            val (row, label, icon) = chrome
            editText.addTextChangedListener(watcher)
            editText.setOnFocusChangeListener { _, hasFocus ->
                row.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_login_field_focused else R.drawable.bg_login_field
                )
                row.elevation = if (hasFocus) 6f * resources.displayMetrics.density else 0f
                label.setTextColor(if (hasFocus) CYAN else FAINT)
                icon.setColorFilter(if (hasFocus) CYAN else FAINT)
                if (hasFocus) hideError()
            }
        }

        // Password masking: ● bullets with wide tracking; tighter when revealed.
        etPassword.letterSpacing = 0.15f
        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }
        btnTogglePassword.setOnFocusChangeListener { v, hasFocus ->
            (v as ImageView).setColorFilter(if (hasFocus) CYAN else MUTED)
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onLoginClick()
                true
            } else {
                false
            }
        }
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        etPassword.transformationMethod =
            if (passwordVisible) null else PasswordTransformationMethod.getInstance()
        etPassword.letterSpacing = if (passwordVisible) 0.01f else 0.15f
        etPassword.setSelection(etPassword.text.length)
        btnTogglePassword.setImageResource(
            if (passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye
        )
        btnTogglePassword.contentDescription =
            if (passwordVisible) "Hide password" else "Show password"
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    private fun setupButtons(view: View) {
        btnLogin.setOnClickListener { onLoginClick() }
        btnSetupPhone.setOnClickListener { qrOverlay?.show() }

        btnLogin.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .translationY(if (hasFocus) -1f * resources.displayMetrics.density else 0f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        updateLoginButtonState()
    }

    private fun allFieldsFilled(): Boolean =
        etServerUrl.text.toString().trim().isNotEmpty() &&
            etUsername.text.toString().trim().isNotEmpty() &&
            etPassword.text.toString().isNotEmpty()

    private fun updateLoginButtonState() {
        if (loginInProgress) return
        val filled = allFieldsFilled()
        btnLogin.setBackgroundResource(
            if (filled) R.drawable.sel_login_btn_primary else R.drawable.sel_login_btn_secondary
        )
        ivLoginPlay.setColorFilter(if (filled) INK else FAINT)
        tvLoginText.setTextColor(if (filled) INK else FAINT)
    }

    private fun setLoading(loading: Boolean) {
        loginInProgress = loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        ivLoginPlay.visibility = if (loading) View.GONE else View.VISIBLE
        tvLoginText.text = if (loading) "Connecting…" else "Sign In"
        etServerUrl.isEnabled = !loading
        etUsername.isEnabled = !loading
        etPassword.isEnabled = !loading
        btnTogglePassword.isEnabled = !loading
        btnSetupPhone.isEnabled = !loading
        if (!loading) updateLoginButtonState()
    }

    // ── Error banner ───────────────────────────────────────────────────────

    private fun showError(message: String) {
        tvError.text = message
        if (errorContainer.visibility != View.VISIBLE) {
            errorContainer.visibility = View.VISIBLE
            errorContainer.alpha = 0f
            errorContainer.translationY = -8f * resources.displayMetrics.density
            errorContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideError() {
        if (errorContainer.visibility == View.VISIBLE) {
            errorContainer.visibility = View.GONE
        }
    }

    // ── Entrance + ambient animations ──────────────────────────────────────

    private fun startEntranceAnimations(view: View) {
        val density = resources.displayMetrics.density

        val branding = view.findViewById<View>(R.id.ll_title_container)
        branding.alpha = 0f
        branding.translationY = -9f * density
        branding.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val form = view.findViewById<View>(R.id.card_login_container)
        form.alpha = 0f
        form.translationX = 16f * density
        form.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun startAmbientAnimations(view: View) {
        val density = resources.displayMetrics.density

        fun blob(id: Int, dx: Float, dy: Float, halfCycleMs: Long) {
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                view.findViewById<View>(id),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx * density),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dy * density)
            ).apply {
                duration = halfCycleMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            animator.start()
            ambientAnimators += animator
        }

        blob(R.id.view_blob_1, -9f, 7f, 4500)   // 9s loop
        blob(R.id.view_blob_2, 7f, -5f, 5500)   // 11s loop
        blob(R.id.view_blob_3, -9f, 7f, 6500)   // 13s loop, reverse phase

        val scanLine = view.findViewById<View>(R.id.view_scanline)
        view.post {
            val travel = view.height + 40f * density
            val scan = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedFraction
                    scanLine.translationY = -20f * density + f * travel
                    scanLine.alpha = when {
                        f < 0.08f -> f / 0.08f * 0.6f
                        f > 0.92f -> (1f - f) / 0.08f * 0.6f
                        else -> 0.6f
                    }
                }
            }
            scan.start()
            ambientAnimators += scan
        }
    }

    // ── QR pairing overlay ─────────────────────────────────────────────────

    private fun setupQrOverlay(view: View) {
        qrOverlay = LoginQrOverlayController(view.findViewById(R.id.qr_overlay)) {
            autoSyncLoginAttempted = false
            checkAutoSyncCredentials()
        }
        setupAccountSignIn(view)
        // §7 U6: credentials can also arrive from the customer's ACCOUNT, with nobody touching this
        // screen. Same reaction as a QR pairing — otherwise they land in prefs and the customer keeps
        // staring at a login form. Posted to the view so we are on the main thread and gone if the
        // fragment is.
        com.tvonnet.debridxtreamiptv.ui.companion.AccountPlaylistSync.onCredentialsApplied = {
            view.post {
                if (isAdded) {
                    // The QR now leads to the account claim page, which never writes to
                    // device_codes — so the overlay's own listener never completes. Close it here,
                    // or it waits for a hand-off that already happened.
                    qrOverlay?.onConfiguredElsewhere()
                    autoSyncLoginAttempted = false
                    checkAutoSyncCredentials()
                }
            }
        }
    }

    /**
     * §9 W1: account sign-in, plus the device key that makes it unnecessary.
     *
     * The key is shown unconditionally because it is the OTHER way to be entitled — a reseller
     * activates it and the customer never needs an account at all. Two ways in, one question.
     */
    private fun setupAccountSignIn(view: View) {
        val overlayView = view.findViewById<View>(R.id.account_overlay) ?: return
        accountOverlay = AccountSignInOverlayController(overlayView) {
            // Signing in is all this does. AccountPlaylistSync sees a real (non-anonymous) user and
            // reads that account's playlists; the existing auto-login then applies them.
            autoSyncLoginAttempted = false
            checkAutoSyncCredentials()
        }
        view.findViewById<View>(R.id.btn_account_signin)?.setOnClickListener {
            accountOverlay?.show()
        }
        view.findViewById<TextView>(R.id.tv_login_device_key)?.text = runCatching {
            "DEVICE KEY  " + com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
                .getInstance(requireContext()).activationCode
        }.getOrDefault("")
    }

    /**
     * Called by MainActivity's legacy onBackPressed override (which bypasses the
     * OnBackPressedDispatcher). @return true if Back was consumed (an overlay closed).
     */
    fun handleBackPress(): Boolean {
        accountOverlay?.takeIf { it.isShowing }?.let {
            it.hide()
            btnSetupPhone.requestFocus()
            return true
        }
        val overlay = qrOverlay ?: return false
        if (overlay.isShowing) {
            overlay.hide()
            btnSetupPhone.requestFocus()
            return true
        }
        return false
    }

    // ── Login flow ─────────────────────────────────────────────────────────

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

    private fun onLoginClick() {
        if (loginInProgress) return

        if (!allFieldsFilled()) {
            showError("Please fill in all three fields to continue.")
            return
        }

        validateInputs()?.let { server ->
            performLogin(server, etUsername.text.toString().trim(), etPassword.text.toString())
        }
    }

    private fun performLogin(server: String, username: String, password: String) {
        if (loginInProgress) return
        hideError()
        setLoading(true)

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
                    navigateToInitialSync()
                } else {
                    // Raw exception messages can embed the credentialed request URL — keep user-facing text stable.
                    setLoading(false)
                    showError("Login failed. Please check your server address and credentials.")
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginFragment", "Login failed", e)
                setLoading(false)
                showError("Login error. Please check your connection and try again.")
            }
        }
    }

    private fun navigateToInitialSync() {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.content_container, InitialSyncFragment())
        }
    }

    private fun validateInputs(): String? {
        val server = etServerUrl.text.toString().trim()
        if (server.isEmpty()) {
            showError("Server URL is required")
            return null
        }
        if (etUsername.text.toString().trim().isEmpty()) {
            showError("Username is required")
            return null
        }
        if (etPassword.text.toString().isEmpty()) {
            showError("Password is required")
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
            showError("Invalid server URL. Must start with http:// or https://")
            return null
        }

        return candidate.trimEnd('/')
    }

    /** Subtle 30dp grid over the left panel (design: 60px @ rgba(0,240,255,0.025)). */
    private class GridDrawable(private val spacingPx: Float, color: Int) : Drawable() {
        private val paint = Paint().apply {
            this.color = color
            strokeWidth = 1f
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            var x = b.left.toFloat()
            while (x <= b.right) {
                canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), paint)
                x += spacingPx
            }
            var y = b.top.toFloat()
            while (y <= b.bottom) {
                canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint)
                y += spacingPx
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        val CYAN = Color.parseColor("#00F0FF")
        val FAINT = Color.parseColor("#475569")
        val MUTED = Color.parseColor("#64748B")
        val INK = Color.parseColor("#041014")
    }
}
