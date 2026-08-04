package com.tvonnet.debridxtreamiptv.ui.vod

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.databinding.ItemMovieSourceBinding
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.ui.sources.StreamLanguage
import com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper
import java.util.Locale

class MovieSourceAdapter(
    private val onSourceFocused: (MovieSource) -> Unit,
    private val onSourceClicked: (MovieSource) -> Unit,
    private val onNavigateUpFromFirstRow: () -> Boolean = { false }
) : ListAdapter<MovieSource, MovieSourceAdapter.SourceViewHolder>(DiffCallback) {

    private var selectedStreamId: String? = null
    private var bestStreamId: String? = null

    /** H8: the language that leads every chip strip (setting value or 2-letter code). */
    private var preferredLanguage: String? = null

    init {
        setHasStableIds(true)
    }

    /** Call before submitList; a later change re-renders the visible strips. */
    fun setPreferredLanguage(language: String?) {
        if (preferredLanguage == language) return
        preferredLanguage = language
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).stream.stream_id.hashCode().toLong()
    }

    /** Marks the given source row as the BEST PICK (accent bar + badge). */
    fun updateBestPick(streamId: String?) {
        val oldId = bestStreamId
        if (oldId == streamId) return
        bestStreamId = streamId
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (item.stream.stream_id == oldId || item.stream.stream_id == streamId) {
                notifyItemChanged(i)
            }
        }
    }

    fun updateSelection(streamId: String?, notify: Boolean = true) {
        val oldId = selectedStreamId
        if (oldId == streamId) return
        
        selectedStreamId = streamId
        if (notify) {
            for (i in 0 until itemCount) {
                val item = getItem(i)
                if (item.stream.stream_id == oldId || item.stream.stream_id == streamId) {
                    notifyItemChanged(i)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemMovieSourceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SourceViewHolder(
        private val binding: ItemMovieSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: MovieSource) {
            // Col 1: Provider Badge
            bindProviderBadge(source)

            // Col 2: Language Flags
            bindLanguages(source)

            // Col 3: Source Title / Release Name
            binding.tvReleaseName.text = source.label

            // Col 4: Quality Badge
            bindQualityBadge(source)

            // Col 5: Type Badge
            bindTypeBadge(source)

            // Col 6: Size text + proportional visual bar
            bindSizeColumn(source)

            // BEST PICK treatment (first cached source after sorting)
            val isBestItem = bestStreamId != null && source.stream.stream_id == bestStreamId
            binding.vBestAccent.isVisible = isBestItem
            binding.tvBestBadge.isVisible = isBestItem

            binding.root.isSelected = source.stream.stream_id == selectedStreamId

            bindFocusHandling(source, isBestItem)

            binding.root.setOnClickListener {
                onSourceClicked(source)
            }

            binding.root.setOnKeyListener { v, keyCode, event -> handleSourceKey(v, keyCode, event, source) }
        }

        private fun bindQualityBadge(source: MovieSource) {
            val qualityLabel = source.quality
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
                ?.uppercase(Locale.US)
            binding.tvQuality.isVisible = !qualityLabel.isNullOrBlank()
            if (qualityLabel.isNullOrBlank()) return
            binding.tvQuality.text = qualityLabel
            when {
                qualityLabel.contains("4K") || qualityLabel.contains("2160") -> {
                    binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_4k_gold)
                    binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.black))
                }
                qualityLabel.contains("1080") -> {
                    binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_1080p_blue)
                    binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.white))
                }
                else -> {
                    binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_generic)
                    binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.white))
                }
            }
        }

        private fun bindTypeBadge(source: MovieSource) {
            val typeLabel = when {
                source.sourceType == "IPTV" -> "IPTV"
                source.cacheStatus == DebridCacheStatus.NOT_CACHED -> "TORRENT"
                else -> "DIRECT"
            }
            binding.tvType.text = typeLabel
            if (typeLabel == "DIRECT") {
                binding.tvType.setBackgroundResource(R.drawable.cin_pill_cached)
                binding.tvType.setTextColor(binding.root.context.getColor(R.color.white))
            } else {
                binding.tvType.setBackgroundResource(R.drawable.cin_pill_uncached)
                binding.tvType.setTextColor(binding.root.context.getColor(R.color.white))
            }
        }

        // Focus management
        private fun bindFocusHandling(source: MovieSource, isBestItem: Boolean) {
            FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                val stillSelected = source.stream.stream_id == selectedStreamId
                binding.root.isSelected = stillSelected

                // Best row keeps its badge instead of the play circle.
                binding.ivPlay.isVisible = hasFocus && !isBestItem
                binding.ivPlay.alpha = if (hasFocus) 1.0f else 0.0f

                if (hasFocus) {
                    onSourceFocused(source)
                }
            }

            FocusGlintHelper.forceReset(binding.root)

            if (binding.root.isFocused) {
                FocusGlintHelper.attach(binding.root)
                binding.ivPlay.isVisible = !isBestItem
                binding.ivPlay.alpha = 1.0f
            } else {
                binding.ivPlay.isVisible = false
            }
        }

        // CENTER plays; UP/DOWN move row-by-row with an explicit scroll+focus (a plain focus
        // search can skip rows mid-scroll); UP from the first row escapes to the caller.
        private fun handleSourceKey(
            v: android.view.View,
            keyCode: Int,
            event: android.view.KeyEvent,
            source: MovieSource
        ): Boolean {
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return false
            val rv = v.parent as? RecyclerView ?: return false
            val adapter = rv.adapter ?: return false

            return when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    onSourceClicked(source)
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position < adapter.itemCount - 1) focusRowAt(rv, position + 1)
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position > 0) focusRowAt(rv, position - 1) else onNavigateUpFromFirstRow()
                    true
                }
                else -> false
            }
        }

        private fun focusRowAt(rv: RecyclerView, target: Int) {
            rv.scrollToPosition(target)
            rv.post {
                rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }

        private fun bindProviderBadge(source: MovieSource) {
            val label = source.label.uppercase(Locale.US)
            val provider = source.provider?.uppercase(Locale.US) ?: ""
            val sourceName = source.sourceName?.uppercase(Locale.US) ?: ""

            val badge = PROVIDER_BADGE_RULES.firstOrNull { it.matches(provider, sourceName, label) }
                ?: GENERIC_PROVIDER_BADGE

            // H1: collapsed-copies count — "×4" means four addons offer this same file
            // (a reliability signal, and the silent-failover pool).
            binding.tvProvider.text = if (source.addonCount > 1) {
                "${badge.text} ×${source.addonCount}"
            } else {
                badge.text
            }
            binding.tvProvider.setBackgroundResource(badge.bg)
            binding.tvProvider.setTextColor(badge.textColor)
        }

        private fun bindLanguages(source: MovieSource) {
            binding.llLanguages.removeAllViews()
            val languages = source.languages.orEmpty()

            // H3: honestly unknown — one neutral chip, not "UNK" (reads as a language)
            // and not "MULTI" (a lie the parser used to invent).
            if (languages.isEmpty()) {
                val chip = LayoutInflater.from(binding.root.context).inflate(
                    R.layout.item_lang_chip, binding.llLanguages, false
                )
                chip.findViewById<TextView>(R.id.tv_flag).text = "🌐"
                chip.findViewById<TextView>(R.id.tv_code).text = "?"
                chip.contentDescription = "Language unknown"
                binding.llLanguages.addView(chip)
                return
            }

            // H8: many-language rows get a deterministic pattern instead of clipping —
            // the preferred language leads, at most MAX_LANG_CHIPS codes render, and the
            // rest collapse into a "+N" counter. On overflow rows only the FIRST chip
            // keeps its flag emoji so the strip stays within its 52dp column.
            val ordered = orderPreferredFirst(languages.distinct())
            val overflow = (ordered.size - MAX_LANG_CHIPS).coerceAtLeast(0)
            ordered.take(MAX_LANG_CHIPS).forEachIndexed { index, langCode ->
                val chip = LayoutInflater.from(binding.root.context).inflate(
                    R.layout.item_lang_chip, binding.llLanguages, false
                )
                val tvFlag = chip.findViewById<TextView>(R.id.tv_flag)
                val tvCode = chip.findViewById<TextView>(R.id.tv_code)

                val flagParts = getFlagEmoji(langCode).split(" ")
                val emoji = flagParts.firstOrNull() ?: "🌐"
                val code = if (flagParts.size > 1) flagParts[1] else "UNK"

                tvFlag.text = emoji
                if (overflow > 0 && index > 0) tvFlag.visibility = android.view.View.GONE
                // H4: check-mark on the FIRST chip when the player itself verified these
                // languages during playback (vs parsed claims from the release title) —
                // first, because that chip is the one guaranteed visible.
                tvCode.text = if (source.languagesVerified && index == 0) {
                    "$code ✓"
                } else {
                    code
                }
                binding.llLanguages.addView(chip)
            }
            if (overflow > 0) {
                val chip = LayoutInflater.from(binding.root.context).inflate(
                    R.layout.item_lang_chip, binding.llLanguages, false
                )
                chip.findViewById<TextView>(R.id.tv_flag).visibility = android.view.View.GONE
                chip.findViewById<TextView>(R.id.tv_code).text = "+$overflow"
                binding.llLanguages.addView(chip)
            }
            binding.llLanguages.contentDescription = buildString {
                append(if (source.languagesVerified) "Verified languages: " else "Languages: ")
                append(ordered.joinToString(", ") { SourceFilterUtils.mapLanguageCodeToName(it) })
            }
        }

        /** The user's preferred audio language leads the strip when the row carries it. */
        private fun orderPreferredFirst(codes: List<String>): List<String> {
            val pref = StreamLanguage.parse(preferredLanguage)
            if (pref == StreamLanguage.ALL || pref == StreamLanguage.UNKNOWN) return codes
            val (match, rest) = codes.partition { StreamLanguage.parse(it) == pref }
            return match + rest
        }

        private fun getFlagEmoji(languageCode: String): String =
            FLAG_EMOJI_BY_CODE[languageCode.lowercase()] ?: "🌐 UNK"

        private fun formatSizeLabel(sizeBytes: Long): String {
            val gb = 1024.0 * 1024.0 * 1024.0
            val mb = 1024.0 * 1024.0
            return if (sizeBytes >= gb) {
                String.format(Locale.US, "%.1f GB", sizeBytes / gb)
            } else {
                String.format(Locale.US, "%.0f MB", sizeBytes / mb)
            }
        }

        /**
         * Renders the file size text plus a mini bar whose fill length is
         * proportional to the largest source in the current list and whose
         * colour encodes the size tier (cyan ≤6GB, amber 6-15GB, red >15GB).
         */
        private fun bindSizeColumn(source: MovieSource) {
            val sizeBytes = source.sizeBytes?.takeIf { it > 0 }
            if (sizeBytes == null) {
                binding.tvFilesize.isVisible = false
                binding.layoutSizeBar.isVisible = false
                return
            }

            binding.tvFilesize.isVisible = true
            binding.tvFilesize.text = formatSizeLabel(sizeBytes)
            binding.layoutSizeBar.isVisible = true

            val maxBytes = currentList.maxOfOrNull { it.sizeBytes ?: 0L }
                ?.takeIf { it > 0 } ?: sizeBytes
            val fraction = (sizeBytes.toDouble() / maxBytes.toDouble()).coerceIn(0.05, 1.0)

            val density = binding.root.resources.displayMetrics.density
            val trackPx = (SIZE_BAR_WIDTH_DP * density).toInt()
            val fillView = binding.vSizeBarFill
            fillView.layoutParams = fillView.layoutParams.apply {
                width = (trackPx * fraction).toInt().coerceAtLeast((2 * density).toInt())
            }
            fillView.requestLayout()

            val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
            val fillColor = when {
                gb <= 6.0 -> SIZE_COLOR_CYAN
                gb <= 15.0 -> SIZE_COLOR_AMBER
                else -> SIZE_COLOR_RED
            }
            fillView.backgroundTintList = ColorStateList.valueOf(fillColor)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        FocusGlintHelper.attachScrollReset(recyclerView)
    }

    override fun onViewRecycled(holder: SourceViewHolder) {
        super.onViewRecycled(holder)
        FocusGlintHelper.forceReset(holder.itemView)
    }

    /** A provider badge (text, pill drawable, text colour) plus the markers that select it. */
    private class ProviderBadge(
        val text: String,
        val bg: Int,
        val textColor: Int,
        val providerMarkers: List<String> = emptyList(),
        val sourceNameMarkers: List<String> = emptyList(),
        val labelMarkers: List<String> = emptyList(),
        val labelExact: String? = null,
    ) {
        fun matches(provider: String, sourceName: String, label: String): Boolean {
            if (providerMarkers.any { provider.contains(it) }) return true
            if (sourceNameMarkers.any { sourceName.contains(it) }) return true
            if (labelMarkers.any { label.contains(it) }) return true
            return labelExact != null && label == labelExact
        }
    }

    companion object {
        /** H8: codes rendered before the "+N" overflow chip (the strip is 52dp wide). */
        private const val MAX_LANG_CHIPS = 2
        private const val SIZE_BAR_WIDTH_DP = 40f
        private const val SIZE_COLOR_CYAN = 0xFF00F0FF.toInt()
        private const val SIZE_COLOR_AMBER = 0x99FFAA00.toInt()
        private const val SIZE_COLOR_RED = 0x99FF3355.toInt()
        private const val BADGE_DARK_TEXT = 0xFF0E0D15.toInt()

        // Design palette: RD cyan, AIO amber, STRM green, generic glass — the old
        // when-chain as data, ordered, first match wins.
        private val PROVIDER_BADGE_RULES = listOf(
            ProviderBadge(
                "IPTV", R.drawable.cin_src_badge_xtream, 0xFFE8B94A.toInt(),
                providerMarkers = listOf("IPTV"), labelExact = "IPTV",
            ),
            ProviderBadge(
                "AIO", R.drawable.cin_src_badge_aio_amber, BADGE_DARK_TEXT,
                sourceNameMarkers = listOf("AIO"), labelMarkers = listOf("AIOSTREAMS"),
            ),
            ProviderBadge(
                "STRM", R.drawable.cin_src_badge_strm_green, BADGE_DARK_TEXT,
                sourceNameMarkers = listOf("STREMTHRU", "STRM"), labelMarkers = listOf("STREMTHRU"),
            ),
            ProviderBadge(
                "RD", R.drawable.cin_src_badge_rd_cyan, BADGE_DARK_TEXT,
                labelMarkers = listOf("RD", "REAL-DEBRID"), providerMarkers = listOf("REALDEBRID"),
            ),
            ProviderBadge(
                "AD", R.drawable.cin_src_badge_aio_amber, BADGE_DARK_TEXT,
                labelMarkers = listOf("AD", "ALLDEBRID"), providerMarkers = listOf("ALLDEBRID"),
            ),
            ProviderBadge(
                "PM", R.drawable.cin_src_badge_prem, 0xFFF1F5F9.toInt(),
                labelMarkers = listOf("PM", "PREMIUMIZE"), providerMarkers = listOf("PREMIUMIZE"),
            ),
        )
        private val GENERIC_PROVIDER_BADGE =
            ProviderBadge("SRC", R.drawable.cin_src_badge_generic, 0xFFF1F5F9.toInt())

        private val FLAG_EMOJI_BY_CODE = mapOf(
            "en" to "🇺🇸 EN", "eng" to "🇺🇸 EN", "english" to "🇺🇸 EN",
            "fr" to "🇫🇷 FR", "fre" to "🇫🇷 FR", "french" to "🇫🇷 FR",
            "de" to "🇩🇪 DE", "ger" to "🇩🇪 DE", "german" to "🇩🇪 DE",
            "es" to "🇪🇸 ES", "spa" to "🇪🇸 ES", "spanish" to "🇪🇸 ES",
            "it" to "🇮🇹 IT", "ita" to "🇮🇹 IT", "italian" to "🇮🇹 IT",
            "pt" to "🇵🇹 PT", "por" to "🇵🇹 PT", "portuguese" to "🇵🇹 PT",
            "ru" to "🇷🇺 RU", "rus" to "🇷🇺 RU", "russian" to "🇷🇺 RU",
            "hi" to "🇮🇳 HI", "hin" to "🇮🇳 HI", "hindi" to "🇮🇳 HI",
            "ja" to "🇯🇵 JA", "jpn" to "🇯🇵 JA", "japanese" to "🇯🇵 JA",
            "ko" to "🇰🇷 KO", "kor" to "🇰🇷 KO", "korean" to "🇰🇷 KO",
            "zh" to "🇨🇳 ZH", "chi" to "🇨🇳 ZH", "chinese" to "🇨🇳 ZH",
            "ar" to "🇸🇦 AR", "ara" to "🇸🇦 AR", "arabic" to "🇸🇦 AR",
            "tr" to "🇹🇷 TR", "tur" to "🇹🇷 TR", "turkish" to "🇹🇷 TR",
            "pl" to "🇵🇱 PL", "pol" to "🇵🇱 PL", "polish" to "🇵🇱 PL",
            "nl" to "🇳🇱 NL", "dut" to "🇳🇱 NL", "dutch" to "🇳🇱 NL",
            // H8: the Indian-language codes the parser and H4 learning actually emit —
            // they rendered as "UNK" before this row existed.
            "ta" to "🇮🇳 TA", "tam" to "🇮🇳 TA", "tamil" to "🇮🇳 TA",
            "te" to "🇮🇳 TE", "tel" to "🇮🇳 TE", "telugu" to "🇮🇳 TE",
            "ml" to "🇮🇳 ML", "mal" to "🇮🇳 ML", "malayalam" to "🇮🇳 ML",
            "kn" to "🇮🇳 KN", "kan" to "🇮🇳 KN", "kannada" to "🇮🇳 KN",
            "pa" to "🇮🇳 PA", "pun" to "🇮🇳 PA", "punjabi" to "🇮🇳 PA",
            "bn" to "🇮🇳 BN", "ben" to "🇮🇳 BN", "bengali" to "🇮🇳 BN",
            "mr" to "🇮🇳 MR", "mar" to "🇮🇳 MR", "marathi" to "🇮🇳 MR",
            "gu" to "🇮🇳 GU", "guj" to "🇮🇳 GU", "gujarati" to "🇮🇳 GU",
            "ur" to "🇵🇰 UR", "urd" to "🇵🇰 UR", "urdu" to "🇵🇰 UR",
            "multi" to "🌎 MULTI",
        )

        private val DiffCallback = object : DiffUtil.ItemCallback<MovieSource>() {
            override fun areItemsTheSame(oldItem: MovieSource, newItem: MovieSource): Boolean {
                return oldItem.stream.stream_id == newItem.stream.stream_id
            }

            override fun areContentsTheSame(oldItem: MovieSource, newItem: MovieSource): Boolean {
                return oldItem == newItem
            }
        }
    }
}
