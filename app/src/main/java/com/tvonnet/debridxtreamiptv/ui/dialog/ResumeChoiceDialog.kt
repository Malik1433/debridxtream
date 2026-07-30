package com.tvonnet.debridxtreamiptv.ui.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.tvonnet.debridxtreamiptv.R
import java.util.Locale

/**
 * "Resume from 42:15, or start over?" — asked once a source has been chosen.
 *
 * The movie detail page no longer has a Resume button. A button that silently changes what it does
 * depending on saved state is the thing that broke here: once a resume existed, Play resumed and the
 * source picker became unreachable from the screen entirely. So Play always means "choose a source",
 * and *where to start* is a separate question, asked after — which is also the order the user is
 * actually thinking in: what to play, then from where.
 *
 * Built on the [ExitDialog] pattern (fixed-width CardView in a DialogFragment) rather than an
 * AlertDialog, whose minimum width renders as a half-screen slab on this TV.
 */
class ResumeChoiceDialog : DialogFragment() {

    /** Set by the caller; not part of the arguments because it cannot survive process death anyway. */
    var onChoice: ((resume: Boolean) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return inflater.inflate(R.layout.dialog_resume_choice, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val positionMs = arguments?.getLong(ARG_POSITION_MS) ?: 0L
        view.findViewById<TextView>(R.id.tv_resume_choice_title).text =
            arguments?.getString(ARG_TITLE).orEmpty()
        view.findViewById<TextView>(R.id.tv_resume_choice_message).text =
            getString(R.string.resume_choice_message, formatPosition(positionMs))

        val resume = view.findViewById<Button>(R.id.btn_resume)
        val startOver = view.findViewById<Button>(R.id.btn_start_over)

        resume.text = getString(R.string.resume_choice_resume_at, formatPosition(positionMs))
        resume.setOnClickListener { choose(true) }
        startOver.setOnClickListener { choose(false) }

        listOf(resume, startOver).forEach { it.applyFocusScale() }

        // Resume is the common intent, so it takes focus — one OK press does the usual thing.
        resume.requestFocus()
    }

    /**
     * Grow on focus, the way the detail page's own buttons do.
     *
     * The themed backgrounds only change their stroke when focused — cyan instead of faint white on
     * the glass button — which is far too quiet to read from across a room. Everywhere else in this
     * app that difference is carried by the button also scaling up, so it is carried here too rather
     * than inventing a new focus language for one dialog.
     */
    private fun View.applyFocusScale() {
        setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                .setDuration(FOCUS_ANIM_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun choose(resume: Boolean) {
        // Fire before dismiss: dismissing can detach this fragment, and a callback held on a
        // detached fragment is the classic way one of these quietly does nothing.
        onChoice?.invoke(resume)
        onChoice = null
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** h:mm:ss past an hour, m:ss below it — how a player shows a position. */
    private fun formatPosition(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        const val TAG = "ResumeChoiceDialog"
        private const val ARG_TITLE = "title"
        private const val ARG_POSITION_MS = "positionMs"

        /** Matches MovieDetailActivity.setupFocusAnimations, so focus feels the same either side. */
        private const val FOCUS_SCALE = 1.05f
        private const val FOCUS_ANIM_MS = 200L

        /**
         * @param onChoice true to resume at [positionMs], false to start from the beginning. Not
         *   called if the user backs out — the caller should treat that as "play nothing".
         */
        fun show(
            fragmentManager: FragmentManager,
            title: String?,
            positionMs: Long,
            onChoice: (resume: Boolean) -> Unit
        ) {
            ResumeChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putLong(ARG_POSITION_MS, positionMs)
                }
                this.onChoice = onChoice
            }.show(fragmentManager, TAG)
        }
    }
}
