package com.tvonnet.debridxtreamiptv.ui.live

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LF-2: the inline **channel-search pill** lifted out of [LiveFragment]. The design's pill expands in
 * place into a channel-name field that drives the ViewModel's existing SearchChannels path (re-queries
 * the paging source for the current category) and debounces the query. It also filters the category
 * chip strip immediately — that chip state (`categoryChipQuery` + `refreshCategoryChips`) stays in the
 * Fragment (it is LF-4's, shared), so the controller pokes it through [onChipQueryChanged] /
 * [chipQueryIsNotEmpty]. Down-arrow out of the field hands focus to the channel grid via
 * [onFocusDownToChannels] (LF-6, still in the Fragment).
 *
 * Behaviour byte-identical to the old `setupSearchPill`/`toggleSearchPill`; only `this`/field refs were
 * rebound to [fragment], the passed views, [viewModel] and the callbacks.
 */
class LiveSearchController(
    private val fragment: Fragment,
    private val viewModel: LiveViewModel,
    private val chipSearch: View?,
    private val etChannelSearch: EditText?,
    private val tvSearchLabel: TextView?,
    callbacks: Callbacks,
) {
    /** The Fragment-owned reactions the search pill pokes (chip state + focus hand-off). */
    data class Callbacks(
        val onChipQueryChanged: (String) -> Unit,
        val chipQueryIsNotEmpty: () -> Boolean,
        val onFocusDownToChannels: () -> Unit,
    )

    private val onChipQueryChanged = callbacks.onChipQueryChanged
    private val chipQueryIsNotEmpty = callbacks.chipQueryIsNotEmpty
    private val onFocusDownToChannels = callbacks.onFocusDownToChannels

    private var searchDebounceJob: Job? = null

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    fun setup() {
        chipSearch?.setOnClickListener { toggle(open = etChannelSearch?.isVisible != true) }

        etChannelSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                // Filter the category chip strip immediately (cheap, local), and debounce the
                // channel query (hits the paging source + global index).
                onChipQueryChanged(query)
                searchDebounceJob?.cancel()
                searchDebounceJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    viewModel.onEvent(LiveEvent.SearchChannels(query))
                }
            }
        })

        // BACK closes the field rather than leaving the screen while typing.
        etChannelSearch?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    toggle(open = false)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onFocusDownToChannels()
                    true
                }
                else -> false
            }
        }
    }

    fun toggle(open: Boolean) {
        val field = etChannelSearch ?: return
        chipSearch?.isActivated = open
        tvSearchLabel?.isVisible = !open
        field.isVisible = open

        if (open) {
            field.requestFocus()
            val imm = fragment.requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchDebounceJob?.cancel()
            if (field.text.isNotEmpty()) {
                field.setText("")
                viewModel.onEvent(LiveEvent.ClearSearch)
            }
            // Restore the full category chip strip when search closes.
            if (chipQueryIsNotEmpty()) {
                onChipQueryChanged("")
            }
            chipSearch?.requestFocus()
        }
    }

    /** Used by the render path: collapse the pill if the user had typed something. */
    fun collapseIfHasText() {
        if (etChannelSearch?.text?.isNotEmpty() == true) toggle(open = false)
    }

    fun cancel() {
        searchDebounceJob?.cancel()
        searchDebounceJob = null
    }
}
