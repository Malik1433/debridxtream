package com.tvonnet.debridxtreamiptv.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CategorySelectionDialog : DialogFragment() {

    @Inject
    lateinit var homePreferences: HomePreferences

    private var categories: List<XtreamCategory> = emptyList()
    private var type: String = "movie" // "movie", "series", "live"
    private var onCategoriesSelected: (() -> Unit)? = null

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
        val selectedIds = when (type) {
            "movie" -> homePreferences.getSelectedMovieCategories()
            "series" -> homePreferences.getSelectedSeriesCategories()
            "live" -> homePreferences.getSelectedLiveCategories()
            else -> emptySet()
        }.toMutableSet()

        val view = android.view.LayoutInflater.from(requireContext()).inflate(com.tvonnet.debridxtreamiptv.R.layout.dialog_category_selection, null)
        
        val rvCategories = view.findViewById<androidx.recyclerview.widget.RecyclerView>(com.tvonnet.debridxtreamiptv.R.id.rv_categories)
        val btnSave = view.findViewById<android.widget.Button>(com.tvonnet.debridxtreamiptv.R.id.btn_save)
        val btnCancel = view.findViewById<android.widget.Button>(com.tvonnet.debridxtreamiptv.R.id.btn_cancel)
        val tvTitle = view.findViewById<android.widget.TextView>(com.tvonnet.debridxtreamiptv.R.id.tv_dialog_title)

        tvTitle.text = when(type) {
            "movie" -> "Select Movie Categories"
            "series" -> "Select Series Categories"
            "live" -> "Select Live TV Categories"
            else -> "Select Categories"
        }

        val adapter = CategoryAdapter(categories, selectedIds)
        android.util.Log.d("CategoryDialog", "Showing dialog with ${categories.size} categories")
        if (categories.isEmpty()) {
             android.util.Log.e("CategoryDialog", "WARNING: Categories list is empty!")
        }
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvCategories.adapter = adapter
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        btnSave.setOnClickListener {
            // Save logic
            when (type) {
                "movie" -> homePreferences.setSelectedMovieCategories(selectedIds)
                "series" -> homePreferences.setSelectedSeriesCategories(selectedIds)
                "live" -> homePreferences.setSelectedLiveCategories(selectedIds)
            }
            onCategoriesSelected?.invoke()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        return dialog
    }

    private class CategoryAdapter(
        private val items: List<XtreamCategory>,
        private val selectedIds: MutableSet<String>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val checkBox: android.widget.CheckBox = view as android.widget.CheckBox
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // Using standard android layout for simple checkbox item to avoid new XML
            val checkBox = android.widget.CheckBox(parent.context).apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(32, 24, 32, 24)
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
            return ViewHolder(checkBox)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.checkBox.text = item.category_name ?: "Unknown"
            holder.checkBox.isChecked = selectedIds.contains(item.category_id)
            
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val id = item.category_id ?: return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedIds.add(id)
                } else {
                    selectedIds.remove(id)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
