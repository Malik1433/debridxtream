package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

class ContinueWatchingAdapter(
    private var items: List<ContinueWatchingItem>,
    private val onItemClick: (ContinueWatchingItem) -> Unit,
    private val onItemFocused: (Int, ContinueWatchingItem) -> Unit = { _, _ -> },
    private val onOpenDetail: (ContinueWatchingItem) -> Unit = { },
    private val onRemoveItem: (ContinueWatchingItem) -> Unit = { }
) : RecyclerView.Adapter<ContinueWatchingAdapter.ContinueWatchingViewHolder>() {

    init {
        setHasStableIds(true)
    }
    
    fun updateItems(newItems: List<ContinueWatchingItem>) {
        if (items == newItems) return
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableIdFor(oldItems[oldItemPosition]) == stableIdFor(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContinueWatchingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return ContinueWatchingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ContinueWatchingViewHolder, position: Int) {
        android.util.Log.e("HISTORY_DEBUG", "ContinueWatchingAdapter: onBindViewHolder position=$position")
        holder.bind(items[position], onItemClick, onItemFocused, onOpenDetail, onRemoveItem)
    }
    
    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long {
        return stableIdFor(items[position])
    }

    fun getStableItemIdAt(position: Int): Long? {
        return items.getOrNull(position)?.let { stableIdFor(it) }
    }

    fun findPositionByStableId(stableId: Long): Int {
        return items.indexOfFirst { stableIdFor(it) == stableId }
    }

    companion object {
        fun stableIdFor(item: ContinueWatchingItem): Long {
            val identity = buildString {
                append(item.contentType.name)
                append(':')
                append(item.tmdbId ?: item.imdbId ?: item.contentId)
                append(':')
                append(item.seriesTitle ?: item.title)
                append(':')
                append(item.source)
            }
            var hash = 1125899906842597L
            identity.forEach { char ->
                hash = 31L * hash + char.code
            }
            return hash
        }
    }
    
    class ContinueWatchingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivContinuePoster: ImageView = itemView.findViewById(R.id.iv_continue_poster)
        private val tvContinueTitle: TextView = itemView.findViewById(R.id.tv_continue_title)
        private val progressWatch: ProgressBar = itemView.findViewById(R.id.progress_watch)
        private val tvContinueProgress: TextView = itemView.findViewById(R.id.tv_continue_progress)
        private val tvContinueTypeBadge: TextView = itemView.findViewById(R.id.tv_continue_type_badge)
        private val quickInfo: View? = itemView.findViewById(R.id.quick_info_bubble)
        private val btnMore: View? = itemView.findViewById(R.id.btn_cw_more)
        private val usesDpadFocus: Boolean =
            itemView.resources.getBoolean(R.bool.ui_uses_dpad_focus)
        private val qiInfo: View? = quickInfo?.findViewById(R.id.qi_info)
        private val qiActions: View? = quickInfo?.findViewById(R.id.qi_actions)
        private val qiOpen: TextView? = quickInfo?.findViewById(R.id.qi_open)
        private val qiRemove: TextView? = quickInfo?.findViewById(R.id.qi_remove)
        private var dwellRunnable: Runnable? = null
        private var inActionsMode = false
        private var swallowNextSelectUp = false
        private var suppressNextClick = false
        private var longPressHandled = false

        init {
            com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(
                itemView.findViewById(R.id.view_focus_glow)
            )
        }

        fun bind(
            item: ContinueWatchingItem,
            onClick: (ContinueWatchingItem) -> Unit,
            onFocused: (Int, ContinueWatchingItem) -> Unit,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit
        ) {
            suppressNextClick = false
            longPressHandled = false
            resetBubbleToInfo()
            bindCardContent(item)
            attachCardListeners(item, onClick, onOpenDetail, onRemoveItem)
            attachChipListeners(item, onOpenDetail, onRemoveItem)
            attachFocusListener(item, onFocused)
        }

        private fun bindCardContent(item: ContinueWatchingItem) {
            tvContinueTitle.text = formatTitle(item)
            tvContinueProgress.text = item.formattedProgress
            tvContinueTypeBadge.text = formatTypeBadge(item)
            progressWatch.progress = item.progressPercentage
            itemView.scaleX = if (itemView.hasFocus()) 1.06f else 1.0f
            itemView.scaleY = if (itemView.hasFocus()) 1.06f else 1.0f
            itemView.elevation = if (itemView.hasFocus()) 22f else 6f

            val resolvedUrl = GlobalConfig.resolveIconUrl(item.posterUrl ?: item.backdropUrl)
            ivContinuePoster.loadPosterOrPlaceholder(resolvedUrl)

            qiInfo?.let {
                itemView.findViewById<TextView>(R.id.qi_title)?.text = formatTitle(item)
                itemView.findViewById<TextView>(R.id.qi_tag)?.text = "RESUME"
                itemView.findViewById<TextView>(R.id.qi_sub)?.text = item.formattedProgress
            }
        }

        private fun attachCardListeners(
            item: ContinueWatchingItem,
            onClick: (ContinueWatchingItem) -> Unit,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit
        ) {
            itemView.setOnClickListener {
                if (suppressNextClick) {
                    suppressNextClick = false
                    return@setOnClickListener
                }
                onClick(item)
            }

            itemView.isLongClickable = true
            itemView.setOnLongClickListener {
                if (!longPressHandled && !inActionsMode) {
                    longPressHandled = true
                    suppressNextClick = true
                    openOptions(item, onOpenDetail, onRemoveItem, fromKey = false)
                }
                true
            }

            // M2c: the phone also gets a VISIBLE way in, because the rulebook is explicit that a
            // long-press may be a shortcut but never the only route to an action. On TV the button
            // stays gone — the bubble is reached by holding OK, and an extra tile would only be one
            // more thing for the D-pad to stop at.
            btnMore?.let { button ->
                button.isVisible = !usesDpadFocus
                button.setOnClickListener {
                    openOptions(item, onOpenDetail, onRemoveItem, fromKey = false)
                }
            }

            attachCardKeyListener(item, onOpenDetail, onRemoveItem)
        }

        /** Holding OK on the remote is the TV's long-press. */
        private fun attachCardKeyListener(
            item: ContinueWatchingItem,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit
        ) {
            itemView.setOnKeyListener { _, keyCode, event ->
                val isSelectKey =
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount > 0 &&
                        isSelectKey && !longPressHandled && !inActionsMode -> {
                        longPressHandled = true
                        suppressNextClick = true
                        openOptions(item, onOpenDetail, onRemoveItem, fromKey = true)
                        true
                    }
                    event.action == KeyEvent.ACTION_UP && isSelectKey -> {
                        longPressHandled = false
                        false
                    }
                    else -> false
                }
            }
        }

        // Chip wiring (used in actions mode)
        private fun attachChipListeners(
            item: ContinueWatchingItem,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit
        ) {
            qiOpen?.setOnClickListener { onOpenDetail(item) }
            qiRemove?.setOnClickListener {
                exitActionsMode()
                onRemoveItem(item)
            }
            val chipKeyListener = View.OnKeyListener { _, keyCode, event ->
                val isSelectKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP -> {
                        exitActionsMode(); itemView.requestFocus(); true
                    }
                    // Swallow the key-up from the same long-press that opened the menu,
                    // so it doesn't instantly "click" the focused option.
                    isSelectKey && event.action == KeyEvent.ACTION_UP && swallowNextSelectUp -> {
                        swallowNextSelectUp = false; true
                    }
                    else -> false
                }
            }
            qiOpen?.setOnKeyListener(chipKeyListener)
            qiRemove?.setOnKeyListener(chipKeyListener)
        }

        private fun attachFocusListener(
            item: ContinueWatchingItem,
            onFocused: (Int, ContinueWatchingItem) -> Unit
        ) {
            itemView.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(140L)
                    .start()
                view.elevation = if (hasFocus) 22f else 6f
                tvContinueTitle.isActivated = hasFocus
                if (!hasFocus) {
                    // Focus may have moved to a bubble chip (still inside this item) — only
                    // tear the bubble down once focus has truly left the whole item.
                    itemView.post {
                        if (!itemView.hasFocus()) {
                            suppressNextClick = false
                            longPressHandled = false
                            if (inActionsMode) exitActionsMode()
                            cancelDwellInfo()
                        }
                    }
                    return@setOnFocusChangeListener
                }
                // Card regained focus — if we were showing quick actions, drop back to info.
                if (inActionsMode) exitActionsMode()
                scheduleDwellInfo()
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFocused(position, item)
                }
            }
        }

        /**
         * M2c: one entry point, two shapes. TV keeps the in-card bubble; a touch device gets a
         * bottom sheet. Routing here rather than at each call site means the D-pad long-press, the
         * touch long-press and the new options button can never drift apart.
         */
        private fun openOptions(
            item: ContinueWatchingItem,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit,
            fromKey: Boolean
        ) {
            if (usesDpadFocus) {
                enterActionsMode(item, onOpenDetail, onRemoveItem, fromKey)
                return
            }
            ContinueWatchingOptionsSheet.show(itemView.context, item, onOpenDetail, onRemoveItem)
        }

        /**
         * Dwell quick-info: bubble appears only after ~450ms of resting on the card.
         *
         * Focus-dwell is a 10-foot idiom — there is no "resting" with a finger — and under touch
         * the card is not focusable anyway (M7c), so this never fires on a phone today. The guard
         * is here so that stays true if focus behaviour ever changes again.
         */
        private fun scheduleDwellInfo() {
            if (!usesDpadFocus) return
            val bubble = quickInfo ?: return
            cancelDwellInfo()
            val runnable = Runnable {
                if (itemView.hasFocus() && !inActionsMode) {
                    resetBubbleToInfo()
                    bubble.visibility = View.VISIBLE
                    bubble.alpha = 0f
                    bubble.translationY = 8f
                    bubble.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180)
                        .start()
                }
            }
            dwellRunnable = runnable
            itemView.postDelayed(runnable, 450L)
        }

        private fun cancelDwellInfo() {
            dwellRunnable?.let { itemView.removeCallbacks(it) }
            dwellRunnable = null
            quickInfo?.let { bubble ->
                bubble.animate().cancel()
                bubble.visibility = View.GONE
            }
        }

        /** Long-press: swap the bubble from its resume-info content to quick actions. */
        private fun enterActionsMode(
            item: ContinueWatchingItem,
            onOpenDetail: (ContinueWatchingItem) -> Unit,
            onRemoveItem: (ContinueWatchingItem) -> Unit,
            fromKey: Boolean
        ) {
            val bubble = quickInfo ?: return
            dwellRunnable?.let { itemView.removeCallbacks(it) }
            dwellRunnable = null
            inActionsMode = true
            swallowNextSelectUp = fromKey
            qiInfo?.visibility = View.GONE
            qiActions?.visibility = View.VISIBLE
            bubble.animate().cancel()
            bubble.visibility = View.VISIBLE
            bubble.alpha = 1f
            bubble.translationY = 0f
            qiOpen?.requestFocus()
        }

        private fun exitActionsMode() {
            inActionsMode = false
            swallowNextSelectUp = false
            resetBubbleToInfo()
            quickInfo?.visibility = View.GONE
        }

        /** Restore the bubble's default (resume-info) content. */
        private fun resetBubbleToInfo() {
            inActionsMode = false
            qiActions?.visibility = View.GONE
            qiInfo?.visibility = View.VISIBLE
        }

        private fun formatTypeBadge(item: ContinueWatchingItem): String {
            return when (item.contentType) {
                ContentType.EPISODE -> "SERIES"
                ContentType.SERIES -> "SERIES"
                ContentType.MOVIE -> "MOVIE"
                ContentType.LIVE_TV -> "LIVE"
            }
        }

        private fun formatTitle(item: ContinueWatchingItem): String {
            val baseTitle = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title
            val seasonNumber = item.seasonNumber
            val episodeNumber = item.episodeNumber
            return if (item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE &&
                seasonNumber != null &&
                episodeNumber != null
            ) {
                val episodeLabel = String.format(java.util.Locale.US, "S%02dE%02d", seasonNumber, episodeNumber)
                "$baseTitle - $episodeLabel"
            } else {
                baseTitle
            }
        }
    }
}
