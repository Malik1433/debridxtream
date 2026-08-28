package com.tvonnet.debridxtreamiptv.ui.debrid.seeall

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverAdapter
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.tvonnet.debridxtreamiptv.util.usePortraitOnTouchDevices
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridItemType

@AndroidEntryPoint
class DebridSeeAllActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROW_ID = "EXTRA_ROW_ID"
        const val EXTRA_ROW_TITLE = "EXTRA_ROW_TITLE"
    }

    private val viewModel: DebridSeeAllViewModel by viewModels()
    private lateinit var adapter: DebridDiscoverAdapter

    private var lastFocusedPosition = -1

    // NOT phoneScaledContext. D1 added that 1.6x to rescue the TV layout's 6-9sp type while this
    // screen was still wearing it; it has a real phone layout in layout/ now (the television file
    // moved to layout-television/), authored in true dp and sp, and scaling that would blow it
    // apart. Same call MovieDetailActivity makes for the same reason.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // BEFORE super.onCreate, so the orientation is settled before the first inflate. Portrait
        // now, because the phone layout IS portrait: the landscape lock was the price of wearing
        // the 10-foot screen, and this was the last phone-reachable page still paying it. The
        // manifest is `unspecified` so the window is never BUILT landscape and swung round after.
        usePortraitOnTouchDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debrid_see_all)

        // The phone app bar's Back. Null on the television, which has neither the button nor a
        // need for one — BACK is a key there.
        findViewById<android.view.View>(R.id.btnBack)?.setOnClickListener { finish() }

        val rowId = intent.getStringExtra(EXTRA_ROW_ID) ?: ""
        val rowTitle = intent.getStringExtra(EXTRA_ROW_TITLE) ?: ""

        val tvTitle = findViewById<TextView>(R.id.textViewTitle)
        tvTitle.text = rowTitle

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewItems)
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val tvError = findViewById<TextView>(R.id.tvError)

        // Six across a television, three in the hand — the same count PhoneBrowseFragment settled
        // on: two waste a third of a screen whose whole job is density, four drop the poster to
        // 88dp where titles stop being legible.
        val columns = if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) 6 else 3
        val layoutManager = GridLayoutManager(this, columns)
        recyclerView.layoutManager = layoutManager
        recyclerView.setHasFixedSize(true)
        recyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS

        if (savedInstanceState != null) {
            lastFocusedPosition = savedInstanceState.getInt("LAST_FOCUSED_POSITION", -1)
        }

        adapter = DebridDiscoverAdapter(
            onItemClick = { item ->
                onItemClick(item)
            },
            onItemFocused = { item ->
                updateBackdrop(item)
                val focusedView = recyclerView.focusedChild ?: return@DebridDiscoverAdapter
                val viewHolder = recyclerView.findContainingViewHolder(focusedView) ?: return@DebridDiscoverAdapter
                lastFocusedPosition = viewHolder.bindingAdapterPosition
            },
            onLoadMore = {
                viewModel.loadNextPage()
            }
        )
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                if (totalItemCount <= lastVisibleItem + 7) { // Threshold increased to 7
                    viewModel.loadNextPage()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderSeeAllState(state, recyclerView, layoutManager, progressBar, tvError)
                }
            }
        }

        if (savedInstanceState == null) {
            viewModel.initRow(rowId)
        }
    }

    private fun renderSeeAllState(
        state: DebridSeeAllUiState,
        recyclerView: RecyclerView,
        layoutManager: GridLayoutManager,
        progressBar: android.widget.ProgressBar,
        tvError: TextView
    ) {
        when (state) {
            is com.tvonnet.debridxtreamiptv.ui.debrid.seeall.DebridSeeAllUiState.Content -> {
                progressBar.visibility = android.view.View.GONE
                tvError.visibility = android.view.View.GONE
                recyclerView.visibility = android.view.View.VISIBLE
                adapter.submitItems(state.items, state.canLoadMore)

                // Restore focus memory if available
                if (lastFocusedPosition != -1 && lastFocusedPosition < state.items.size) {
                    recyclerView.post {
                        val view = layoutManager.findViewByPosition(lastFocusedPosition)
                        view?.requestFocus()
                    }
                }
            }
            is com.tvonnet.debridxtreamiptv.ui.debrid.seeall.DebridSeeAllUiState.Error -> {
                progressBar.visibility = android.view.View.GONE
                recyclerView.visibility = android.view.View.GONE
                tvError.visibility = android.view.View.VISIBLE
                tvError.text = state.message
            }
            com.tvonnet.debridxtreamiptv.ui.debrid.seeall.DebridSeeAllUiState.Loading -> {
                // Only show full loader if empty
                if (adapter.itemCount == 0) {
                    progressBar.visibility = android.view.View.VISIBLE
                    tvError.visibility = android.view.View.GONE
                    recyclerView.visibility = android.view.View.GONE
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_FOCUSED_POSITION", lastFocusedPosition)
    }

    private fun onItemClick(item: CatalogItem) {
        if (!DebridItemType.isSeries(item.type)) {
            val intent = Intent(this, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
                intent.getStringExtra(EXTRA_ROW_TITLE)?.takeIf { it.isNotBlank() }?.let {
                    putExtra(MovieDetailActivity.EXTRA_SOURCE_RAIL, it)
                }
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        }
    }

    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBackdrop: Runnable? = null

    private fun updateBackdrop(item: CatalogItem) {
        // IMG-2: debounce full-screen backdrop decode to focus-rest (~300ms).
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        val imageUrl = item.backdropUrl ?: item.posterUrl ?: return
        val task = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            val ivBackdrop = findViewById<ImageView>(R.id.ivBackdrop)
            Glide.with(this)
                .load(imageUrl)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .override(960, 540)
                .transition(DrawableTransitionOptions.withCrossFade(500))
                .into(ivBackdrop)
        }
        pendingBackdrop = task
        backdropHandler.postDelayed(task, 300L)
    }
}
