package com.tvonnet.debridxtreamiptv.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Picks which provider categories become rows on Home.
 *
 * **Instant-apply.** Every tick is written to [HomePreferences] immediately and BACK closes the
 * dialog — there is no Save button. A provider can ship hundreds of categories, and the old
 * Save/Cancel pair sat *below* that list: ticking one box meant scrolling all the way to the bottom
 * to commit it. That is the rule for the whole of Settings — toggles and pickers already behave
 * this way, this dialog was the one exception.
 */
@AndroidEntryPoint
class CategorySelectionDialog : DialogFragment() {

    @Inject
    lateinit var homePreferences: HomePreferences

    private var categories: List<XtreamCategory> = emptyList()
    private var type: String = "movie" // "movie", "series", "live"
    private var onCategoriesSelected: (() -> Unit)? = null

    /** Set while the dialog is open so the host is told once, on close, not once per tick. */
    private var changed = false

    companion object {
        const val TAG = "CategorySelectionDialog"

        fun newInstance(type: String, categories: List<XtreamCategory>): CategorySelectionDialog {
            val fragment = CategorySelectionDialog()
            fragment.type = type
            fragment.categories = categories
            return fragment
        }
    }

    fun setCallback(callback: () -> Unit) {
        onCategoriesSelected = callback
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val selectedIds = readSelection().toMutableSet()

        val view = android.view.LayoutInflater.from(requireContext())
            .inflate(com.tvonnet.debridxtreamiptv.R.layout.dialog_category_selection, null)

        val rvCategories = view.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tvonnet.debridxtreamiptv.R.id.rv_categories
        )
        val tvTitle = view.findViewById<android.widget.TextView>(
            com.tvonnet.debridxtreamiptv.R.id.tv_dialog_title
        )

        fun renderTitle() {
            val what = when (type) {
                "movie" -> "Movie"
                "series" -> "Series"
                "live" -> "Live TV"
                else -> "Categories"
            }
            tvTitle.text = "$what Rows · ${selectedIds.size} selected"
        }
        renderTitle()

        val adapter = CategoryAdapter(categories, selectedIds) { id, isChecked ->
            if (isChecked) selectedIds.add(id) else selectedIds.remove(id)
            // Write straight through — the dialog holds no unsaved state.
            writeSelection(selectedIds)
            changed = true
            renderTitle()
        }
        android.util.Log.d("CategoryDialog", "Showing dialog with ${categories.size} categories")
        if (categories.isEmpty()) {
            android.util.Log.e("CategoryDialog", "WARNING: Categories list is empty!")
        }
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvCategories.adapter = adapter

        return AlertDialog.Builder(requireContext())
            .setView(view)
            // BACK is the way out now that there is nothing to commit.
            .setCancelable(true)
            .create()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Tell Home once, on the way out, rather than after every tick.
        if (changed) onCategoriesSelected?.invoke()
    }

    private fun readSelection(): Set<String> = when (type) {
        "movie" -> homePreferences.getSelectedMovieCategories()
        "series" -> homePreferences.getSelectedSeriesCategories()
        "live" -> homePreferences.getSelectedLiveCategories()
        else -> emptySet()
    }

    private fun writeSelection(ids: Set<String>) {
        when (type) {
            "movie" -> homePreferences.setSelectedMovieCategories(ids)
            "series" -> homePreferences.setSelectedSeriesCategories(ids)
            "live" -> homePreferences.setSelectedLiveCategories(ids)
        }
    }

    private class CategoryAdapter(
        private val items: List<XtreamCategory>,
        private val selectedIds: MutableSet<String>,
        private val onToggle: (id: String, isChecked: Boolean) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: android.view.View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val checkBox: android.widget.CheckBox = view as android.widget.CheckBox
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val density = parent.resources.displayMetrics.density
            val checkBox = android.widget.CheckBox(parent.context).apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT
                )
                val padH = (16 * density).toInt()
                val padV = (12 * density).toInt()
                setPadding(padH, padV, padH, padV)
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF00F0FF.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                // Focus has to be obvious on a D-pad list this long.
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) {
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 8 * density
                            setColor(0x1A00F0FF)
                            setStroke((2 * density).toInt(), 0x5C00F0FF)
                        }
                    } else {
                        null
                    }
                }
            }
            return ViewHolder(checkBox)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            // Detach the listener before rebinding a recycled row, or setting isChecked below
            // fires a "toggle" for whichever category used to live in this view.
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.text = item.category_name ?: "Unknown"
            holder.checkBox.isChecked = selectedIds.contains(item.category_id)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val id = item.category_id ?: return@setOnCheckedChangeListener
                onToggle(id, isChecked)
            }
        }

        override fun getItemCount() = items.size
    }
}
