package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi

/**
 * The two sheets Browse opens.
 *
 * Sheets rather than menus for the same reason the Live screen uses one: they are thumb-reachable,
 * one tap per option, and they have the width to carry a count beside every row — which is the
 * whole point at 200 categories.
 */
object PhoneBrowseSheets {

    /**
     * The category picker: searchable, because at 200 entries scrolling is not a retrieval
     * strategy — typing is. It opens on the current selection and one tap selects and dismisses.
     */
    fun categories(
        host: Fragment,
        categories: List<XtreamCategory>,
        counts: Map<String, Int>,
        selectedId: String?,
        onPick: (String) -> Unit,
    ) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_phone_browse_categories, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        val listBox = content.findViewById<LinearLayout>(R.id.phone_bcat_list)
        val total = content.findViewById<TextView>(R.id.phone_bcat_total)
        val matches = content.findViewById<TextView>(R.id.phone_bcat_matches)
        val empty = content.findViewById<TextView>(R.id.phone_bcat_empty)
        total.text = "${categories.size} ${context.getString(R.string.phone_total_suffix)}"

        fun render(query: String) {
            val needle = query.trim().lowercase()
            val shown = categories.filter {
                needle.isEmpty() || it.category_name.orEmpty().lowercase().contains(needle)
            }
            matches.text = "${shown.size} OF ${categories.size}"
            empty.isVisible = shown.isEmpty()
            empty.text = context.getString(R.string.phone_no_category_matches) + " \"" + query + "\""
            listBox.removeAllViews()
            shown.forEach { category ->
                val row = inflater.inflate(R.layout.item_phone_browse_category_row, listBox, false)
                val selected = category.category_id == selectedId
                row.findViewById<TextView>(R.id.phone_bcat_name).apply {
                    text = category.category_name
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            context,
                            if (selected) R.color.phone_cyan else R.color.phone_text_primary
                        )
                    )
                }
                row.findViewById<TextView>(R.id.phone_bcat_count).text =
                    counts[category.category_id]?.let { "%,d".format(it) }.orEmpty()
                row.findViewById<View>(R.id.phone_bcat_check).isVisible = selected
                row.setOnClickListener {
                    onPick(category.category_id.orEmpty())
                    dialog.dismiss()
                }
                listBox.addView(row)
            }
        }

        content.findViewById<EditText>(R.id.phone_bcat_search).addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            }
        )
        content.findViewById<View>(R.id.phone_bcat_close).setOnClickListener { dialog.dismiss() }
        render("")
        dialog.show()
    }

    /** Sort: one tap applies and dismisses. No Apply button — there is nothing to confirm. */
    fun sort(
        host: Fragment,
        isSeries: Boolean,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_phone_browse_sort, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        val listBox = content.findViewById<LinearLayout>(R.id.phone_bsort_list)
        options(isSeries).forEach { (key, labelRes, hintRes) ->
            val row = inflater.inflate(R.layout.item_phone_browse_sort_row, listBox, false)
            val selected = key == current
            row.findViewById<TextView>(R.id.phone_bsort_label).apply {
                setText(labelRes)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (selected) R.color.phone_cyan else R.color.phone_text_primary
                    )
                )
            }
            row.findViewById<TextView>(R.id.phone_bsort_hint).apply {
                isVisible = hintRes != 0
                if (hintRes != 0) setText(hintRes)
            }
            row.findViewById<View>(R.id.phone_bsort_check).isVisible = selected
            row.setOnClickListener {
                onPick(key)
                dialog.dismiss()
            }
            listBox.addView(row)
        }
        content.findViewById<View>(R.id.phone_bsort_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * What a card can do without opening it: the long-press menu.
     *
     * Details is the only navigation here — Play and Sources belong to the Detail page, one tap
     * away — so this stays a short list of things that are faster from the grid than from inside.
     */
    fun cardActions(
        host: Fragment,
        title: String,
        isFavourite: Boolean,
        onDetails: () -> Unit,
        onFavourite: () -> Unit,
    ) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_phone_browse_sort, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.phone_bsort_title).text = title
        val listBox = content.findViewById<LinearLayout>(R.id.phone_bsort_list)
        val rows = listOf<Pair<Int, () -> Unit>>(
            R.string.phone_details to onDetails,
            (if (isFavourite) R.string.phone_remove_favourite else R.string.phone_favourite)
                to onFavourite,
        )
        rows.forEach { (labelRes, action) ->
            val row = inflater.inflate(R.layout.item_phone_browse_sort_row, listBox, false)
            row.findViewById<TextView>(R.id.phone_bsort_label).setText(labelRes)
            row.findViewById<View>(R.id.phone_bsort_hint).isVisible = false
            row.findViewById<View>(R.id.phone_bsort_check).isVisible = false
            row.setOnClickListener {
                action()
                dialog.dismiss()
            }
            listBox.addView(row)
        }
        content.findViewById<View>(R.id.phone_bsort_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * The sort options each section actually HAS. The handoff lists five; this app's catalogue
     * queries provide four per section, and offering a fifth that quietly falls back to another
     * order would be worse than not offering it.
     */
    fun options(isSeries: Boolean): List<Triple<String, Int, Int>> = if (isSeries) {
        listOf(
            Triple("RECENTLY_ADDED", R.string.phone_sort_recent, R.string.phone_sort_default_hint),
            Triple("A_TO_Z", R.string.phone_sort_az, 0),
            Triple("TOP_RATED", R.string.phone_sort_rating, R.string.phone_sort_rating_hint),
            Triple("MOST_EPISODES", R.string.phone_sort_episodes, 0),
        )
    } else {
        listOf(
            Triple("RECENTLY_ADDED", R.string.phone_sort_recent, R.string.phone_sort_default_hint),
            Triple("A_TO_Z", R.string.phone_sort_az, 0),
            Triple("TOP_RATED", R.string.phone_sort_rating, R.string.phone_sort_rating_hint),
            Triple("NEWEST", R.string.phone_sort_year, R.string.phone_sort_year_hint),
        )
    }

    /** What the sort chip says: the current order, not the word "Sort" — so "why is this the
     *  order it is" never requires opening anything. */
    fun sortLabelRes(key: String): Int = when (key) {
        "A_TO_Z" -> R.string.phone_sort_az
        "TOP_RATED" -> R.string.phone_sort_rating
        "NEWEST" -> R.string.phone_sort_year
        "MOST_EPISODES" -> R.string.phone_sort_episodes
        else -> R.string.phone_sort_recent
    }
}
