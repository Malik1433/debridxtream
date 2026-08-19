package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R

/**
 * The phone's search field — the native keyboard, which is the only keyboard a handset should ever
 * be shown (design frame 1e; the phone rulebook bans the on-screen D-pad grid by name).
 *
 * Present only in the phone layout. [isPresent] is false on the television, where the 36-key grid
 * in layout-television/overlay_stremio_search.xml still drives the query and this object does
 * nothing at all.
 *
 * It reports keystrokes and nothing else: the debounce, the search and the results all stay in
 * [StremioSearchOverlay], so both devices run the identical search path.
 */
internal class StremioSearchInput(
    root: View,
    private val onQuery: (String) -> Unit,
    private val onBack: () -> Unit,
) {
    private val input: EditText? = root.findViewById(R.id.search_input)
    private val clear: View? = root.findViewById(R.id.search_clear)

    val isPresent: Boolean get() = input != null

    /** Set while we are writing the field ourselves, so the watcher does not re-fire the search. */
    private var selfEdit = false

    init {
        input?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clear?.isVisible = text.isNotEmpty()
                if (!selfEdit) onQuery(text)
            }
        })

        // The keyboard's own Search key. Results are already live from the watcher, so this only
        // puts the keyboard away — the customer asked to see the list, not to keep typing.
        input?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        clear?.setOnClickListener { setText("") }
        root.findViewById<View>(R.id.search_back)?.setOnClickListener { onBack() }
    }

    /** Writes the field without re-triggering the watcher's search, then reports once. */
    fun setText(text: String) {
        val field = input ?: return
        selfEdit = true
        field.setText(text)
        field.setSelection(text.length)
        selfEdit = false
        clear?.isVisible = text.isNotEmpty()
        onQuery(text)
    }

    /**
     * Raise the keyboard with the field.
     *
     * Through the insets controller rather than [InputMethodManager.showSoftInput], which is
     * advisory: SHOW_IMPLICIT is refused often enough — a view that is not focused *yet*, a window
     * still animating in — that the customer lands on a search screen with no keyboard and has to
     * tap the field they are already looking at. The IMM call stays as the fallback for hosts
     * where the controller is unavailable.
     */
    fun focusAndShowKeyboard() {
        val field = input ?: return
        field.requestFocus()
        field.post {
            val shown = ViewCompat.getWindowInsetsController(field)
                ?.also { it.show(WindowInsetsCompat.Type.ime()) } != null
            if (!shown) {
                field.context.getSystemService<InputMethodManager>()
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideKeyboard() {
        val field = input ?: return
        ViewCompat.getWindowInsetsController(field)?.hide(WindowInsetsCompat.Type.ime())
        field.clearFocus()
        field.context.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(field.windowToken, 0)
    }
}
