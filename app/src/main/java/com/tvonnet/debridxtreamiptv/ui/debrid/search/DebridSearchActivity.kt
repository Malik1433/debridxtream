package com.tvonnet.debridxtreamiptv.ui.debrid.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DebridSearchActivity : AppCompatActivity() {

    private val viewModel: DebridSearchViewModel by viewModels()
    private lateinit var adapter: DebridSearchAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debrid_search)

        val rvResults: RecyclerView = findViewById(R.id.rv_search_results)
        val etSearch: EditText = findViewById(R.id.et_search)
        progressBar = findViewById(R.id.progress_bar)
        tvMessage = findViewById(R.id.tv_message)

        adapter = DebridSearchAdapter(onItemClick = { item ->
            navigateToDetails(item)
        })

        rvResults.layoutManager = GridLayoutManager(this, 4)
        rvResults.adapter = adapter
        rvResults.itemAnimator = null
        rvResults.setHasFixedSize(true)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Focus the search bar immediately
        etSearch.requestFocus()

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is SearchUiState.Idle -> {
                        progressBar.visibility = View.GONE
                        tvMessage.visibility = View.VISIBLE
                        tvMessage.text = "Type to search..."
                        adapter.submitList(emptyList())
                    }
                    is SearchUiState.Loading -> {
                        progressBar.visibility = View.VISIBLE
                        tvMessage.visibility = View.GONE
                    }
                    is SearchUiState.Success -> {
                        progressBar.visibility = View.GONE
                        tvMessage.visibility = View.GONE
                        
                        // Phase 2: Focus-safe list submission
                        adapter.submitList(state.items) {
                            if (rvResults.hasFocus()) {
                                // If the grid already has focus, ensure it stays on a valid item
                                FocusCoordinator.requestFocus("DEBRID_SEARCH") {
                                    rvResults.post {
                                        rvResults.post {
                                            if (rvResults.focusedChild == null && state.items.isNotEmpty()) {
                                                rvResults.getChildAt(0)?.requestFocus()
                                            }
                                            FocusCoordinator.release("DEBRID_SEARCH")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is SearchUiState.Empty -> {
                        progressBar.visibility = View.GONE
                        tvMessage.visibility = View.VISIBLE
                        tvMessage.text = state.message
                        adapter.submitList(emptyList())
                    }
                    is SearchUiState.Error -> {
                        progressBar.visibility = View.GONE
                        tvMessage.visibility = View.VISIBLE
                        tvMessage.text = "Error: ${state.message}"
                        adapter.submitList(emptyList())
                    }
                }
            }
        }
    }

    private fun navigateToDetails(item: DebridContentItem) {
        if (item.type == "movie") {
            val intent = Intent(this, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid") // Crucial for Debrid logic
            }
            startActivity(intent)
        } else if (item.type == "show" || item.type == "series") {
            val intent = Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        }
    }
}
