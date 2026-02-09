package com.tvonnet.debridxtreamiptv.ui.epg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Week 11: EPG (Electronic Program Guide) Fragment
 * 
 * Displays program guide timeline with current and upcoming programs
 */
@AndroidEntryPoint
class EpgFragment : Fragment() {
    
    private val viewModel: EpgViewModel by viewModels()
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var adapter: EpgAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_epg, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        recyclerView = view.findViewById(R.id.recycler_view_epg)
        progressBar = view.findViewById(R.id.progress_bar_epg)
        emptyView = view.findViewById(R.id.text_empty_epg)
        
        // Setup RecyclerView
        adapter = EpgAdapter { program ->
            // Handle program click
            Toast.makeText(
                requireContext(),
                "Program: ${program.title}",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        
        // Observe UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    handleUiState(state)
                }
            }
        }
        
        // Initial fetch
        viewModel.fetchEpg()
    }
    
    private fun handleUiState(state: EpgUiState) {
        when (state) {
            is EpgUiState.Loading -> {
                progressBar.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.GONE
            }
            is EpgUiState.Success -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = state.message
            }
            is EpgUiState.ProgramsLoaded -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
                adapter.submitList(state.programs)
            }
            is EpgUiState.Empty -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = state.message
            }
            is EpgUiState.Error -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = state.message
            }
        }
    }
    
    /**
     * Load programs for a specific channel
     */
    fun loadChannelPrograms(channelId: String) {
        viewModel.loadChannelPrograms(channelId)
    }
    
    companion object {
        fun newInstance() = EpgFragment()
    }
}

