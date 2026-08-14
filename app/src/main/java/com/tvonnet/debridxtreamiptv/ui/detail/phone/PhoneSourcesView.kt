package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

/**
 * One playable source, flattened out of whatever the host's own source type is.
 *
 * The debrid stack has a rich source model; this screen needs six facts and a way to play it,
 * and taking only those keeps the screen from depending on the resolver's shape.
 */
data class PhoneSource(
    val id: String,
    val releaseName: String,
    val quality: String,
    val sizeLabel: String?,
    val languages: String?,
    val provider: String?,
    val cached: Boolean,
    val best: Boolean,
)

/**
 * The Sources screen: filter-first, because nobody reads sixty rows.
 *
 * People arrive with a sentence — "the 4K Hindi one, cached" — so the quality chips, the language
 * chip and the cached toggle all carry their COUNT, which makes a dead end visible before it is
 * tapped. Results group under quality headers. BEST is pinned above the filters and never
 * participates in them: filtering must not be able to hide the answer the app already computed.
 */
class PhoneSourcesView(
    private val root: View,
    private val inflater: LayoutInflater,
    private val title: String,
    private val onPlay: (PhoneSource) -> Unit,
    private val onBack: () -> Unit,
) {
    private var all: List<PhoneSource> = emptyList()
    private var quality: String? = null
    private var language: String? = null
    private var cachedOnly = false

    private val adapter = RowAdapter { source -> onPlay(source) }

    init {
        root.findViewById<View>(R.id.phone_src_back).setOnClickListener { onBack() }
        root.findViewById<RecyclerView>(R.id.phone_src_list).apply {
            layoutManager = LinearLayoutManager(root.context)
            adapter = this@PhoneSourcesView.adapter
        }
        // Sort exists on the app bar in the design; size-descending inside each quality group is
        // the default and the only order implemented so far, so the control stays hidden rather
        // than opening a menu with one entry.
        root.findViewById<View>(R.id.phone_src_sort).isVisible = false
    }

    fun submit(sources: List<PhoneSource>) {
        all = sources
        root.findViewById<TextView>(R.id.phone_src_subtitle).text = root.context.getString(
            R.string.phone_sources_found, title.uppercase(java.util.Locale.getDefault()), sources.size
        )
        bindBest(sources.firstOrNull { it.best } ?: sources.firstOrNull())
        bindFilters()
        bindList()
    }

    private fun bindBest(best: PhoneSource?) {
        val card = root.findViewById<View>(R.id.phone_src_best)
        card.isVisible = best != null
        if (best == null) return
        root.findViewById<TextView>(R.id.phone_src_best_meta).text = listOfNotNull(
            best.quality,
            best.sizeLabel,
            if (best.cached) root.context.getString(R.string.phone_cached) else null,
        ).joinToString(" · ")
        root.findViewById<TextView>(R.id.phone_src_best_name).text = best.releaseName
        root.findViewById<TextView>(R.id.phone_src_best_provider).text =
            listOfNotNull(best.provider, best.languages).joinToString(" · ")
        val play = { onPlay(best) }
        card.setOnClickListener { play() }
        root.findViewById<View>(R.id.phone_src_best_play).setOnClickListener { play() }
    }

    private fun bindFilters() {
        val bar = root.findViewById<LinearLayout>(R.id.phone_src_filters)
        bar.removeAllViews()

        addChip(bar, root.context.getString(R.string.phone_all), all.size, quality == null) {
            quality = null
            bindFilters(); bindList()
        }
        all.map { it.quality }.distinct().sortedByDescending { qualityRank(it) }.forEach { q ->
            val count = all.count { it.quality == q }
            addChip(bar, q, count, quality == q) {
                quality = if (quality == q) null else q
                bindFilters(); bindList()
            }
        }
        val languages = all.mapNotNull { it.languages }.flatMap { it.split(" · ") }
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (languages.isNotEmpty()) {
            val label = language ?: root.context.getString(R.string.phone_language)
            addChip(bar, label, languages.size, language != null) {
                showLanguageSheet(languages)
            }
        }
        val cachedCount = all.count { it.cached }
        if (cachedCount > 0) {
            addChip(bar, root.context.getString(R.string.phone_cached_only), cachedCount, cachedOnly) {
                cachedOnly = !cachedOnly
                bindFilters(); bindList()
            }
        }
    }

    private fun addChip(
        bar: LinearLayout,
        name: String,
        count: Int,
        active: Boolean,
        onClick: () -> Unit,
    ) {
        val chip = inflater.inflate(R.layout.item_phone_filter_chip, bar, false)
        chip.findViewById<TextView>(R.id.phone_fchip_name).apply {
            text = name
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.phone_cyan else R.color.phone_text_primary,
                )
            )
        }
        chip.findViewById<TextView>(R.id.phone_fchip_count).text = count.toString()
        chip.setBackgroundResource(
            if (active) R.drawable.bg_phone_chip_active else R.drawable.bg_phone_chip
        )
        // A zero-count chip is rendered disabled rather than hidden, so the shape of the result
        // set stays visible instead of silently changing between titles.
        if (count == 0) {
            chip.alpha = 0.4f
            chip.isEnabled = false
        } else {
            chip.setOnClickListener { onClick() }
        }
        bar.addView(chip)
    }

    private fun showLanguageSheet(languages: List<String>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(root.context)
        val content = LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_phone_sheet)
            val pad = com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.dp(context, 12)
            setPadding(0, pad, 0, pad)
        }
        // "Any language" first, so the filter is always reversible from inside the sheet.
        (listOf(root.context.getString(R.string.phone_all)) + languages).forEach { entry ->
            content.addView(
                TextView(root.context).apply {
                    text = entry
                    textSize = 13.5f
                    setTextColor(ContextCompat.getColor(context, R.color.phone_text_primary))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.dp(context, 20)
                    setPadding(pad, 0, pad, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.dp(context, 56),
                    )
                    setOnClickListener {
                        language = entry.takeIf { it != context.getString(R.string.phone_all) }
                        bindFilters(); bindList()
                        sheet.dismiss()
                    }
                }
            )
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun bindList() {
        val filtered = all.filter { source ->
            (quality == null || source.quality == quality) &&
                (!cachedOnly || source.cached) &&
                (language == null || source.languages?.contains(language!!) == true)
        }
        // Grouped by quality, size descending inside each group — the two things people sort by.
        val rows = mutableListOf<Row>()
        filtered.groupBy { it.quality }
            .toList()
            .sortedByDescending { (q, _) -> qualityRank(q) }
            .forEach { (q, group) ->
                rows += Row.Header(q, group.size)
                rows += group.sortedByDescending { sizeOf(it) }.map { Row.Item(it) }
            }
        adapter.submit(rows)
        root.findViewById<View>(R.id.phone_src_empty).isVisible = filtered.isEmpty()
    }

    private fun qualityRank(quality: String): Int = when {
        quality.contains("4K", true) || quality.contains("2160") -> 4
        quality.contains("1080") -> 3
        quality.contains("720") -> 2
        else -> 1
    }

    private fun sizeOf(source: PhoneSource): Double {
        val label = source.sizeLabel ?: return 0.0
        val number = label.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
        return if (label.contains("GB", true)) number * 1024 else number
    }

    private sealed interface Row {
        data class Header(val quality: String, val count: Int) : Row
        data class Item(val source: PhoneSource) : Row
    }

    private class RowAdapter(
        private val onClick: (PhoneSource) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_phone_source_header, parent, false))
            } else {
                ItemVH(inflater.inflate(R.layout.item_phone_source, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row)
                is Row.Item -> (holder as ItemVH).bind(row.source, onClick)
            }
        }

        private class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(header: Row.Header) {
                itemView.findViewById<TextView>(R.id.phone_sh_title).text = header.quality
                itemView.findViewById<TextView>(R.id.phone_sh_count).text = header.count.toString()
                itemView.findViewById<View>(R.id.phone_sh_accent).setBackgroundColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (header.quality.contains("4K", true)) {
                            R.color.phone_cyan
                        } else {
                            R.color.phone_text_secondary
                        },
                    )
                )
            }
        }

        private class ItemVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(source: PhoneSource, onClick: (PhoneSource) -> Unit) {
                val context = itemView.context
                itemView.findViewById<TextView>(R.id.phone_srow_quality).apply {
                    text = source.quality
                    setBackgroundResource(
                        if (source.quality.contains("4K", true)) {
                            R.drawable.bg_phone_badge_cyan
                        } else {
                            R.drawable.bg_phone_badge_neutral
                        }
                    )
                }
                itemView.findViewById<TextView>(R.id.phone_srow_size).text = source.sizeLabel.orEmpty()
                itemView.findViewById<TextView>(R.id.phone_srow_name).text = source.releaseName
                itemView.findViewById<TextView>(R.id.phone_srow_provider).text =
                    listOfNotNull(source.provider, source.languages).joinToString(" · ")
                itemView.findViewById<TextView>(R.id.phone_srow_state).apply {
                    setText(if (source.cached) R.string.phone_cached else R.string.phone_direct)
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (source.cached) R.color.phone_green else R.color.phone_amber,
                        )
                    )
                }
                // One tap plays. There is no confirm step anywhere on this screen.
                itemView.setOnClickListener { onClick(source) }
            }
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_ITEM = 1
        }
    }
}
