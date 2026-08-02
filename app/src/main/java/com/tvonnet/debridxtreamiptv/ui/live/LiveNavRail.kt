package com.tvonnet.debridxtreamiptv.ui.live

import android.animation.ValueAnimator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator

/**
 * The Live TV v2 nav rail: a 44dp icon strip that expands to 140dp (design 88px -> 280px) while
 * it holds focus, fading in the labels and a scrim over the page behind it.
 *
 * Sections are routed through [SectionNavigator], shared with Home's rail.
 */
class LiveNavRail(
    private val host: Fragment,
    private val root: View,
    private val onDpadRight: () -> Boolean
) {

    /** Design easing: cubic-bezier(0.22, 1, 0.36, 1). */
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val rail: View? = root.findViewById(R.id.livev2_rail)
    private val scrim: View? = root.findViewById(R.id.livev2_rail_scrim)
    private val brandText: View? = root.findViewById(R.id.livev2_rail_brand_text)
    private val sectionLabel: View? = root.findViewById(R.id.livev2_rail_section_label)
    private val rvItems: RecyclerView? = root.findViewById(R.id.livev2_rail_items)
    private val settingsItem: View? = root.findViewById(R.id.livev2_rail_settings)

    private var widthAnimator: ValueAnimator? = null
    private var expanded = false
    private var adapter: RailAdapter? = null

    /** Focus can bounce between the rail's own children; only collapse once it has truly left. */
    private var collapseCheckPending = false

    fun setup() {
        val context = host.context ?: return

        val items = buildList {
            add(RailItem(SectionNavigator.SECTION_SEARCH, context.getString(R.string.nav_search), R.drawable.ic_search))
            add(RailItem(SectionNavigator.SECTION_HOME, context.getString(R.string.nav_home), R.drawable.ic_home))
            add(RailItem(SectionNavigator.SECTION_LIVE, context.getString(R.string.nav_live_tv), R.drawable.ic_live_tv))
            add(RailItem(SectionNavigator.SECTION_MOVIES, context.getString(R.string.nav_movies), R.drawable.ic_movie))
            add(RailItem(SectionNavigator.SECTION_SERIES, context.getString(R.string.nav_series), R.drawable.ic_series))
            // Tier gate: NORMAL devices must not see a Debrid entry at all.
            if (SectionNavigator.isAllowed(context, SectionNavigator.SECTION_DEBRID)) {
                add(RailItem(SectionNavigator.SECTION_DEBRID, context.getString(R.string.nav_debrid), R.drawable.ic_dns))
            }
        }

        adapter = RailAdapter(
            items = items,
            // Live TV is the section we're already on — it's the active row and a no-op.
            activeSection = SectionNavigator.SECTION_LIVE,
            onFocusChanged = { hasFocus -> onRailFocusChanged(hasFocus) },
            onDpadRight = onDpadRight,
            onSelected = { section -> openSection(section) }
        )

        rvItems?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            itemAnimator = null
            adapter = this@LiveNavRail.adapter
            setHasFixedSize(true)
            isFocusable = false
        }

        setupSettings(context)
    }

    private fun setupSettings(context: android.content.Context) {
        val item = settingsItem ?: return
        item.findViewById<ImageView>(R.id.livev2_nav_icon)?.apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(COLOR_IDLE)
        }
        item.findViewById<TextView>(R.id.livev2_nav_label)?.text = context.getString(R.string.nav_settings)
        item.findViewById<View>(R.id.livev2_nav_indicator)?.isVisible = false
        item.findViewById<View>(R.id.livev2_nav_slot)?.isSelected = false

        item.setOnClickListener { openSection(SectionNavigator.SECTION_SETTINGS) }
        item.setOnFocusChangeListener { _, hasFocus -> onRailFocusChanged(hasFocus) }
        item.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                onDpadRight()
        }
    }

    private fun openSection(section: String) {
        // "live" is the current screen — re-opening it would push a duplicate onto the back stack.
        if (section == SectionNavigator.SECTION_LIVE) return
        SectionNavigator.navigate(host, section)
    }

    /** True while the rail owns focus, so the fragment can route BACK/LEFT accordingly. */
    fun hasFocus(): Boolean = rail?.findFocus() != null

    fun focusActiveItem(): Boolean {
        val rv = rvItems ?: return false
        val target = adapter?.activePosition() ?: 0
        val holder = rv.findViewHolderForAdapterPosition(target)
        return if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            rv.scrollToPosition(target)
            rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            true
        }
    }

    private fun onRailFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            collapseCheckPending = false
            setExpanded(true)
            return
        }
        // Defer: focus may just be hopping to the next rail child.
        if (collapseCheckPending) return
        collapseCheckPending = true
        rail?.post {
            collapseCheckPending = false
            if (rail.findFocus() == null) setExpanded(false)
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val view = rail ?: return
        val context = view.context

        val from = view.layoutParams?.width ?: collapsedWidth(context)
        val to = if (value) expandedWidth(context) else collapsedWidth(context)
        if (from == to) return

        view.setBackgroundResource(
            if (value) R.drawable.livev2_rail_bg_expanded else R.drawable.livev2_rail_bg
        )

        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 320L
            interpolator = ease
            addUpdateListener { anim ->
                val lp = view.layoutParams ?: return@addUpdateListener
                lp.width = anim.animatedValue as Int
                view.layoutParams = lp
            }
            start()
        }

        val targetAlpha = if (value) 1f else 0f
        listOfNotNull(brandText, sectionLabel).forEach { label ->
            label.animate().cancel()
            label.animate().alpha(targetAlpha).setDuration(if (value) 200L else 140L).start()
        }
        adapter?.setLabelsVisible(value)
        settingsItem?.findViewById<TextView>(R.id.livev2_nav_label)?.let { label ->
            label.animate().cancel()
            label.animate().alpha(targetAlpha).setDuration(if (value) 200L else 140L).start()
        }

        scrim?.let { s ->
            s.animate().cancel()
            if (value) s.isVisible = true
            s.animate()
                .alpha(targetAlpha)
                .setDuration(280L)
                .withEndAction { if (!value) s.isVisible = false }
                .start()
        }
    }

    fun cleanup() {
        widthAnimator?.cancel()
        widthAnimator = null
        rail?.animate()?.cancel()
        scrim?.animate()?.cancel()
        brandText?.animate()?.cancel()
        sectionLabel?.animate()?.cancel()
        settingsItem?.apply {
            setOnClickListener(null)
            onFocusChangeListener = null
            setOnKeyListener(null)
        }
        rvItems?.adapter = null
        adapter = null
    }

    private fun collapsedWidth(context: android.content.Context) =
        context.resources.getDimensionPixelSize(R.dimen.livev2_rail_width_collapsed)

    private fun expandedWidth(context: android.content.Context) =
        context.resources.getDimensionPixelSize(R.dimen.livev2_rail_width_expanded)

    data class RailItem(val section: String, val label: String, val iconRes: Int)

    private class RailAdapter(
        private val items: List<RailItem>,
        private val activeSection: String,
        private val onFocusChanged: (Boolean) -> Unit,
        private val onDpadRight: () -> Boolean,
        private val onSelected: (String) -> Unit
    ) : RecyclerView.Adapter<RailAdapter.Holder>() {

        private var labelsVisible = false
        private val callbacks = Holder.Callbacks(onFocusChanged, onDpadRight, onSelected)

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = items[position].section.hashCode().toLong()

        fun activePosition() = items.indexOfFirst { it.section == activeSection }.coerceAtLeast(0)

        fun setLabelsVisible(visible: Boolean) {
            if (labelsVisible == visible) return
            labelsVisible = visible
            notifyItemRangeChanged(0, items.size, PAYLOAD_LABEL)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_livev2_nav, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], items[position].section == activeSection, labelsVisible, callbacks)
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_LABEL)) {
                holder.applyLabelAlpha(labelsVisible)
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount() = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val slot: FrameLayout? = itemView.findViewById(R.id.livev2_nav_slot)
            private val icon: ImageView? = itemView.findViewById(R.id.livev2_nav_icon)
            private val label: TextView? = itemView.findViewById(R.id.livev2_nav_label)
            private val indicator: View? = itemView.findViewById(R.id.livev2_nav_indicator)

            fun applyLabelAlpha(visible: Boolean) {
                label?.animate()?.cancel()
                label?.animate()?.alpha(if (visible) 1f else 0f)
                    ?.setDuration(if (visible) 200L else 140L)?.start()
            }

            /** The adapter-level callbacks, constant across binds — bundled once. */
            data class Callbacks(
                val onFocusChanged: (Boolean) -> Unit,
                val onDpadRight: () -> Boolean,
                val onSelected: (String) -> Unit
            )

            fun bind(
                item: RailItem,
                isActive: Boolean,
                labelsVisible: Boolean,
                callbacks: Callbacks
            ) {
                val (onFocusChanged, onDpadRight, onSelected) = callbacks
                icon?.setImageResource(item.iconRes)
                label?.text = item.label
                label?.alpha = if (labelsVisible) 1f else 0f

                itemView.isSelected = isActive
                slot?.isSelected = isActive
                indicator?.isVisible = isActive
                // On the active row the slot is a bright gradient, so the glyph inverts to dark.
                icon?.setColorFilter(if (isActive) COLOR_ON_ACCENT else COLOR_IDLE)
                label?.setTextColor(if (isActive) COLOR_ACTIVE_TEXT else COLOR_IDLE)

                itemView.setOnClickListener { onSelected(item.section) }
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && !isActive) icon?.setColorFilter(COLOR_FOCUSED)
                    else if (!hasFocus && !isActive) icon?.setColorFilter(COLOR_IDLE)
                    onFocusChanged(hasFocus)
                }
                itemView.setOnKeyListener { _, keyCode, event ->
                    event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        onDpadRight()
                }
            }
        }

        private companion object {
            const val PAYLOAD_LABEL = "label"
        }
    }

    private companion object {
        const val COLOR_FOCUSED = 0xFF00F0FF.toInt()
        const val COLOR_IDLE = 0xFF64748B.toInt()
        const val COLOR_ACTIVE_TEXT = 0xFFF1F5F9.toInt()
        const val COLOR_ON_ACCENT = 0xFF041014.toInt()
    }
}
