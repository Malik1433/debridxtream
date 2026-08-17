package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow
import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner

/** Library tab: Watching (Continue Watching) / Saved (My Library) / Completed, from Debrid rows. */
internal class StremioLibrarySection(
    private val fragment: StremioHomeFragment,
    private val root: View,
    private val actions: StremioDebridActions
) {
    private val d = root.resources.displayMetrics.density
    private val ctx = root.context

    /** The two rulebooks meet here: a 23dp pill with an 8sp label is a 10-foot control. */
    private val isPhone = !root.resources.getBoolean(R.bool.ui_uses_dpad_focus)

    /** Tab geometry, chosen once, so the builder below carries no device branches inside it. */
    private data class TabMetrics(
        val height: Int,
        val padH: Int,
        val gap: Int,
        val icon: Float,
        val text: Float,
        val radius: Float,
    )

    private val tabMetrics = if (isPhone) {
        TabMetrics(height = 48, padH = 16, gap = 8, icon = 16f, text = 13f, radius = 10f)
    } else {
        TabMetrics(height = 23, padH = 11, gap = 2, icon = 6.5f, text = 8f, radius = 5f)
    }

    private val tabsRow: LinearLayout = root.findViewById(R.id.libTabsRow)
    private val count: TextView = root.findViewById(R.id.lib_count)
    private val grid: RecyclerView = root.findViewById(R.id.libraryGrid)
    private val empty: TextView = root.findViewById(R.id.lib_empty)
    private val adapter = StremioLibraryAdapter()

    private var watching: List<DebridContentItem> = emptyList()
    private var saved: List<DebridContentItem> = emptyList()
    private var active = "watching"

    /**
     * Two buckets, because two are what this app can actually fill.
     *
     * "Completed" was a third tab whose contents were a hard-coded empty list — it could never show
     * anything, whatever the customer watched. A tab that cannot fill is not a feature, it is a
     * dead end with a label, so it is gone rather than mocked. When finished-watching state is
     * readable per debrid title, it comes back with real rows behind it.
     */
    private val tabs = listOf(
        Triple("watching", "Watching", R.drawable.ic_stremio_clock),
        Triple("saved", "Saved", R.drawable.ic_stremio_bookmark)
    )

    init {
        val screenW = ctx.resources.displayMetrics.widthPixels
        // Phone: 16dp gutters and 124dp cards with a 12dp gap. Television: its own old maths.
        val span = if (isPhone) {
            ((screenW - 32 * d) / (136 * d)).toInt().coerceAtLeast(2)
        } else {
            ((screenW - 70 * d) / (81 * d)).toInt().coerceAtLeast(1)
        }
        grid.layoutManager = GridLayoutManager(ctx, span)
        grid.adapter = adapter
        grid.itemAnimator = null
        buildTabs()
    }

    fun bind(rows: List<DebridRow>) {
        watching = rows.firstOrNull { it.id == "continue_watching" }?.items?.filter { it.type != "see_all" } ?: emptyList()
        saved = rows.firstOrNull { it.id == "my_library" }?.items?.filter { it.type != "see_all" } ?: emptyList()
        rebuild()
    }

    fun firstFocusable(): View? = if (tabsRow.childCount > 0) tabsRow.getChildAt(0) else grid

    private fun buildTabs() {
        tabsRow.removeAllViews()
        tabs.forEach { (key, label, icon) ->
            val tab = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((tabMetrics.padH * d).toInt(), 0, (tabMetrics.padH * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (tabMetrics.height * d).toInt(),
                ).apply { marginEnd = (tabMetrics.gap * d).toInt() }
                isFocusable = true
                addView(ImageView(ctx).apply {
                    setImageResource(icon)
                    val size = (tabMetrics.icon * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                        .apply { marginEnd = (6 * d).toInt() }
                })
                addView(TextView(ctx).apply {
                    textSize = tabMetrics.text
                    includeFontPadding = false
                })
                setOnClickListener { active = key; styleTabs(); rebuild() }
            }
            StremioFocus.attach(tab, 1.05f)
            tabsRow.addView(tab)
        }
        styleTabs()
    }

    private fun styleTabs() {
        for (i in 0 until tabsRow.childCount) {
            val tab = tabsRow.getChildAt(i) as LinearLayout
            val (key, label, _) = tabs[i]
            val isActive = key == active
            val fg = if (isActive) 0xFF041014.toInt() else 0xFF94A3B8.toInt()
            (tab.getChildAt(0) as ImageView).setColorFilter(if (isActive) 0xFF041014.toInt() else 0xFF64748B.toInt())
            // The count rides in the tab on a phone: how much is in each bucket is the question
            // this screen exists to answer, and asking the customer to switch tabs to find out is
            // one tap too many.
            val itemCount = if (key == "watching") watching.size else saved.size
            val text = if (isPhone) "$label  $itemCount" else label
            (tab.getChildAt(1) as TextView).apply {
                this.text = text
                setTextColor(fg)
                StremioFonts.apply(this, R.font.outfit_semibold)
            }
            tab.background = if (isActive) {
                StremioGradients.diagonal(0xFF0077FF.toInt(), 0xFF00F0FF.toInt(), tabMetrics.radius, d)
            } else {
                GradientDrawable().apply {
                    cornerRadius = tabMetrics.radius * d
                    setColor(0x0DFFFFFF)
                    setStroke((1 * d).toInt(), 0x1AFFFFFF)
                }
            }
        }
    }

    private fun rebuild() {
        val bucket = when (active) {
            "watching" -> LibBucket(watching, "WATCHING", 0xFFF1F5F9.toInt(), 0xCC0077FF.toInt())
            else -> LibBucket(saved, "SAVED", 0xFF041014.toInt(), 0xFFA78BFA.toInt())
        }
        styleTabs()
        count.text = count.context.getString(R.string.f_titles_count, bucket.items.size)
        empty.visibility = if (bucket.items.isEmpty()) View.VISIBLE else View.GONE
        // An empty bucket says what would fill it. "Nothing here yet" alone leaves the customer to
        // guess whether the app is broken or they simply have not done the thing.
        empty.setText(
            if (active == "watching") R.string.phone_library_empty_watching
            else R.string.phone_library_empty_saved
        )
        adapter.submit(bucket.items.map { item ->
            val progress = if ((item.durationMs ?: 0L) > 0L)
                ((item.resumePositionMs ?: 0L) * 100 / (item.durationMs ?: 1L)).toInt() else 0
            StremioLibraryCard(
                title = MediaTitleCleaner.clean(item.title),
                year = item.year ?: "",
                status = bucket.status, statusColor = bucket.statusColor, statusBg = bucket.statusBg,
                hasProgress = active == "watching" && progress > 0, progress = progress,
                posterUrl = item.posterUrl ?: item.backdropUrl,
                onClick = { actions.click(item) }
            )
        })
    }

    private data class LibBucket(
        val items: List<DebridContentItem>, val status: String, val statusColor: Int, val statusBg: Int
    )
}
