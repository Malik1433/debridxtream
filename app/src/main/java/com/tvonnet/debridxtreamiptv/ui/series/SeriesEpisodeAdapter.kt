package com.tvonnet.debridxtreamiptv.ui.series

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
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
interface SeriesEpisodeAdapterEntryPoint {
    fun watchedStateRepository(): WatchedStateRepository
}

class SeriesEpisodeAdapter(
    private val onEpisodeClick: (EpisodeUiModel) -> Unit,
    private val onEpisodeFocused: (EpisodeUiModel) -> Unit
) : ListAdapter<EpisodeUiModel, SeriesEpisodeAdapter.EpisodeViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    private var selectedEpisodeId: String? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchedKeys = mutableSetOf<String>()

    fun submitEpisodes(newItems: List<EpisodeUiModel>, selectedId: String?) {
        selectedEpisodeId = selectedId
        submitList(newItems)
    }

    fun setSelectedEpisode(episodeId: String?) {
        if (selectedEpisodeId == episodeId) return

        val previousId = selectedEpisodeId
        selectedEpisodeId = episodeId

        previousId?.let { notifyItemChangedById(it) }
        episodeId?.let { notifyItemChangedById(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeV2Binding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.id == selectedEpisodeId)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    inner class EpisodeViewHolder(private val binding: com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeV2Binding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        init {
            binding.vFocusBorder.setBackgroundResource(R.drawable.bg_episode_focus_glow)
            binding.vFocusBorder.visibility = View.INVISIBLE

            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                onEpisodeClick(item)
            }

            binding.root.setOnFocusChangeListener { v, hasFocus ->
                binding.vFocusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) {
                    val item = getItem(bindingAdapterPosition)
                    onEpisodeFocused(item)
                }
            }

            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: return@setOnLongClickListener false
                val item = getItem(position)
                val context = binding.root.context
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
            binding.tvTitle.text = item.title
            binding.tvEpisodeNumber.text = "E${item.episodeNumber ?: 0}"

            val context = itemView.context
            val episodeLabel = item.episodeNumber?.let { "Episode $it" } ?: "Episode"
            val durationText = item.durationMinutes?.let { " • ${it} min" } ?: ""
            val plotSnippet = item.description?.take(40)?.let { " • $it..." } ?: ""
            
            binding.tvDuration.text = "$episodeLabel$durationText$plotSnippet"

            val thumbnail = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            Glide.with(itemView)
                .load(thumbnail)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(binding.ivEpisodeThumb)

            itemView.isSelected = isSelected
            val identityKey = watchedIdentityKey(itemView.context, item)
            binding.ivWatchedIndicator.visibility = if (identityKey != null && watchedKeys.contains(identityKey)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (identityKey != null && !watchedKeys.contains(identityKey)) {
                loadWatchedState(identityKey)
            }
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
        item: EpisodeUiModel,
        identityKey: String,
        watched: Boolean
    ) {
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

    private fun seriesIdentity(context: Context): String? {
        val intent = context.findActivity()?.intent
        return intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_ID)
    }

    private fun seriesTitle(context: Context): String? {
        val intent = context.findActivity()?.intent
        return intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_NAME)
    }

    private fun seriesYear(context: Context): String? {
        val releaseDate = context.findActivity()?.intent?.getStringExtra(SeriesDetailActivity.EXTRA_SERIES_RELEASE_DATE)
        return releaseDate?.take(4)
    }

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
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    private fun notifyItemChangedById(id: String) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EpisodeUiModel>() {
            override fun areItemsTheSame(oldItem: EpisodeUiModel, newItem: EpisodeUiModel): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: EpisodeUiModel, newItem: EpisodeUiModel): Boolean =
                oldItem == newItem
        }
    }
}

