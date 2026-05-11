package com.tvonnet.debridxtreamiptv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SyncState
import com.tvonnet.debridxtreamiptv.data.model.SyncType
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InitialSyncFragment : Fragment() {

    @Inject
    lateinit var repository: XtreamRepository

    private lateinit var syncProgress: ProgressBar
    private lateinit var syncStatus: TextView
    private lateinit var syncPercent: TextView
    private lateinit var countLive: TextView
    private lateinit var countMovies: TextView
    private lateinit var countSeries: TextView
    private lateinit var syncError: TextView
    private lateinit var retryButton: View

    private var hasNavigated = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_initial_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        syncProgress = view.findViewById(R.id.sync_progress)
        syncStatus = view.findViewById(R.id.tv_sync_status)
        syncPercent = view.findViewById(R.id.tv_sync_percent)
        countLive = view.findViewById(R.id.tv_count_live)
        countMovies = view.findViewById(R.id.tv_count_movies)
        countSeries = view.findViewById(R.id.tv_count_series)
        syncError = view.findViewById(R.id.tv_sync_error)
        retryButton = view.findViewById(R.id.btn_sync_retry)

        retryButton.setOnClickListener {
            startSync()
        }

        observeSyncProgress()
        startSyncIfNeeded()
    }

    private fun startSyncIfNeeded() {
        val current = repository.syncProgress.value
        if (current.type == SyncType.INITIAL && current.state == SyncState.RUNNING) {
            return
        }
        if (current.type == SyncType.INITIAL && current.state == SyncState.SUCCESS) {
            navigateToHome()
            return
        }
        startSync()
    }

    private fun startSync() {
        syncError.visibility = View.GONE
        retryButton.visibility = View.GONE
        retryButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.syncInitialData()
            if (result.isSuccess) {
                navigateToHome()
            } else {
                showError(result.exceptionOrNull()?.message)
            }
        }
    }

    private fun observeSyncProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.syncProgress.collect { progress ->
                    if (progress.type != SyncType.INITIAL) return@collect

                    syncProgress.progress = progress.percent
                    syncPercent.text = "${progress.percent}%"
                    syncStatus.text = progress.stage
                    countLive.text = progress.liveCount.toString()
                    countMovies.text = progress.vodCount.toString()
                    countSeries.text = progress.seriesCount.toString()

                    when (progress.state) {
                        SyncState.ERROR -> showError(progress.errorMessage)
                        SyncState.SUCCESS -> navigateToHome()
                        SyncState.RUNNING -> retryButton.isEnabled = false
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun showError(message: String?) {
        syncStatus.text = "Sync failed"
        syncError.text = message ?: "Sync failed. Check your connection."
        syncError.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        retryButton.isEnabled = true
    }

    private fun navigateToHome() {
        if (hasNavigated) return
        hasNavigated = true
        requireActivity().supportFragmentManager.commit {
            replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.home.HomeFragment())
        }
    }
}
