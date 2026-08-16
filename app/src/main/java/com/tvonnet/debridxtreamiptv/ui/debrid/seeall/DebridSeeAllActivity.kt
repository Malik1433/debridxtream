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
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices
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

    // D1: the M13 phone font scale reached only MainActivity and the two detail activities, so
    // this screen rendered at the TV's px÷2 sizes — 6-9sp — in the hand. Same one-liner as there.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.tvonnet.debridxtreamiptv.util.phoneScaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // D1: the manifest already pins this activity to landscape, but going through
        // the M13 helper keeps every screen on ONE mechanism — a future change to the
        // orientation rule would otherwise miss the two Debrid activities silently.
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debrid_see_all)

        val rowId = intent.getStringExtra(EXTRA_ROW_ID) ?: ""
        val rowTitle = intent.getStringExtra(EXTRA_ROW_TITLE) ?: ""

        val tvTitle = findViewById<TextView>(R.id.textViewTitle)
        tvTitle.text = rowTitle

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewItems)
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val tvError = findViewById<TextView>(R.id.tvError)

        val layoutManager = GridLayoutManager(this, 6)
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
