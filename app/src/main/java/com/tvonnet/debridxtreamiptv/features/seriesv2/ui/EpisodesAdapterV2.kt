package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeCinBinding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EpisodesAdapterEntryPoint {
    fun watchedStateRepository(): com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
}

/**
 * Horizontal episode strip for the IPTV Series Detail screen. Renders the cyan
 * "cinematic" episode card (item_episode_cin) with watched / resume / active states.
 */
class EpisodesAdapterV2(
    private val onEpisodeClick: (EpisodeEntityV2) -> Unit,
    private val onEpisodeFocused: (EpisodeEntityV2) -> Unit = {}
) : PagingDataAdapter<EpisodeEntityV2, EpisodesAdapterV2.EpisodeViewHolder>(EpisodeDiffCallback) {

    private companion object { const val TAG = "EpisodesAdapterV2" }

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    /**
     * The long-press "mark watched" write, owned (2026-09-05).
     *
     * It used to run on a fresh `CoroutineScope(Dispatchers.IO)` minted per click: nothing
     * cancelled it, and — worse — a scope built without a handler routes a failed Room write to
     * the thread's uncaught-exception handler, so a disk error on a long-press crashed the app.
     * This is the pattern the sibling adapters already use ([CinEpisodeAdapter],
     * [SeriesEpisodeAdapter]): one scope per adapter, cancelled with the adapter, the write
     * itself inside `runCatching` on IO.
     */
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeCinBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null) {
            holder.bind(item)
        }
    }

    var seriesCoverUrl: String? = null
    var seriesId: String? = null
    var seriesTitle: String? = null
    var seriesYear: String? = null
    var source: String? = null
    var isDebridSeries: Boolean = false
    var tmdbId: String? = null
    var imdbId: String? = null
    var watchedKeys: Set<String> = emptySet()

    /** identityKey -> saved progress ms, for in-progress resume bars (real V2 data). */
    var progressByKey: Map<String, Long> = emptyMap()

    private var selectedEpisodeId: String? = null

    fun setSelectedEpisode(episodeId: String?) {
        if (selectedEpisodeId == episodeId) return
        val prev = selectedEpisodeId
        selectedEpisodeId = episodeId
        val items = snapshot().items
        prev?.let { id -> items.indexOfFirst { it.episodeId == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it, Any()) } }
        episodeId?.let { id -> items.indexOfFirst { it.episodeId == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it, Any()) } }
    }

    inner class EpisodeViewHolder(private val binding: ItemEpisodeCinBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                if (item != null) onEpisodeClick(item)
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val item = getItem(bindingAdapterPosition)
                    if (item != null) onEpisodeFocused(item)
                }
            }

            // D-pad containment at the strip's right edge (owner bug 2026-08-31): at the
            // last laid-out card RIGHT finds no focusable neighbour — the next position's
            // view is not laid out yet, or the season truly ends here — and the framework's
            // focus search then escapes the strip up to the Play button. If a next item
            // exists, scroll it in and focus it ourselves; at the true end, stay put.
            // LEFT/UP/DOWN are untouched — leaving the strip vertically stays possible.
            binding.root.setOnKeyListener { v, keyCode, event ->
                if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.action != android.view.KeyEvent.ACTION_DOWN
                ) return@setOnKeyListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (pos + 1 >= itemCount) return@setOnKeyListener true // true end: focus stays
                val rv = binding.root.parent as? RecyclerView ?: return@setOnKeyListener false
                val next = rv.layoutManager?.findViewByPosition(pos + 1)
                if (next != null && next.isFocusable) return@setOnKeyListener false // normal move
                // The manual move below bypasses the framework's focus navigation, which is
                // what normally plays the remote's click tick — play it ourselves or the
                // strip goes silent at the loading edge (owner caught it, 2026-08-31).
                v.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_RIGHT)
                rv.scrollToPosition(pos + 1)
                rv.post { rv.findViewHolderForAdapterPosition(pos + 1)?.itemView?.requestFocus() }
                true
            }

            binding.root.setOnLongClickListener {
                val item = getItem(bindingAdapterPosition)
                if (item != null) {
                    val context = binding.root.context
                    val currentSeriesId = seriesId
                    val identityKey = watchedIdentityKey(item)
                    if (identityKey != null) {
                        val isWatched = watchedKeys.contains(identityKey)
                        val title = context.getString(
                            if (isWatched) R.string.c_mark_as_unwatched else R.string.c_mark_as_watched
                        )
                        androidx.appcompat.app.AlertDialog.Builder(context)
                            .setTitle(item.title)
                            .setItems(arrayOf(title)) { _, _ ->
                                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                                    context.applicationContext,
                                    EpisodesAdapterEntryPoint::class.java
                                )
                                val repo = entryPoint.watchedStateRepository()
                                val historyPrefs = com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences(context)
                                adapterScope.launch {
                                    val outcome = withContext(Dispatchers.IO) { runCatching {
                                    val currentState = repo.getState(identityKey) ?: com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity(
                                        identityKey = identityKey,
                                        contentType = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.CONTENT_TYPE_EPISODE,
                                        source = watchedSource(),
                                        episodeId = if (usesDebridIdentity()) null else item.episodeId,
                                        seriesId = if (usesDebridIdentity()) debridSeriesIdentity() ?: currentSeriesId else currentSeriesId,
                                        seasonNumber = item.seasonNumber,
                                        episodeNumber = item.episodeNumber,
                                        title = item.title,
                                        year = if (usesDebridIdentity()) seriesYear else null,
                                        tmdbId = if (usesDebridIdentity()) tmdbId else null,
                                        imdbId = if (usesDebridIdentity()) imdbId else null,
                                        durationMs = item.duration * 1000L,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    repo.setManualWatched(currentState, !isWatched, System.currentTimeMillis())
                                    historyPrefs.removeContinueWatchingItem(item.episodeId)
                                    } }
                                    outcome.onFailure { e ->
                                        android.util.Log.w(TAG, "mark-watched write failed", e)
                                    }
                                }
                            }
                            .show()
                    }
                    true
                } else false
            }
        }

        fun bind(episode: EpisodeEntityV2) {
            binding.tvEpisodeNumber.text = "E%02d".format(episode.episodeNumber)

            val rawTitle = episode.title
            val cleanTitle = if (rawTitle.isNullOrEmpty() || rawTitle.equals("null", ignoreCase = true)) {
                "Episode ${episode.episodeNumber}"
            } else rawTitle
            binding.tvTitle.text = cleanTitle

            val durationText = formatDuration(episode.duration)
            binding.tvDuration.text = durationText
            binding.tvDuration.visibility = if (durationText.isEmpty()) View.GONE else View.VISIBLE

            val identityKey = watchedIdentityKey(episode)
            val watched = identityKey != null && watchedKeys.contains(identityKey)
            val isActive = episode.episodeId == selectedEpisodeId

            // Resume progress: prefer real saved progress from the watched-state store
            // (episode.resumePosition is never populated in the V2 flow), keyed by identity.
            val durationMs = episode.duration * 1000L
            val resume = (identityKey?.let { progressByKey[it] } ?: 0L)
                .takeIf { it > 0L } ?: episode.resumePosition
            // When duration is unknown (0), still show a resume bar if we have a position.
            val hasProgress = resume > 0L &&
                (durationMs <= 0L || resume < durationMs) && !watched
            // Fraction only meaningful when duration is known; else show a token bar.
            val pctFraction = if (durationMs > 0L) {
                (resume.toFloat() / durationMs.toFloat()).coerceIn(0.02f, 1f)
            } else 0.35f
            applyResumeBar(hasProgress, pctFraction)
            bindSubtitle(episode, watched, hasProgress, durationMs, pctFraction)
            applyBadge(watched, isActive)
            loadThumbnail(episode)
        }

        private fun applyResumeBar(hasProgress: Boolean, pctFraction: Float) {
            if (!hasProgress) {
                binding.layoutProgress.visibility = View.GONE
                return
            }
            binding.layoutProgress.visibility = View.VISIBLE
            binding.vProgressFill.post {
                val parent = binding.layoutProgress.width
                val lp = binding.vProgressFill.layoutParams
                lp.width = (parent * pctFraction).toInt()
                binding.vProgressFill.layoutParams = lp
            }
        }

        // Subtitle line
        private fun bindSubtitle(
            episode: EpisodeEntityV2,
            watched: Boolean,
            hasProgress: Boolean,
            durationMs: Long,
            pctFraction: Float
        ) {
            val durationText = formatDuration(episode.duration)
            binding.tvSubtitle.text = when {
                watched -> "WATCHED" + (if (durationText.isNotEmpty()) " · $durationText" else "")
                hasProgress -> {
                    if (durationMs > 0L) "RESUME · ${(pctFraction * 100).toInt().coerceIn(1, 100)}%"
                    else "RESUME"
                }
                !episode.releaseDate.isNullOrBlank() -> episode.releaseDate
                else -> "EPISODE ${episode.episodeNumber}"
            }
        }

        // Badge: watched (green + check), active (cyan + play), or default (dark + play)
        private fun applyBadge(watched: Boolean, isActive: Boolean) {
            when {
                watched -> {
                    binding.vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_watched)
                    binding.ivWatchedIndicator.visibility = View.VISIBLE
                    binding.ivPlayIcon.visibility = View.GONE
                }
                isActive -> {
                    binding.vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_active)
                    binding.ivWatchedIndicator.visibility = View.GONE
                    binding.ivPlayIcon.visibility = View.VISIBLE
                    binding.ivPlayIcon.setColorFilter(0xFF041014.toInt())
                }
                else -> {
                    binding.vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_play)
                    binding.ivWatchedIndicator.visibility = View.GONE
                    binding.ivPlayIcon.visibility = View.VISIBLE
                    binding.ivPlayIcon.setColorFilter(0xFFF1F5F9.toInt())
                }
            }
        }

        private fun loadThumbnail(episode: EpisodeEntityV2) {
            val imageUrl = if (!episode.thumbnail.isNullOrEmpty()) episode.thumbnail else seriesCoverUrl
            Glide.with(binding.root.context)
                .load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.placeholder_poster)
                .error(R.drawable.placeholder_poster)
                .into(binding.ivEpisodeThumb)
        }

        private fun formatDuration(seconds: Long): String {
            if (seconds <= 0) return ""
            val m = seconds / 60
            return "${m}M"
        }

        private fun watchedIdentityKey(episode: EpisodeEntityV2): String? {
            return if (usesDebridIdentity()) {
                com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.debridEpisode(
                    seriesIdentity = debridSeriesIdentity(),
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    seriesTitle = seriesTitle ?: seriesId,
                    seriesYear = seriesYear,
                    fallbackDiscriminator = episode.episodeId ?: episode.title
                )
            } else {
                val currentSeriesId = seriesId ?: return null
                com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
                    episodeId = episode.episodeId,
                    seriesId = currentSeriesId,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber
                )
            }
        }

        private fun usesDebridIdentity(): Boolean {
            return isDebridSeries || source.equals("debrid", ignoreCase = true)
        }

        private fun watchedSource(): String {
            return if (usesDebridIdentity()) {
                com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_DEBRID
            } else {
                com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM
            }
        }

        private fun debridSeriesIdentity(): String? {
            return tmdbId?.trim()?.takeIf { it.isNotBlank() }
                ?: imdbId?.trim()?.takeIf { it.isNotBlank() }
                ?: seriesId?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    object EpisodeDiffCallback : DiffUtil.ItemCallback<EpisodeEntityV2>() {
        override fun areItemsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem.episodeId == newItem.episodeId
        }

        override fun areContentsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem == newItem
        }
    }
}
