package com.tvonnet.debridxtreamiptv.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi

/**
 * The settings pickers, as bottom sheets (G3).
 *
 * The phone rulebook has always asked for sheets rather than centre panels, and the eight settings
 * choosers were the last dialogs left on a handset. A sheet is thumb-reachable, its scrim is a
 * dismiss target, and it has room for a search field — which the ~90-entry language list needs and
 * the three-entry density list does not.
 *
 * The rows live in a **RecyclerView**. They were a plain column at first, which is fine for nine
 * languages and catastrophic for the Home row choosers: a provider's several hundred categories
 * were all measured on the main thread when the sheet opened, and on a slow device that reached an
 * ANR inside `TextView.onMeasure`. Recycling makes the cost the size of the screen, not the size of
 * the catalogue.
 *
 * **Television is not a caller.** Its dialogs are correct at three metres and stay; the routing
 * lives in [SettingsSelectorDialogs].
 */
object SettingsPickerSheet {

    /** One option in a multi-select sheet. The id and its label are one thing, so they travel as one. */
    data class Option(val id: String, val label: String)

    /** Above this many options a list stops being scannable and needs a search field. */
    private const val SEARCH_THRESHOLD = 12

    /** How tall the scrolling list may get before it scrolls instead of growing. */
    private const val LIST_CAP_DP = 420

    /** Roughly what fits in [LIST_CAP_DP] — below this a short list keeps its natural height. */
    private const val ROWS_BEFORE_CAP = 7

    /**
     * One choice, committed on the tap — no confirm button, because a sheet that needs two taps to
     * do one thing is a dialog with extra steps.
     */
    fun single(
        host: Fragment,
        title: String,
        labels: Array<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit,
    ) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_settings_picker, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.settings_pick_title).text = title

        // The index is carried as the id so a filtered list still reports the caller's position.
        val options = labels.mapIndexed { index, label -> Option(index.toString(), label) }
        val adapter = RowAdapter(
            ticked = { option -> option.id.toInt() == checkedIndex },
            onClick = { option, _ ->
                dialog.dismiss()
                onPick(option.id.toInt())
            },
        )
        bindList(content, adapter, options, labels.size)
        dialog.show()
    }

    /**
     * Several choices, committed by the button — the row choosers, where "which categories appear
     * on Home" is only meaningful once the whole set is decided.
     */
    fun multi(
        host: Fragment,
        title: String,
        options: List<Option>,
        selected: Set<String>,
        onDone: (Set<String>) -> Unit,
    ) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_settings_picker, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.settings_pick_title).text = title
        val done = content.findViewById<TextView>(R.id.settings_pick_done)
        done.isVisible = true

        val chosen = selected.toMutableSet()
        val adapter = RowAdapter(
            ticked = { option -> option.id in chosen },
            // Only the tapped row is rebound; `chosen` stays the single truth, so a search that
            // rebuilds the list still shows the right ticks.
            onClick = { option, position ->
                if (option.id in chosen) chosen.remove(option.id) else chosen.add(option.id)
                notifyRow(content, position)
            },
        )
        bindList(content, adapter, options, options.size)

        done.setOnClickListener {
            dialog.dismiss()
            onDone(chosen)
        }
        dialog.show()
    }

    /**
     * Wires the recycler, the search field and the "nothing matches" line — the same for both.
     *
     * Finds the search box and the empty line from [content] rather than taking them: the callers
     * already look them up, but passing them here made a seven-argument function out of one that
     * only ever needs the sheet and its options.
     */
    private fun bindList(
        content: View,
        adapter: RowAdapter,
        options: List<Option>,
        total: Int,
    ) {
        val list = content.findViewById<RecyclerView>(R.id.settings_pick_list)
        val empty = content.findViewById<TextView>(R.id.settings_pick_empty)
        val search = content.findViewById<EditText>(R.id.settings_pick_search)
        list.layoutManager = LinearLayoutManager(content.context)
        list.adapter = adapter
        search.isVisible = total > SEARCH_THRESHOLD
        capListHeight(content, total)

        fun apply(query: String) {
            val needle = query.trim().lowercase()
            val shown = if (needle.isEmpty()) {
                options
            } else {
                options.filter { it.label.lowercase().contains(needle) }
            }
            adapter.submit(shown)
            empty.isVisible = shown.isEmpty()
            empty.text = content.context.getString(R.string.phone_no_category_matches) +
                " \"" + query + "\""
        }

        apply("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = apply(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun notifyRow(content: View, position: Int) {
        val list = content.findViewById<RecyclerView>(R.id.settings_pick_list)
        list.adapter?.notifyItemChanged(position)
    }

    /**
     * Stops a long list from pushing the sheet past the screen.
     *
     * `android:maxHeight` looks like it should do this and does nothing: neither ScrollView nor
     * RecyclerView honours it. Without the cap the Done button — the only way to commit a
     * multi-select — ended up below the bottom edge of the screen.
     */
    private fun capListHeight(content: View, rows: Int) {
        if (rows <= ROWS_BEFORE_CAP) return
        val list = content.findViewById<View>(R.id.settings_pick_list)
        val cap = (LIST_CAP_DP * content.resources.displayMetrics.density).toInt()
        list.layoutParams = list.layoutParams.apply { height = cap }
    }

    /**
     * One option per row, 52dp tall so the whole row is the target rather than the tick beside it.
     *
     * [ticked] is asked at bind time rather than stored on the item: the multi-select's answer
     * changes while the sheet is open, and the set that knows it lives in the caller.
     */
    private class RowAdapter(
        private val ticked: (Option) -> Boolean,
        private val onClick: (Option, Int) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.Holder>() {

        private val items = mutableListOf<Option>()

        fun submit(next: List<Option>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_picker_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = items[position]
            holder.label.text = option.label
            holder.tick.isVisible = ticked(option)
            holder.itemView.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) onClick(items[at], at)
            }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.settings_pick_row_label)
            val tick: View = view.findViewById(R.id.settings_pick_row_tick)
        }
    }
}
