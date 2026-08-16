package com.tvonnet.debridxtreamiptv.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.tvonnet.debridxtreamiptv.R

class ExitDialog : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Ensure transparent background for the dialog window to show the CardView corners
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        
        return inflater.inflate(R.layout.dialog_exit_app, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cancel = view.findViewById<View>(R.id.btn_cancel)

        cancel.setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btn_exit).setOnClickListener {
            activity?.finish()
        }

        // Focus the safe action by default. Posted rather than called inline: on TV the dialog
        // window has not taken focus yet at onViewCreated, so an immediate requestFocus() can be
        // dropped and the user lands on the destructive button instead.
        cancel.post { cancel.requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val TAG = "ExitDialog"
        
        fun newInstance(): ExitDialog {
            return ExitDialog()
        }
    }
}
