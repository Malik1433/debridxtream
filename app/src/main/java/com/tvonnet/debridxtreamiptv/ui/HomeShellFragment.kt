package com.tvonnet.debridxtreamiptv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.favorites.FavoritesFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragmentNew
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeShellFragment : Fragment() {
    
    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var credentialsPrefs: CredentialsPreferences
    
    private lateinit var leftMenu: LinearLayout
    private lateinit var menuLive: TextView
    private lateinit var menuVod: TextView
    private lateinit var menuSeries: TextView
    private lateinit var menuSearch: TextView
    private lateinit var menuFavorites: TextView
    private lateinit var menuSettings: TextView
    private lateinit var contentContainer: FrameLayout
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home_shell, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize repository with saved credentials
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        
        if (serverUrl != null && username != null && password != null) {
            repository.ensureInitialized(serverUrl, username, password)
        }
        
        leftMenu = view.findViewById(R.id.left_menu)
        menuLive = view.findViewById(R.id.menu_live)
        menuVod = view.findViewById(R.id.menu_vod)
        menuSeries = view.findViewById(R.id.menu_series)
        menuSearch = view.findViewById(R.id.menu_search)
        menuFavorites = view.findViewById(R.id.menu_favorites)
        menuSettings = view.findViewById(R.id.menu_settings)
        contentContainer = view.findViewById(R.id.content_container)
        
        setupMenu()
    }
    
    override fun onStart() {
        super.onStart()
        // Set default focus to left menu (first item: Live TV)
        menuLive.requestFocus()
    }
    
    private fun setupMenu() {
        menuLive.setOnClickListener {
            selectMenuItem(0)
        }
        
        menuVod.setOnClickListener {
            selectMenuItem(1)
        }
        
        menuSeries.setOnClickListener {
            selectMenuItem(2)
        }
        
        menuSearch.setOnClickListener {
            selectMenuItem(3)
        }
        
        menuFavorites.setOnClickListener {
            selectMenuItem(4)
        }
        
        menuSettings.setOnClickListener {
            selectMenuItem(5)
        }
        
        // Select Live TV by default
        selectMenuItem(0)
    }
    
    private fun selectMenuItem(position: Int) {
        android.util.Log.d("HomeShellFragment", "selectMenuItem called with position: $position")
        
        // Update menu item selection visuals
        val menuItems = listOf(menuLive, menuVod, menuSeries, menuSearch, menuFavorites, menuSettings)
        menuItems.forEachIndexed { index, item ->
            item.isSelected = (index == position)
        }
        
        // Week 13: Smooth fragment transitions
        val fragment = when (position) {
            0 -> {
                android.util.Log.d("HomeShellFragment", "Creating LiveFragment")
                LiveFragment()
            }
            1 -> {
                android.util.Log.d("HomeShellFragment", "Creating VodFragment")
                VodFragment()
            }
            2 -> {
                android.util.Log.d("HomeShellFragment", "Creating SeriesFragment")
                SeriesFragment()
            }
            3 -> {
                android.util.Log.d("HomeShellFragment", "Creating SearchFragment")
                SearchFragment()
            }
            4 -> {
                android.util.Log.d("HomeShellFragment", "Creating FavoritesFragment")
                FavoritesFragment()
            }
            5 -> {
                android.util.Log.d("HomeShellFragment", "Creating SettingsFragmentNew")
                SettingsFragmentNew()
            }
            else -> {
                android.util.Log.d("HomeShellFragment", "Default: Creating LiveFragment")
                LiveFragment()
            }
        }
        
        showFragment(fragment)
    }
    
    private fun showFragment(fragment: Fragment) {
        // Week 13: Smooth fragment transitions with animations
        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,   // Enter animation
                R.anim.slide_out_left,    // Exit animation
                R.anim.slide_in_right,   // Pop enter animation
                R.anim.slide_out_left     // Pop exit animation
            )
            replace(R.id.content_container, fragment)
            setReorderingAllowed(true) // Enable optimizations
        }
    }
}

