package com.tvonnet.debridxtreamiptv.ui.debrid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Fragment for Real-Debrid device-code authentication
 * Shows device code, verification URL, and polls for user authorization
 */
@AndroidEntryPoint
class DebridAuthFragment : Fragment() {
    
    private val viewModel: DebridAuthViewModel by viewModels()
    
    private lateinit var tvDeviceCode: TextView
    private lateinit var tvVerificationUrl: TextView
    private lateinit var tvInstructions: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStartAuth: Button
    private lateinit var btnCancel: Button
    private lateinit var cardDeviceCode: CardView
    private lateinit var btnShowManual: Button
    private lateinit var llManualEntry: android.view.View
    private lateinit var etApiKey: android.widget.EditText
    private lateinit var btnSubmitManual: Button
    private lateinit var llAuthButtons: android.view.View
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_debrid_auth, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        observeViewModel()
    }
    
    private fun initializeViews(view: View) {
        tvDeviceCode = view.findViewById(R.id.tv_device_code)
        tvVerificationUrl = view.findViewById(R.id.tv_verification_url)
        tvInstructions = view.findViewById(R.id.tv_instructions)
        progressBar = view.findViewById(R.id.progress_auth)
        btnStartAuth = view.findViewById(R.id.btn_start_auth)
        btnCancel = view.findViewById(R.id.btn_cancel_auth)
        cardDeviceCode = view.findViewById(R.id.card_device_code)
        
        btnStartAuth.setOnClickListener {
            viewModel.startAuth()
        }
        
        btnCancel.setOnClickListener {
            viewModel.cancelAuth()
            parentFragmentManager.popBackStack()
        }

        btnShowManual = view.findViewById(R.id.btn_show_manual)
        llManualEntry = view.findViewById(R.id.ll_manual_entry)
        etApiKey = view.findViewById(R.id.et_api_key)
        btnSubmitManual = view.findViewById(R.id.btn_submit_manual)
        llAuthButtons = view.findViewById(R.id.ll_auth_buttons)

        btnShowManual.setOnClickListener {
            llManualEntry.visibility = if (llManualEntry.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (llManualEntry.visibility == View.VISIBLE) {
                etApiKey.requestFocus()
            }
        }

        btnSubmitManual.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                viewModel.loginWithStaticToken(key)
            } else {
                Toast.makeText(requireContext(), "Please enter API Key", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.authState.collect { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: DebridAuthState) {
        android.util.Log.d("DebridAuthFragment", "updateUI called with state: ${state::class.simpleName}")
        when (state) {
            is DebridAuthState.Idle -> {
                progressBar.visibility = View.GONE
                cardDeviceCode.visibility = View.GONE
                tvDeviceCode.visibility = View.GONE
                tvVerificationUrl.visibility = View.GONE
                tvInstructions.text = getString(R.string.debrid_auth_start_message)
                btnStartAuth.visibility = View.VISIBLE
                btnStartAuth.isEnabled = true
                // Reset instruction text color in case it was red from error state
                tvInstructions.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
            is DebridAuthState.FetchingCode -> {
                progressBar.visibility = View.VISIBLE
                cardDeviceCode.visibility = View.GONE
                tvDeviceCode.visibility = View.GONE
                tvVerificationUrl.visibility = View.GONE
                tvInstructions.text = getString(R.string.debrid_auth_fetching)
                btnStartAuth.visibility = View.GONE
            }
            is DebridAuthState.ShowingCode -> {
                android.util.Log.d(
                    "DebridAuthFragment",
                    "Showing code: ${maskCode(state.deviceCode)}, URL: ${state.verificationUrl}"
                )
                progressBar.visibility = View.VISIBLE
                cardDeviceCode.visibility = View.VISIBLE
                tvDeviceCode.visibility = View.VISIBLE
                tvVerificationUrl.visibility = View.VISIBLE
                tvDeviceCode.text = state.deviceCode
                tvVerificationUrl.text = state.verificationUrl
                tvInstructions.text = getString(R.string.debrid_auth_instructions)
                btnStartAuth.visibility = View.GONE
                // Reset instruction text color in case it was red from error state
                tvInstructions.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
            is DebridAuthState.Success -> {
                progressBar.visibility = View.GONE
                cardDeviceCode.visibility = View.GONE
                Toast.makeText(requireContext(), R.string.debrid_auth_success, Toast.LENGTH_SHORT).show()
                // Navigate back to Debrid fragment
                parentFragmentManager.popBackStack()
            }
            is DebridAuthState.Error -> {
                progressBar.visibility = View.GONE
                cardDeviceCode.visibility = View.GONE
                tvDeviceCode.visibility = View.GONE
                tvVerificationUrl.visibility = View.GONE
                tvInstructions.text = getString(R.string.debrid_auth_error_format, state.message)
                tvInstructions.setTextColor(resources.getColor(android.R.color.holo_red_light, null))
                btnStartAuth.visibility = View.VISIBLE
                btnStartAuth.isEnabled = true
                llAuthButtons.visibility = View.VISIBLE
                btnShowManual.visibility = View.VISIBLE
                android.util.Log.e("DebridAuthFragment", "Authentication error: ${state.message}")
            }
        }
        
        // Hide manual entry during active OAuth or Fetching
        if (state is DebridAuthState.FetchingCode || state is DebridAuthState.ShowingCode) {
            btnShowManual.visibility = View.GONE
            llManualEntry.visibility = View.GONE
            llAuthButtons.visibility = if (state is DebridAuthState.ShowingCode) View.VISIBLE else View.GONE
        } else if (state is DebridAuthState.Idle) {
            btnShowManual.visibility = View.VISIBLE
            llAuthButtons.visibility = View.VISIBLE
        }
    }

    private fun maskCode(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "n/a"
        return when {
            trimmed.length <= 2 -> "**"
            else -> trimmed.take(2) + "***"
        }
    }
}
