package com.tvonnet.debridxtreamiptv.ui.series

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CinEpisodeAdapter(
    private val onEpisodeClick: (EpisodeUiModel) -> Unit,
    private val onEpisodeFocused: (EpisodeUiModel) -> Unit
) : ListAdapter<EpisodeUiModel, CinEpisodeAdapter.EpisodeViewHolder>(DIFF_CALLBACK) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    private var selectedEpisodeId: String? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchedKeys = mutableSetOf<String>()
    // identityKey -> progress percent (1..99) for in-progress episodes
    private val progressByKey = mutableMapOf<String, Int>()
    private val loadedKeys = mutableSetOf<String>()

    // Activity-pushed overrides (authoritative, refreshed on resume).
    // Keyed by episode id.
    private var progressOverride: Map<String, Int> = emptyMap()
    private var watchedOverride: Set<String> = emptySet()

    /** Called by the Activity after (re)loading watched-state so progress/resume reflect real data. */
    fun applyOverrides(progress: Map<String, Int>, watched: Set<String>) {
        progressOverride = progress
        watchedOverride = watched
        // Force the lazy per-row loader to re-query too.
        loadedKeys.clear()
        notifyDataSetChanged()
    }

    fun submitEpisodes(newItems: List<EpisodeUiModel>, selectedId: String?) {
        selectedEpisodeId = selectedId
        submitList(newItems)
    }

    fun setSelectedEpisode(episodeId: String?) {
        if (selectedEpisodeId == episodeId) return
        val prev = selectedEpisodeId
        selectedEpisodeId = episodeId
        prev?.let { notifyItemChangedById(it) }
        episodeId?.let { notifyItemChangedById(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_cin, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedEpisodeId)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.iv_episode_thumb)
        private val tvEpNum: TextView = itemView.findViewById(R.id.tv_episode_number)
        private val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_subtitle)
        private val layoutProgress: FrameLayout = itemView.findViewById(R.id.layout_progress)
        private val vProgressFill: View = itemView.findViewById(R.id.v_progress_fill)
        private val vBadgeBg: View = itemView.findViewById(R.id.v_badge_bg)
        private val ivWatched: ImageView = itemView.findViewById(R.id.iv_watched_indicator)
        private val ivPlayIcon: ImageView = itemView.findViewById(R.id.iv_play_icon)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEpisodeClick(getItem(pos))
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onEpisodeFocused(getItem(pos))
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: return@setOnLongClickListener false
                val item = getItem(pos)
                val context = itemView.context
                val identityKey = watchedIdentityKey(context, item) ?: return@setOnLongClickListener false
                val isWatched = watchedKeys.contains(identityKey)
                val actionTitle = if (isWatched) "Mark as Unwatched" else "Mark as Watched"
                AlertDialog.Builder(context)
                    .setTitle(item.title)
                    .setItems(arrayOf(actionTitle)) { _, _ ->
                        toggleWatched(context, item, identityKey, !isWatched)
                    }
                    .show()
                true
            }
        }

        fun bind(item: EpisodeUiModel, isSelected: Boolean) {
            tvEpNum.text = "E${String.format(java.util.Locale.US, "%02d", item.episodeNumber ?: 0)}"
            tvTitle.text = item.title

            val durationMin = item.durationMinutes
            tvDuration.text = if (durationMin != null) "${durationMin}M" else ""

            loadThumbnail(item)

            val identityKey = watchedIdentityKey(itemView.context, item)
            val actuallyWatched = resolveWatchedState(item, identityKey)
            val pct = resolveProgressPercent(item, identityKey, durationMin)
            val hasProgress = !actuallyWatched && pct != null

            applyProgressBar(hasProgress, pct)
            applyBadge(actuallyWatched, isSelected)

            // Subtitle
            tvSubtitle.text = when {
                actuallyWatched -> "WATCHED · ${tvDuration.text}"
                hasProgress -> "RESUME · ${pct}%"
                else -> item.description?.take(20) ?: ""
            }

            itemView.isSelected = isSelected
        }

        private fun loadThumbnail(item: EpisodeUiModel) {
            val thumbnail = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            Glide.with(itemView)
                .load(thumbnail)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(ivThumb)
        }

        // Lazy-loads the stored state once per identity, then merges the three watched signals.
        private fun resolveWatchedState(item: EpisodeUiModel, identityKey: String?): Boolean {
            if (identityKey != null && !loadedKeys.contains(identityKey)) {
                loadWatchedState(identityKey)
            }
            val isWatched = identityKey != null && watchedKeys.contains(identityKey)
            return item.isWatched || isWatched || watchedOverride.contains(item.id)
        }

        // Progress percent: prefer Activity override (authoritative), then stored watched-state, then item.resumePosition
        private fun resolveProgressPercent(item: EpisodeUiModel, identityKey: String?, durationMin: Int?): Int? {
            val overridePct = progressOverride[item.id]
            val storedPct = identityKey?.let { progressByKey[it] }
            val fallbackPct = if (item.resumePosition > 0 && durationMin != null && durationMin > 0) {
                ((item.resumePosition.toFloat() / (durationMin * 60_000L)) * 100).toInt().coerceIn(0, 100)
            } else null
            return (overridePct ?: storedPct ?: fallbackPct)?.takeIf { it in 1..99 }
        }

        // Progress bar
        private fun applyProgressBar(hasProgress: Boolean, pct: Int?) {
            if (!hasProgress || pct == null) {
                layoutProgress.visibility = View.GONE
                return
            }
            layoutProgress.visibility = View.VISIBLE
            vProgressFill.post {
                val parentWidth = (vProgressFill.parent as? View)?.width ?: 0
                val lp = vProgressFill.layoutParams
                lp.width = (parentWidth * pct / 100f).toInt()
                vProgressFill.layoutParams = lp
            }
        }

        // Badge
        private fun applyBadge(actuallyWatched: Boolean, isSelected: Boolean) {
            when {
                actuallyWatched -> {
                    vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_watched)
                    ivWatched.visibility = View.VISIBLE
                    ivPlayIcon.visibility = View.GONE
                }
                isSelected -> {
                    vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_active)
                    ivWatched.visibility = View.GONE
                    ivPlayIcon.visibility = View.VISIBLE
                    ivPlayIcon.setColorFilter(0xFF041014.toInt())
                }
                else -> {
                    vBadgeBg.setBackgroundResource(R.drawable.cin_series_ep_badge_play)
                    ivWatched.visibility = View.GONE
                    ivPlayIcon.visibility = View.VISIBLE
                    ivPlayIcon.setColorFilter(0xFFF1F5F9.toInt())
                }
            }
        }
    }

    private fun loadWatchedState(identityKey: String) {
        loadedKeys.add(identityKey)
        adapterScope.launch {
            val state = withContext(Dispatchers.IO) {
                watchedRepository()?.getState(identityKey)
            }
            var changed = false
            if (state?.isWatched == true) {
                if (watchedKeys.add(identityKey)) changed = true
            }
            // Compute progress percent from stored resume position
            if (state != null && showsPartialProgress(state)) {
                val pct = ((state.progressMs.toFloat() / state.durationMs) * 100).toInt().coerceIn(0, 100)
                if (pct in 1..99 && progressByKey[identityKey] != pct) {
                    progressByKey[identityKey] = pct
                    changed = true
                }
            }
            if (changed) notifyItemChangedByIdentity(identityKey)
        }
    }

    /** Started but neither finished nor marked watched — the RESUME progress-strip case. */
    private fun showsPartialProgress(state: WatchedStateEntity): Boolean =
        !state.isWatched && state.durationMs > 0 && state.progressMs > 0

    private fun toggleWatched(context: Context, item: EpisodeUiModel, identityKey: String, watched: Boolean) {
        adapterScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val timestamp = System.currentTimeMillis()
                    val repo = watchedRepository()
                    val state = repo.getState(identityKey) ?: WatchedStateEntity(
                        identityKey = identityKey,
                        contentType = WatchedStateEntity.CONTENT_TYPE_EPISODE,
                        source = WatchedStateEntity.SOURCE_DEBRID,
                        title = item.title,
                        year = seriesYear(context),
                        seriesId = seriesIdentity(context),
                        seasonNumber = selectedSeasonNumber(context),
                        episodeNumber = item.episodeNumber,
                        durationMs = (item.durationMinutes ?: 0).toLong() * 60_000L,
                        updatedAt = timestamp
                    )
                    repo.setManualWatched(state, watched, timestamp)
                }
            }
            result.onSuccess {
                if (watched) watchedKeys.add(identityKey) else watchedKeys.remove(identityKey)
                notifyItemChangedByIdentity(identityKey)
                Toast.makeText(context, if (watched) "Marked watched" else "Marked unwatched", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Unable to update watched state", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun watchedIdentityKey(context: Context, item: EpisodeUiModel): String? {
        if (!isDebridContext(context)) return null
        return WatchedIdentityBuilder.debridEpisode(
            seriesIdentity = seriesIdentity(context),
            seasonNumber = selectedSeasonNumber(context),
            episodeNumber = item.episodeNumber,
            seriesTitle = seriesTitle(context),
            seriesYear = seriesYear(context),
            fallbackDiscriminator = item.id.ifBlank { item.title }
        )
    }

    private fun watchedRepository(): WatchedStateRepository {
        val appContext = lastContext?.applicationContext
            ?: throw IllegalStateException("Missing adapter context")
        return EntryPointAccessors.fromApplication(
            appContext,
            SeriesEpisodeAdapterEntryPoint::class.java
        ).watchedStateRepository()
    }

    private var lastContext: Context? = null

    private fun isDebridContext(context: Context): Boolean {
        lastContext = context.applicationContext
        val activity = context.findActivity()
        return activity?.intent?.getBooleanExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, false) == true
    }

    private fun seriesIdentity(context: Context): String? =
        context.findActivity()?.intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_ID)

    private fun seriesTitle(context: Context): String? =
        context.findActivity()?.intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_NAME)

    private fun seriesYear(context: Context): String? =
        context.findActivity()?.intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_RELEASE_DATE)?.take(4)

    private fun selectedSeasonNumber(context: Context): Int? {
        val activity = context.findActivity() ?: return null
        return runCatching {
            val field = activity.javaClass.getDeclaredField("selectedSeasonKey")
            field.isAccessible = true
            (field.get(activity) as? String)?.toIntOrNull()
        }.getOrNull()
    }

    private fun Context.findActivity(): Activity? {
        var current = this
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun notifyItemChangedByIdentity(identityKey: String) {
        val index = currentList.indexOfFirst { watchedIdentityKey(lastContext ?: return, it) == identityKey }
        if (index != -1) notifyItemChanged(index)
    }

    private fun notifyItemChangedById(id: String) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) notifyItemChanged(index)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EpisodeUiModel>() {
            override fun areItemsTheSame(old: EpisodeUiModel, new: EpisodeUiModel) = old.id == new.id
            override fun areContentsTheSame(old: EpisodeUiModel, new: EpisodeUiModel) = old == new
        }
    }
}
