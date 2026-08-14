package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory

/** The categories inside the "All N" sheet — 56dp rows, filtered by the sheet's own search. */
class PhoneCategoryAdapter(
    private val counts: Map<String, Int>,
    private val onPick: (XtreamCategory) -> Unit,
) : RecyclerView.Adapter<PhoneCategoryAdapter.VH>() {

    private var items: List<XtreamCategory> = emptyList()

    fun submit(categories: List<XtreamCategory>) {
        items = categories
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_phone_category_row, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.phone_cat_name)
        private val count: TextView = itemView.findViewById(R.id.phone_cat_count)

        fun bind(category: XtreamCategory) {
            name.text = category.category_name
            count.text = category.category_id?.let { counts[it] }?.toString().orEmpty()
            itemView.setOnClickListener { onPick(category) }
        }
    }
}
