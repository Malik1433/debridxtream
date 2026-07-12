package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.R

/**
 * Custom adapter for the cinematic track selection dialog.
 *
 * IMPORTANT: On Android TV, D-pad DPAD_CENTER fires ListView.onItemClick,
 * NOT View.OnClickListener. The click callback is wired via
 * DialogInterface.OnClickListener passed to AlertDialog.Builder.setAdapter(),
 * not through setOnClickListener on individual rows.
 *
 * @param items List of track label strings to display.
 * @param selectedIndex Index of the currently selected track (-1 for none).
 * @param onItemClicked Callback invoked with the selected position index.
 */
class TrackSelectionAdapter(
    private val items: List<String>,
    private val selectedIndex: Int,
    private val onItemClicked: (Int) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): String = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_track_selection_row, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tv_track_name)
        val ivCheck = view.findViewById<ImageView>(R.id.iv_track_check)

        tvName.text = getItem(position)
        val isSelected = position == selectedIndex
        ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        // VOD Player redesign: active row label is cyan, others are light.
        val ctx = view.context
        val labelColor = if (isSelected) R.color.neon_cyan else R.color.stremio_text_primary
        tvName.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, labelColor))

        return view
    }

    /**
     * Creates a DialogInterface.OnClickListener that routes clicks
     * from the AlertDialog's internal ListView to our callback.
     * Use this with AlertDialog.Builder.setAdapter(adapter, adapter.asDialogClickListener()).
     */
    fun asDialogClickListener(): DialogInterface.OnClickListener {
        return DialogInterface.OnClickListener { _, which ->
            onItemClicked(which)
        }
    }
}
