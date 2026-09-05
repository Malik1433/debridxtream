package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EpisodeBrowserEntryPoint {
    fun watchedStateRepository(): WatchedStateRepository
}

/**
 * Controller for the shared Series Episode Browser overlay in PlayerActivity.
 * Handles the display and selection of episodes for both IPTV and Debrid sources.
 */
class EpisodeBrowserController(
    private val container: View,
    private val onEpisodeSelected: (EpisodeEntityV2) -> Unit
) {
    private val rvEpisodes: RecyclerView = container.findViewById(R.id.rv_episodes)
    private val tvTitle: TextView = container.findViewById(R.id.tv_episode_browser_title)
    private val tvMessage: TextView = container.findViewById(R.id.tv_episode_browser_message)
    private val pbLoading: View = container.findViewById(R.id.pb_loading)
    private val adapter = EpisodeAdapter(onEpisodeSelected)
    private var focusedPosition = RecyclerView.NO_POSITION
    private var fallbackImageUrl: String? = null

    // VOD Player redesign: stacked coverflow — the centred card sits in front (full size),
    // the cards above/below peek out ~half from behind it, receding with scale/dim/z-depth.
    private val cardOverlapPx = (86f * container.resources.displayMetrics.density).toInt()

    init {
        rvEpisodes.adapter = adapter
        rvEpisodes.isFocusable = true
        rvEpisodes.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvEpisodes.clipChildren = false
        rvEpisodes.clipToPadding = false
        (rvEpisodes.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

        // overlap consecutive cards so neighbours tuck behind the centre card
        rvEpisodes.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                outRect.top = if (pos > 0) -cardOverlapPx else 0
            }
        })

        rvEpisodes.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCoverflowTransforms()
            }
        })
        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { view.post { applyCoverflowTransforms() } }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        container.isVisible = false
    }

    /** Scale/dim/z each visible card by its distance from the vertical centre (front-to-back stack). */
    private fun applyCoverflowTransforms() {
        val cy = rvEpisodes.height / 2f
        for (i in 0 until rvEpisodes.childCount) {
            val child = rvEpisodes.getChildAt(i)
            val childCy = (child.top + child.bottom) / 2f
            val step = (child.height - cardOverlapPx).coerceAtLeast(1)
            val n = ((childCy - cy) / step)
            val an = kotlin.math.abs(n).coerceAtMost(2.5f)
            val scale = (1f - 0.13f * an).coerceAtLeast(0.6f)
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = (1f - 0.4f * an).coerceAtLeast(0.12f)
            // centre card in front (highest Z); neighbours recede behind it
            child.translationZ = 30f - 12f * an
        }
    }

    fun isVisible(): Boolean = container.isVisible

    fun show(
        episodes: List<EpisodeEntityV2>,
        currentEpisodeId: String?,
        seasonTitle: String? = null,
        fallbackImageUrl: String? = null
    ) {
        seasonTitle?.let { tvTitle.text = it }
        this.fallbackImageUrl = fallbackImageUrl
        adapter.setFallbackImageUrl(fallbackImageUrl)
        adapter.setSeriesTitle(seasonTitle)
        adapter.setCurrentEpisodeId(currentEpisodeId)
        Log.e("IPTV_EP_LOAD_FIX", "CONTROLLER submit count=${episodes.size}")
        adapter.submitList(episodes) {
            // Scroll to current episode if list is not empty
            val currentIndex = episodes.indexOfFirst { it.episodeId == currentEpisodeId }
            if (currentIndex != -1) {
                focusedPosition = currentIndex
                requestFocusAt(currentIndex)
            } else if (episodes.isNotEmpty()) {
                focusedPosition = 0
                requestFocusAt(0)
            }
        }
        val wasHidden = !container.isVisible
        container.isVisible = true
        if (wasHidden) animateIn()
        tvMessage.isVisible = false
        // If not loading, focus the list. If loading, focus the loading spinner or container.
        if (pbLoading.isVisible) {
            pbLoading.isFocusable = true
            pbLoading.requestFocus()
        } else {
            rvEpisodes.post { rvEpisodes.requestFocus() }
        }
    }

    fun hide() {
        container.animate().cancel()
        container.isVisible = false
    }

    /** VOD Player redesign: panel slide-in — translateX(80→0) + alpha(0→1), 400ms FastOutSlowIn. */
    private fun animateIn() {
        val dx = 80f * container.resources.displayMetrics.density
        container.translationX = dx
        container.alpha = 0f
        container.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(400L)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .start()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!container.isVisible || event.action != KeyEvent.ACTION_DOWN) return false
        // VOD Player redesign: right-side panel is a VERTICAL list.
        // UP/DOWN move within the list; LEFT (or BACK) closes; RIGHT is a no-op.
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveFocusBy(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveFocusBy(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                hide()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val position = focusedPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: rvEpisodes.getChildAdapterPosition(rvEpisodes.focusedChild ?: rvEpisodes)
                if (event.isLongPress) {
                    adapter.toggleWatchedAt(position, rvEpisodes.context)
                } else {
                    adapter.currentList.getOrNull(position)?.let(onEpisodeSelected)
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                hide()
                true
            }
            else -> false
        }
        Log.d("EP_BROWSER_FOCUS_FIX", "browser visible key=${event.keyCode} handled=$handled")
        return handled
    }

    fun setLoading(loading: Boolean) {
        Log.e("IPTV_EP_LOAD_FIX", "CONTROLLER loading=$loading")
        pbLoading.isVisible = loading
        rvEpisodes.isVisible = !loading
        tvMessage.isVisible = false
        if (loading) {
            pbLoading.isFocusable = true
            pbLoading.requestFocus()
        } else {
            rvEpisodes.post { rvEpisodes.requestFocus() }
        }
    }

    fun showMessage(message: String, seasonTitle: String? = null) {
        seasonTitle?.let { tvTitle.text = it }
        Log.e("IPTV_EP_LOAD_FIX", "CONTROLLER message=$message")
        adapter.submitList(emptyList())
        val wasHidden = !container.isVisible
        container.isVisible = true
        if (wasHidden) animateIn()
        pbLoading.isVisible = false
        rvEpisodes.isVisible = false
        tvMessage.text = message
        tvMessage.isVisible = true
        tvMessage.isFocusable = true
        tvMessage.requestFocus()
    }

    fun requestFocus() {
        if (pbLoading.isVisible) {
            pbLoading.isFocusable = true
            pbLoading.requestFocus()
        } else {
            val position = focusedPosition.takeIf { it != RecyclerView.NO_POSITION } ?: 0
            requestFocusAt(position)
        }
    }

    private fun moveFocusBy(delta: Int) {
        val itemCount = adapter.itemCount
        if (itemCount == 0) return
        val current = focusedPosition.takeIf { it in 0 until itemCount }
            ?: (rvEpisodes.getChildAdapterPosition(rvEpisodes.focusedChild ?: rvEpisodes)
                .takeIf { it in 0 until itemCount } ?: 0)
        val next = (current + delta).coerceIn(0, itemCount - 1)
        focusedPosition = next
        requestFocusAt(next)
    }

    private fun requestFocusAt(position: Int) {
        rvEpisodes.post {
            val lm = rvEpisodes.layoutManager as? LinearLayoutManager ?: return@post
            val existing = lm.findViewByPosition(position)
            if (existing != null) {
                // Smoothly bring the focused card to the vertical centre (coverflow feel).
                val delta = (existing.top + existing.height / 2) - rvEpisodes.height / 2
                if (delta != 0) rvEpisodes.smoothScrollBy(0, delta)
                existing.requestFocus()
                rvEpisodes.post { applyCoverflowTransforms() }
            } else {
                val approxCard = (164 * rvEpisodes.resources.displayMetrics.density).toInt()
                val offset = ((rvEpisodes.height - approxCard) / 2).coerceAtLeast(0)
                lm.scrollToPositionWithOffset(position, offset)
                rvEpisodes.post {
                    rvEpisodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                        ?: rvEpisodes.requestFocus()
                    applyCoverflowTransforms()
                }
            }
        }
    }

    private class EpisodeAdapter(
        private val onEpisodeSelected: (EpisodeEntityV2) -> Unit
    ) : ListAdapter<EpisodeEntityV2, EpisodeViewHolder>(EpisodeDiffCallback()) {

        private var currentEpisodeId: String? = null
        private var fallbackImageUrl: String? = null
        private var seriesTitle: String? = null
        private var appContext: Context? = null
        private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val watchedKeys = mutableSetOf<String>()

        fun setCurrentEpisodeId(id: String?) {
            currentEpisodeId = id
            notifyDataSetChanged()
        }

        fun setFallbackImageUrl(url: String?) {
            fallbackImageUrl = url?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            notifyDataSetChanged()
        }

        fun setSeriesTitle(title: String?) {
            seriesTitle = title
        }

        fun toggleWatchedAt(position: Int, context: Context): Boolean {
            val episode = currentList.getOrNull(position) ?: return false
            val identityKey = watchedIdentityKey(episode) ?: return false
            val isWatched = watchedKeys.contains(identityKey)
            val actionTitle = context.getString(
                if (isWatched) R.string.c_mark_as_unwatched else R.string.c_mark_as_watched
            )
            AlertDialog.Builder(context)
                .setTitle(episode.title ?: context.getString(R.string.f_episode_number, episode.episodeNumber))
                .setItems(arrayOf(actionTitle)) { _, _ ->
                    toggleWatched(context, episode, identityKey, !isWatched)
                }
                .show()
            return true
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            adapterScope.cancel()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            appContext = parent.context.applicationContext
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player_episode_browser, parent, false)
            return EpisodeViewHolder(view, onEpisodeSelected) { episode, context ->
                toggleWatchedAt(currentList.indexOf(episode), context)
            }
        }

        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            val episode = getItem(position)
            val identityKey = watchedIdentityKey(episode)
            holder.bind(
                episode = episode,
                isActive = episode.episodeId == currentEpisodeId,
                fallbackImageUrl = fallbackImageUrl,
                isWatched = identityKey != null && watchedKeys.contains(identityKey)
            )
            if (identityKey != null && !watchedKeys.contains(identityKey)) {
                loadWatchedState(identityKey)
            }
        }

        private fun loadWatchedState(identityKey: String) {
            adapterScope.launch {
                val isWatched = withContext(Dispatchers.IO) {
                    watchedRepository()?.getState(identityKey)?.isWatched == true
                }
                if (isWatched && watchedKeys.add(identityKey)) {
                    notifyItemChangedByIdentity(identityKey)
                }
            }
        }

        private fun toggleWatched(
            context: Context,
            episode: EpisodeEntityV2,
            identityKey: String,
            watched: Boolean
        ) {
            adapterScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val timestamp = System.currentTimeMillis()
                        val repo = watchedRepository(context) ?: error("Watched repository unavailable")
                        val state = repo.getState(identityKey) ?: WatchedStateEntity(
                            identityKey = identityKey,
                            contentType = WatchedStateEntity.CONTENT_TYPE_EPISODE,
                            source = WatchedStateEntity.SOURCE_DEBRID,
                            title = episode.title,
                            year = episode.releaseDate?.take(4),
                            seriesId = episode.seriesId,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber,
                            durationMs = episode.duration.takeIf { it > 0L } ?: episode.durationSecs?.toLongOrNull()?.times(1000L) ?: 0L,
                            updatedAt = timestamp
                        )
                        repo.setManualWatched(state, watched, timestamp)
                    }
                }
                result.onSuccess {
                    if (watched) {
                        watchedKeys.add(identityKey)
                    } else {
                        watchedKeys.remove(identityKey)
                    }
                    notifyItemChangedByIdentity(identityKey)
                    Toast.makeText(
                        context,
                        if (watched) "Marked watched" else "Marked unwatched",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.c_unable_to_update_watched_state), Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun watchedIdentityKey(episode: EpisodeEntityV2): String? {
            if (!isDebridEpisode(episode)) return null
            return WatchedIdentityBuilder.debridEpisode(
                seriesIdentity = episode.seriesId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                seriesTitle = seriesTitle,
                seriesYear = episode.releaseDate?.take(4),
                fallbackDiscriminator = episode.episodeId.ifBlank { episode.title }
            )
        }

        private fun isDebridEpisode(episode: EpisodeEntityV2): Boolean {
            val expectedPrefix = "${episode.seriesId}:${episode.seasonNumber}:${episode.episodeNumber}"
            return episode.episodeId == expectedPrefix
        }

        private fun watchedRepository(context: Context? = null): WatchedStateRepository? {
            val appContext = context?.applicationContext ?: appContext ?: return null
            return EntryPointAccessors.fromApplication(
                appContext,
                EpisodeBrowserEntryPoint::class.java
            ).watchedStateRepository()
        }

        private fun notifyItemChangedByIdentity(identityKey: String) {
            val index = currentList.indexOfFirst { watchedIdentityKey(it) == identityKey }
            if (index != -1) notifyItemChanged(index)
        }
    }

    private class EpisodeViewHolder(
        view: View,
        private val onEpisodeSelected: (EpisodeEntityV2) -> Unit,
        private val onEpisodeLongPressed: (EpisodeEntityV2, Context) -> Boolean
    ) : RecyclerView.ViewHolder(view) {
        private val ivThumb: ImageView = view.findViewById(R.id.iv_episode_thumb)
        private val placeholder: View = view.findViewById(R.id.layout_episode_placeholder)
        private val tvPlaceholderNumber: TextView = view.findViewById(R.id.tv_placeholder_episode_number)
        private val tvNumber: TextView = view.findViewById(R.id.tv_episode_number)
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvMeta: TextView = view.findViewById(R.id.tv_episode_meta)
        private val tvDuration: TextView = view.findViewById(R.id.tv_episode_duration)
        private val tvPlayingBadge: TextView = view.findViewById(R.id.tv_playing_badge)

        init {
            // Coverflow scale/dim/z is driven by list position (see applyCoverflowTransforms);
            // the focus listener only toggles the selected border + title marquee.
            itemView.setOnFocusChangeListener { view, hasFocus ->
                view.isActivated = hasFocus
                tvTitle.isSelected = hasFocus
            }
        }

        fun bind(
            episode: EpisodeEntityV2,
            isActive: Boolean,
            fallbackImageUrl: String?,
            isWatched: Boolean
        ) {
            tvNumber.text = "E${episode.episodeNumber}"
            tvPlaceholderNumber.text = "E${episode.episodeNumber}"
            tvTitle.text = episode.title ?: "Episode ${episode.episodeNumber}"
            tvMeta.text = tvMeta.context.getString(R.string.series_detail_season_name, episode.seasonNumber)
            tvDuration.text = formatEpisodeDuration(episode.durationSecs, episode.duration)
            tvDuration.isVisible = tvDuration.text.isNotBlank()
            tvPlayingBadge.text = if (isActive) "▶ NOW" else "✓ WATCHED"
            tvPlayingBadge.isVisible = isActive || isWatched
            itemView.isSelected = isActive

            val imageUrl = episode.thumbnail
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: fallbackImageUrl?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

            if (imageUrl == null) {
                ivThumb.isVisible = false
                placeholder.isVisible = true
                Glide.with(ivThumb).clear(ivThumb)
            } else {
                placeholder.isVisible = false
                ivThumb.isVisible = true
                Glide.with(ivThumb)
                    .load(imageUrl)
                    .placeholder(R.drawable.placeholder_banner)
                    .error(R.drawable.placeholder_banner)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivThumb)
            }

            itemView.setOnClickListener {
                onEpisodeSelected(episode)
            }
            itemView.setOnLongClickListener {
                onEpisodeLongPressed(episode, itemView.context)
            }
        }

        private fun formatEpisodeDuration(durationSecs: String?, durationMs: Long): String {
            val seconds = durationSecs?.toLongOrNull()
                ?: durationMs.takeIf { it > 0L }?.div(1000L)
                ?: return ""
            val minutes = seconds / 60L
            if (minutes <= 0L) return ""
            val hours = minutes / 60L
            val remainingMinutes = minutes % 60L
            return if (hours > 0L) {
                "${hours}h ${remainingMinutes}m"
            } else {
                "${minutes}m"
            }
        }
    }

    private class EpisodeDiffCallback : DiffUtil.ItemCallback<EpisodeEntityV2>() {
        override fun areItemsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem.episodeId == newItem.episodeId
        }

        override fun areContentsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem == newItem
        }
    }
}
