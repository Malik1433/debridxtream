package com.tvonnet.debridxtreamiptv.ui.vod

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
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

    /** H8/H9: the priority pair that leads every chip strip (setting values or codes). */
    private var preferredLanguage: String? = null
    private var secondaryLanguage: String? = null

    init {
        setHasStableIds(true)
    }

    /** Call before submitList; a later change re-renders the visible strips. */
    fun setPreferredLanguages(primary: String?, secondary: String? = null) {
        if (preferredLanguage == primary && secondaryLanguage == secondary) return
        preferredLanguage = primary
        secondaryLanguage = secondary
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

        /**
         * Whether this link plays NOW.
         *
         * It used to read "DIRECT" or "TORRENT" — words about our plumbing, not about the
         * customer's next thirty seconds. The only question that matters is whether the provider
         * already holds the file, so the badge says so, and on the phone a green/amber dot on the
         * leading edge says the same thing before the eye even reaches the badge. IPTV rows are
         * neither: they are a stream, not a debrid link.
         *
         * The WORDS are qualified per device. The phone has room for INSTANT / FETCH FIRST; the
         * television's badge column is 48dp at 8sp and shipped reading "IN-" until
         * values-television/strings_sources.xml gave it CACHED / FETCH.
         */
        private fun bindTypeBadge(source: MovieSource) {
            val isIptv = source.sourceType == "IPTV"
            val instant = !isIptv && source.cacheStatus != DebridCacheStatus.NOT_CACHED
            binding.tvType.text = when {
                isIptv -> "IPTV"
                instant -> binding.root.context.getString(R.string.phone_source_instant)
                else -> binding.root.context.getString(R.string.phone_source_fetch_first)
            }
            binding.tvType.setBackgroundResource(
                if (instant) R.drawable.cin_pill_cached else R.drawable.cin_pill_uncached
            )
            binding.tvType.setTextColor(binding.root.context.getColor(R.color.white))
            // Phone only — the television row has no dot in its layout.
            //
            // INVISIBLE, not GONE, for an IPTV row: the whole line is anchored to this dot, so
            // collapsing it slid the badges and the title onto the BEST accent bar and the row
            // read "PTV" with its first letter under the stripe. A mark with nothing to say
            // should go quiet, not take the layout with it.
            binding.root.findViewById<View>(R.id.v_cache_dot)?.apply {
                visibility = if (isIptv) View.INVISIBLE else View.VISIBLE
                setBackgroundResource(
                    if (instant) R.drawable.bg_phone_dot_green else R.drawable.bg_phone_dot_amber
                )
            }
        }

        // Focus management
        private fun bindFocusHandling(source: MovieSource, isBestItem: Boolean) {
            // M5: on TV the play circle marks the FOCUSED row, so it rides focus. A touch
            // device never focuses a row, and the same rule would hide it forever — there
            // it is a standing tap affordance instead.
            val alwaysShown = binding.root.resources.getBoolean(R.bool.source_row_play_always_visible)

            FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                val stillSelected = source.stream.stream_id == selectedStreamId
                binding.root.isSelected = stillSelected

                // Best row keeps its badge instead of the play circle.
                showPlayAffordance(hasFocus || alwaysShown, isBestItem)

                if (hasFocus) {
                    onSourceFocused(source)
                }
            }

            FocusGlintHelper.forceReset(binding.root)

            if (binding.root.isFocused) {
                FocusGlintHelper.attach(binding.root)
                showPlayAffordance(shown = true, isBestItem = isBestItem)
            } else {
                showPlayAffordance(shown = alwaysShown, isBestItem = isBestItem)
            }
        }

        private fun showPlayAffordance(shown: Boolean, isBestItem: Boolean) {
            binding.ivPlay.isVisible = shown && !isBestItem
            binding.ivPlay.alpha = if (shown) 1.0f else 0.0f
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

        /**
         * H9: BOTH priority languages lead the strip — primary first, then secondary,
         * then the rest. With 2 visible slots this guarantees any priority language a
         * row carries is NAMED, never hidden inside "+N".
         */
        private fun orderPreferredFirst(codes: List<String>): List<String> {
            val pref = priorityEnum(preferredLanguage)
            val second = priorityEnum(secondaryLanguage)
            if (pref == null && second == null) return codes
            val (primary, notPrimary) = codes.partition { pref != null && StreamLanguage.parse(it) == pref }
            val (secondary, rest) = notPrimary.partition { second != null && StreamLanguage.parse(it) == second }
            return primary + secondary + rest
        }

        private fun priorityEnum(language: String?): StreamLanguage? =
            StreamLanguage.parse(language)
                .takeIf { it != StreamLanguage.ALL && it != StreamLanguage.UNKNOWN }

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
