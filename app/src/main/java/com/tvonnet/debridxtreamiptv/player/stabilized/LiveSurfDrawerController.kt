package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus

/**
 * C8: the two-panel channel surf drawer (channel list, plus the category picker behind it), the
 * preview strip that follows focus, and the ON AIR NOW mini-EPG.
 *
 * It also **owns the zap state** — the channel list, the tuned index, the per-channel EPG and the
 * favourite ids. That is not just relocation: those four were fields on the OSD manager, and every
 * reader of them lives here. The OSD and the TV Guide now ask this controller instead, which is why
 * the drawer is the one place that decides what "the current channel list" means.
 *
 * Behaviours that are easy to lose and were kept deliberately:
 *  - **`updatePreservingFocus` on a favourites update** (CC-1). Favourites can change while the
 *    drawer is open; a plain rebind drops the user's row mid-scroll.
 *  - **`pendingCategoryRefocus`.** Picking a category reloads the list *asynchronously*, so focus
 *    and the preview have to be re-asserted when the new list arrives — not when the click happens.
 *  - **LEFT is progressive, BACK is its mirror.** LEFT goes channels → categories → (from
 *    categories) close; BACK goes categories → channels → close. Collapsing either into a single
 *    "close" makes the second panel unreachable by D-pad.
 *
 * Bodies moved verbatim from `LivePlayerOsdManager`.
 */
internal class LiveSurfDrawerController(
    private val root: View,
    private val host: Host,
) {
    /** What the drawer needs from the OSD around it. */
    interface Host {
        fun showToast(message: String)

        /** Drawer opening: cancel the OSD auto-hide, then dim the OSD to ~12% (spec). */
        fun onDrawerOpening()

        /** Drawer closed: restore the OSD (or hand focus to the video) and refocus the controls. */
        fun onDrawerClosed(refocusControls: Boolean)

        fun tuneChannel(index: Int)
        fun selectCategory(categoryId: String)
    }

    private companion object {
        const val OPEN_MS = 300L
        const val SCRIM_MS = 250L
        const val CATS_OPEN_MS = 320L
        const val CATS_CLOSE_MS = 260L
        const val CLOSE_MS = 250L
        const val SCRIM_OUT_MS = 200L
    }

    private val easeOutExpo = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val drawerScrim: View = root.findViewById(R.id.live_drawer_scrim)
    private val drawer: View = root.findViewById(R.id.live_surf_drawer)
    private val surfTitle: TextView = root.findViewById(R.id.live_surf_title)
    private val surfCount: TextView = root.findViewById(R.id.live_surf_count)
    private val surfList: RecyclerView = root.findViewById(R.id.rv_live_surf)

    private val previewBg: View = root.findViewById(R.id.live_preview_bg)
    private val previewLogo: FrameLayout = root.findViewById(R.id.live_preview_logo)
    private val previewLogoText: TextView = root.findViewById(R.id.live_preview_logo_text)
    private val previewLogoImg: ImageView = root.findViewById(R.id.live_preview_logo_img)
    private val previewName: TextView = root.findViewById(R.id.live_preview_name)
    private val previewNow: TextView = root.findViewById(R.id.live_preview_now)
    private val previewQuality: TextView = root.findViewById(R.id.live_preview_quality)
    private val previewProgress: ProgressBar = root.findViewById(R.id.live_preview_progress)

    private val catsDrawer: View = root.findViewById(R.id.live_cats_drawer)
    private val catsList: RecyclerView = root.findViewById(R.id.rv_live_cats)

    private val onAirClock: TextView = root.findViewById(R.id.live_onair_clock)
    private val onAirList: RecyclerView = root.findViewById(R.id.rv_live_onair)

    private enum class Panel { NONE, CHANNELS, BOTH }
    private var panel: Panel = Panel.NONE

    // ── the zap state this controller owns ─────────────────────────────────────
    private var zapChannels: List<ZapChannel> = emptyList()
    private var zapIndex: Int = -1
    private var favoriteIds: Set<String> = emptySet()
    private var surfEpg: Map<String, EpgEntity> = emptyMap()
    private var categories: List<XtreamCategory> = emptyList()
    private var currentCategoryId: String? = null
    private var currentCategoryName: String? = null
    private var pendingCategoryRefocus = false
    private var previewIndex: Int = -1

    val isOpen: Boolean get() = panel != Panel.NONE
    fun channels(): List<ZapChannel> = zapChannels
    fun tunedIndex(): Int = zapIndex

    private val surfAdapter = LiveSurfChannelAdapter(
        onChannelClick = { index ->
            close(refocusControls = false)
            host.tuneChannel(index)
        },
        onChannelFocus = { index -> updatePreview(index) }
    )

    private val categoryAdapter: LiveSurfCategoryAdapter = LiveSurfCategoryAdapter { category ->
        val catId = category.category_id ?: return@LiveSurfCategoryAdapter
        currentCategoryId = catId
        currentCategoryName = category.category_name
        // Reload the zap list for the picked category; the refreshed list arrives
        // async via setZapState, which re-submits the category highlight + re-asserts
        // focus because of this flag.
        pendingCategoryRefocus = true
        host.selectCategory(catId)
    }

    private val onAirAdapter = LiveOnAirAdapter()

    init {
        surfList.layoutManager = LinearLayoutManager(root.context)
        surfList.adapter = surfAdapter
        catsList.layoutManager = LinearLayoutManager(root.context)
        catsList.adapter = categoryAdapter
        onAirList.layoutManager = LinearLayoutManager(root.context)
        onAirList.adapter = onAirAdapter
        onAirList.isFocusable = false
        drawerScrim.setOnClickListener { close() }
    }

    // ── state in ───────────────────────────────────────────────────────────────

    fun setZapState(categoryId: String, categoryName: String?, channels: List<ZapChannel>, index: Int) {
        currentCategoryId = categoryId
        currentCategoryName = categoryName
        zapChannels = channels
        zapIndex = index
        surfTitle.text = categoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, channels.size)
        refreshOnAir()
        if (panel != Panel.NONE) {
            surfAdapter.submit(channels, index, favoriteIds, rowEpgMap(), categoryColor())
            categoryAdapter.submit(categories, currentCategoryId, categoryCounts())
            // A category switch refreshes the list async; re-assert focus + preview.
            if (pendingCategoryRefocus) {
                pendingCategoryRefocus = false
                updatePreview(index.coerceAtLeast(0))
                focusSurfRow(index.coerceAtLeast(0))
            }
        }
    }

    fun setSurfEpg(map: Map<String, EpgEntity>) {
        surfEpg = map
        refreshOnAir()
        if (panel != Panel.NONE) {
            // CC-1: this fires on the 30s minute tick while the drawer is open; keep the
            // user's focused row instead of letting notifyDataSetChanged drop/flicker it.
            surfList.updatePreservingFocus {
                surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
            }
        }
        if (previewIndex in zapChannels.indices) bindPreviewStrip(zapChannels[previewIndex])
    }

    /** Category picker data for the second-LEFT panel. */
    fun setCategories(cats: List<XtreamCategory>) {
        categories = cats
        if (panel == Panel.BOTH) categoryAdapter.submit(cats, currentCategoryId, categoryCounts())
    }

    fun setFavorites(ids: Set<String>) {
        favoriteIds = ids
        if (panel != Panel.NONE) {
            // CC-1: favorites can update while the drawer is open — preserve the focused row.
            surfList.updatePreservingFocus {
                surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
            }
        }
    }

    /** The OSD's minute ticker drives the ON AIR clock and re-renders the strip. */
    fun onMinuteTick(label: String) {
        onAirClock.text = label
        refreshOnAir()
    }

    // ── open / close ───────────────────────────────────────────────────────────

    fun open() {
        if (zapChannels.isEmpty()) {
            host.showToast(root.context.getString(R.string.player_zap_loading))
            return
        }
        host.onDrawerOpening()
        panel = Panel.CHANNELS
        surfTitle.text = currentCategoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, zapChannels.size)
        surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
        updatePreview(zapIndex.coerceAtLeast(0))
        drawerScrim.alpha = 0f
        drawerScrim.isVisible = true
        drawerScrim.animate().alpha(1f).setDuration(SCRIM_MS).start()
        drawer.isVisible = true
        drawer.translationX = -drawer.layoutParams.width.toFloat()
        drawer.animate().translationX(0f).setDuration(OPEN_MS).setInterpolator(easeOutExpo).start()
        catsDrawer.isVisible = false
        focusSurfRow(zapIndex.coerceAtLeast(0))
    }

    fun close(refocusControls: Boolean = true) {
        if (panel == Panel.NONE) return
        panel = Panel.NONE
        pendingCategoryRefocus = false
        drawerScrim.animate().alpha(0f).setDuration(SCRIM_OUT_MS)
            .withEndAction { drawerScrim.isVisible = false }.start()
        val catW = catsDrawer.layoutParams.width.toFloat()
        if (catsDrawer.isVisible) {
            catsDrawer.animate().translationX(-catW).setDuration(CLOSE_MS)
                .withEndAction { catsDrawer.isVisible = false }.start()
        }
        drawer.animate().translationX(-drawer.layoutParams.width.toFloat()).setDuration(CLOSE_MS)
            .withEndAction { drawer.isVisible = false; drawer.translationX = 0f }.start()
        host.onDrawerClosed(refocusControls)
    }

    /** Second LEFT — slide the category picker in far-left; channel list shifts right. */
    private fun openCategories() {
        if (categories.isEmpty()) {
            host.showToast(root.context.getString(R.string.live_osd_categories_loading))
            return
        }
        panel = Panel.BOTH
        categoryAdapter.submit(categories, currentCategoryId, categoryCounts())
        val catW = catsDrawer.layoutParams.width.toFloat()
        catsDrawer.isVisible = true
        catsDrawer.translationX = -catW
        catsDrawer.animate().translationX(0f).setDuration(CATS_OPEN_MS).setInterpolator(easeOutExpo).start()
        drawer.animate().translationX(catW).setDuration(CATS_OPEN_MS).setInterpolator(easeOutExpo).start()
        val selected = categories.indexOfFirst { it.category_id == currentCategoryId }.coerceAtLeast(0)
        catsList.scrollToPosition(selected)
        catsList.post {
            catsList.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
                ?: catsList.requestFocus()
        }
    }

    /** RIGHT from the category picker — collapse it; channel list slides back. */
    private fun closeCategories() {
        if (panel != Panel.BOTH) return
        panel = Panel.CHANNELS
        val catW = catsDrawer.layoutParams.width.toFloat()
        catsDrawer.animate().translationX(-catW).setDuration(CATS_CLOSE_MS).setInterpolator(easeOutExpo)
            .withEndAction { catsDrawer.isVisible = false }.start()
        drawer.animate().translationX(0f).setDuration(CATS_CLOSE_MS).setInterpolator(easeOutExpo).start()
        focusSurfRow(previewIndex.coerceAtLeast(0))
    }

    /** BACK inside the drawer: categories → channels → close. Returns true if consumed. */
    fun handleBack(): Boolean {
        if (panel == Panel.NONE) return false
        if (panel == Panel.BOTH) {
            closeCategories()
            return true
        }
        close()
        return true
    }

    /** While the drawer is open it owns LEFT/RIGHT; list focus handles up/down/OK natively. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (panel == Panel.NONE) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Channels → open category picker (both); both → close everything.
                when (panel) {
                    Panel.CHANNELS -> openCategories()
                    Panel.BOTH -> close()
                    Panel.NONE -> {}
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // From the category picker, RIGHT collapses back to the channel list.
                if (panel == Panel.BOTH) {
                    closeCategories(); true
                } else false
            }
            else -> false // list focus handles up/down/OK natively
        }
    }

    private fun focusSurfRow(position: Int) {
        surfList.scrollToPosition(position)
        surfList.post {
            surfList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                ?: surfList.requestFocus()
        }
    }

    // ── EPG-derived row data ───────────────────────────────────────────────────

    private fun toRowEpg(e: EpgEntity?): SurfRowEpg? {
        if (e == null || e.stop <= e.start) return e?.let { SurfRowEpg(it.title, 0, null) }
        val now = System.currentTimeMillis()
        val dur = (e.stop - e.start).coerceAtLeast(1L)
        val elapsed = (now - e.start).coerceIn(0L, dur)
        val pct = (elapsed * 100 / dur).toInt()
        val leftMin = ((dur - elapsed) / 60_000L).coerceAtLeast(1L)
        return SurfRowEpg(e.title, pct, "${leftMin}m left")
    }

    private fun rowEpgMap(): Map<String, SurfRowEpg> =
        surfEpg.mapNotNull { (id, e) -> toRowEpg(e)?.let { id to it } }.toMap()

    private fun categoryColor(): Int = LiveChannelVisuals.accentColor(currentCategoryName)

    private fun categoryCounts(): Map<String, Int> {
        val current = currentCategoryId?.let { mapOf(it to zapChannels.size) } ?: emptyMap()
        // Favorites count is already tracked client-side (favoriteIds, kept live by
        // PlayerViewModel.observeLiveFavorites) — free to show without a query.
        return current + (FAVORITES_CATEGORY_ID to favoriteIds.size)
    }

    /** ON AIR NOW mini-EPG: prev / current / next around the tuned channel. */
    private fun refreshOnAir() {
        val n = zapChannels.size
        if (n == 0 || zapIndex < 0) {
            onAirAdapter.submit(emptyList())
            return
        }
        val offsets = when {
            n >= 3 -> listOf(-1, 0, 1)
            n == 2 -> listOf(0, 1)
            else -> listOf(0)
        }
        val rows = offsets.map { off ->
            val idx = ((zapIndex + off) % n + n) % n
            val ch = zapChannels[idx]
            val e = surfEpg[ch.streamId]
            OnAirRow(
                name = ch.name,
                num = String.format(java.util.Locale.US, "%03d", idx + 1),
                nowTitle = e?.title,
                logoUrl = ch.logoUrl,
                isActive = off == 0,
                progress = toRowEpg(e)?.progress ?: 0
            )
        }
        onAirAdapter.submit(rows)
    }

    // ── preview strip (follows focus in the channel list) ──────────────────────

    private fun updatePreview(index: Int) {
        if (index !in zapChannels.indices) return
        previewIndex = index
        bindPreviewStrip(zapChannels[index])
    }

    private fun bindPreviewStrip(ch: ZapChannel) {
        val density = root.resources.displayMetrics.density
        previewName.text = ch.name
        previewLogoText.text = LiveChannelVisuals.channelInitials(ch.name)
        previewLogo.background = LiveChannelVisuals.channelTileGradient(ch.name, 6f * density)
        previewBg.background = LiveChannelVisuals.watermarkGradient(ch.name)
        if (!ch.logoUrl.isNullOrBlank()) {
            previewLogoImg.isVisible = true
            GlideUtils.loadChannelLogo(previewLogoImg, ch.logoUrl)
        } else {
            previewLogoImg.isVisible = false
        }
        val e = surfEpg[ch.streamId]
        previewNow.text = e?.title
        previewNow.isVisible = !e?.title.isNullOrBlank()
        val rp = toRowEpg(e)
        if (rp != null && rp.progress > 0) {
            previewProgress.isVisible = true
            previewProgress.progress = rp.progress
        } else {
            previewProgress.isVisible = false
        }
        bindPreviewQuality(ch.name)
    }

    private fun bindPreviewQuality(name: String?) {
        val q = LiveChannelVisuals.detectQuality(name)
        if (q == null) {
            previewQuality.isVisible = false
            return
        }
        previewQuality.isVisible = true
        previewQuality.text = q
        previewQuality.setBackgroundResource(
            if (q.contains("4K")) R.drawable.bg_live_badge_quality_4k
            else R.drawable.bg_live_badge_quality_hd
        )
    }
}
