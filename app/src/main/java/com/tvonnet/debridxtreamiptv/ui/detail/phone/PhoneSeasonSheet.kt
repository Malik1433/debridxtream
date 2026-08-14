package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R

/**
 * The season picker.
 *
 * Twenty seasons will not fit as tabs, and a chip rail turns "Season 17" into horizontal hunting
 * with no sense of position. A sheet answers both questions people actually have — which season,
 * and where was I — in one list, because every row carries its own watched progress.
 *
 * It opens scrolled to the selected season, so a twenty-season show does not start at season one.
 */
object PhoneSeasonSheet {

    fun show(
        context: Context,
        inflater: LayoutInflater,
        model: PhoneDetailModel,
        onPicked: (Int) -> Unit,
    ) {
        val sheet = BottomSheetDialog(context)
        val content = inflater.inflate(R.layout.sheet_phone_seasons, null)
        sheet.setContentView(content)

        content.findViewById<TextView>(R.id.phone_seasons_total).text =
            context.getString(R.string.phone_season_total, model.seasons.size)
        content.findViewById<View>(R.id.phone_seasons_close).setOnClickListener { sheet.dismiss() }

        val list = content.findViewById<RecyclerView>(R.id.phone_seasons_list)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = Adapter(model.seasons, model.selectedSeason) { season ->
            onPicked(season)
            sheet.dismiss()
        }
        val selectedIndex = model.seasons.indexOfFirst { it.number == model.selectedSeason }
        if (selectedIndex > 0) list.scrollToPosition(selectedIndex)

        sheet.show()
    }

    private class Adapter(
        private val seasons: List<PhoneDetailModel.Season>,
        private val selected: Int?,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun getItemCount() = seasons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_phone_season, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(seasons[position])

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(season: PhoneDetailModel.Season) {
                val context = itemView.context
                val isSelected = season.number == selected
                itemView.findViewById<TextView>(R.id.phone_season_name).apply {
                    text = context.getString(R.string.phone_season_n, season.number)
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (isSelected) R.color.phone_cyan else R.color.phone_text_primary,
                        )
                    )
                }
                itemView.findViewById<TextView>(R.id.phone_season_count).text =
                    context.getString(R.string.phone_episode_count, season.episodeCount)

                val progress = itemView.findViewById<ProgressBar>(R.id.phone_season_progress)
                progress.progress = if (season.episodeCount <= 0) {
                    0
                } else {
                    (season.watchedCount * 100) / season.episodeCount
                }

                itemView.findViewById<TextView>(R.id.phone_season_state).text = when {
                    season.watchedCount <= 0 -> context.getString(R.string.phone_not_started)
                    else -> "${season.watchedCount}/${season.episodeCount}"
                }
                itemView.findViewById<ImageView>(R.id.phone_season_check).isVisible = isSelected
                itemView.setBackgroundColor(
                    if (isSelected) SELECTED_TINT else android.graphics.Color.TRANSPARENT
                )
                itemView.setOnClickListener { onPick(season.number) }
            }
        }

        private companion object {
            /** The same faint cyan wash the playing row uses in Live TV. */
            const val SELECTED_TINT = 0x1200F0FF
        }
    }
}
