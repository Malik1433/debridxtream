# 🚀 DEBRIDXTREAMIPTV - PRODUCTION IMPLEMENTATION ROADMAP
## From MVP to World-Class Android TV App

**Current Version:** 1.0 MVP  
**Target Version:** 2.0 Production-Ready  
**Timeline:** 16 Weeks (4 Months)  
**Effort:** ~730 Development Hours

---

## 📊 EXECUTIVE SUMMARY

### Current State Analysis
- **Architecture Score:** 6/10 (No MVVM, No DI, No Testing)
- **Performance Score:** 5/10 (No pagination, memory issues)
- **Feature Completeness:** 4/10 (Missing EPG, Search, Favorites)
- **Production Readiness:** 3/10 (No analytics, poor error handling)

### Target State
- **Architecture Score:** 9/10 (MVVM + Hilt + Testing)
- **Performance Score:** 9/10 (Pagination + Multi-level caching)
- **Feature Completeness:** 9/10 (All essential features)
- **Production Readiness:** 9/10 (Analytics + Security + Monitoring)

---

## 🎯 PHASE 1: ARCHITECTURE FOUNDATION (Weeks 1-4)

### Week 1: MVVM Architecture Implementation

#### 1.1 Add Required Dependencies
**File:** `app/build.gradle`

```gradle
dependencies {
    // ViewModel & Lifecycle
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    
    // Hilt Dependency Injection
    implementation 'com.google.dagger:hilt-android:2.48'
    kapt 'com.google.dagger:hilt-compiler:2.48'
    
    // Room Database
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'
    
    // Paging 3
    implementation 'androidx.paging:paging-runtime-ktx:3.2.1'
    
    // Testing
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
    testImplementation 'androidx.arch.core:core-testing:2.2.0'
    testImplementation 'io.mockk:mockk:1.13.8'
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 2 hours  
**Deliverable:** Updated build.gradle with all dependencies

---

#### 1.2 Create Base ViewModel Classes
**New Files to Create:**

**`app/src/main/java/com/tvonnet/debridxtreamiptv/ui/base/BaseViewModel.kt`**
```kotlin
package com.tvonnet.debridxtreamiptv.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel<STATE, EVENT> : ViewModel() {
    
    protected abstract fun getInitialState(): STATE
    
    private val _uiState = MutableStateFlow(getInitialState())
    val uiState: StateFlow<STATE> = _uiState.asStateFlow()
    
    protected fun updateState(reducer: STATE.() -> STATE) {
        _uiState.value = _uiState.value.reducer()
    }
    
    protected val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        handleException(exception)
    }
    
    protected open fun handleException(exception: Throwable) {
        // Override in child classes
    }
    
    abstract fun onEvent(event: EVENT)
}
```

**`app/src/main/java/com/tvonnet/debridxtreamiptv/ui/base/UiState.kt`**
```kotlin
package com.tvonnet.debridxtreamiptv.ui.base

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val exception: Throwable? = null) : UiState<Nothing>()
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 4 hours  
**Deliverable:** Base classes for all ViewModels

---

#### 1.3 Implement LiveViewModel with StateFlow
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveViewModel.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.ui.live

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<XtreamStream> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val error: String? = null
)

sealed class LiveEvent {
    object LoadCategories : LiveEvent()
    data class SelectCategory(val categoryId: String) : LiveEvent()
    data class PlayChannel(val stream: XtreamStream) : LiveEvent()
    object Retry : LiveEvent()
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val savedStateHandle: SavedStateHandle
) : BaseViewModel<LiveUiState, LiveEvent>() {
    
    override fun getInitialState() = LiveUiState()
    
    init {
        // Auto-load categories on initialization
        onEvent(LiveEvent.LoadCategories)
    }
    
    override fun onEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.LoadCategories -> loadCategories()
            is LiveEvent.SelectCategory -> loadChannelsForCategory(event.categoryId)
            is LiveEvent.PlayChannel -> handlePlayChannel(event.stream)
            is LiveEvent.Retry -> retry()
        }
    }
    
    private fun loadCategories() {
        viewModelScope.launch(exceptionHandler) {
            updateState { copy(isLoadingCategories = true, error = null) }
            
            val cache = repository.readCache()
            val categories = cache?.live?.categories ?: emptyList()
            
            if (categories.isEmpty()) {
                updateState {
                    copy(
                        isLoadingCategories = false,
                        error = "No categories found. Please login and sync."
                    )
                }
            } else {
                updateState {
                    copy(
                        categories = categories,
                        isLoadingCategories = false,
                        selectedCategoryId = categories.firstOrNull()?.category_id
                    )
                }
                
                // Auto-load first category
                categories.firstOrNull()?.category_id?.let { categoryId ->
                    loadChannelsForCategory(categoryId)
                }
            }
        }
    }
    
    private fun loadChannelsForCategory(categoryId: String) {
        viewModelScope.launch(exceptionHandler) {
            updateState {
                copy(
                    selectedCategoryId = categoryId,
                    isLoadingChannels = true,
                    error = null
                )
            }
            
            val cache = repository.readCache()
            val streams = cache?.live?.streams?.filter {
                it.category_id == categoryId ||
                it.category_ids?.contains(categoryId.toIntOrNull() ?: -1) == true
            } ?: emptyList()
            
            updateState {
                copy(
                    channels = streams,
                    isLoadingChannels = false
                )
            }
        }
    }
    
    private fun handlePlayChannel(stream: XtreamStream) {
        // Emit navigation event (handle in Fragment)
        savedStateHandle["play_stream"] = stream
    }
    
    private fun retry() {
        val currentState = uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(LiveEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(LiveEvent.SelectCategory(categoryId))
            }
        }
    }
    
    override fun handleException(exception: Throwable) {
        updateState {
            copy(
                isLoadingCategories = false,
                isLoadingChannels = false,
                error = exception.message ?: "Unknown error occurred"
            )
        }
    }
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 6 hours  
**Deliverable:** Complete LiveViewModel with state management

---

#### 1.4 Refactor LiveFragment to use ViewModel
**Update:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.ui.live

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.databinding.FragmentLiveBinding
import com.tvonnet.debridxtreamiptv.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveFragment : Fragment() {
    
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: LiveViewModel by viewModels()
    
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        observeState()
    }
    
    private fun setupRecyclerViews() {
        // Categories
        categoryAdapter = CategoryAdapter { categoryId ->
            viewModel.onEvent(LiveEvent.SelectCategory(categoryId))
        }
        binding.rvLiveCategories.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }
        
        // Channels
        channelAdapter = ChannelAdapter { stream ->
            viewModel.onEvent(LiveEvent.PlayChannel(stream))
        }
        binding.rvChannels.apply {
            layoutManager = GridLayoutManager(context, 5)
            adapter = channelAdapter
        }
    }
    
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                renderState(state)
            }
        }
        
        // Observe navigation events
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.savedStateHandle
                .getStateFlow<XtreamStream?>("play_stream", null)
                .collect { stream ->
                    stream?.let { navigateToPlayer(it) }
                }
        }
    }
    
    private fun renderState(state: LiveUiState) {
        // Categories
        categoryAdapter.submitList(state.categories)
        
        // Channels
        channelAdapter.submitList(state.channels)
        
        // Loading states
        binding.progressCategories.visibility =
            if (state.isLoadingCategories) View.VISIBLE else View.GONE
        binding.progressChannels.visibility =
            if (state.isLoadingChannels) View.VISIBLE else View.GONE
        
        // Error state
        if (state.error != null) {
            binding.tvEmptyState.apply {
                text = state.error
                visibility = View.VISIBLE
            }
            binding.rvCategories.visibility = View.GONE
            binding.rvChannels.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvCategories.visibility = View.VISIBLE
            binding.rvChannels.visibility = View.VISIBLE
        }
    }
    
    private fun navigateToPlayer(stream: XtreamStream) {
        val credentialsPrefs = CredentialsPreferences(requireContext())
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        
        val streamUrl = "$serverUrl/live/$username/$password/${stream.stream_id}.ts"
        
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_STREAM_TITLE, stream.name ?: "Live Channel")
        }
        startActivity(intent)
        
        // Clear navigation event
        viewModel.savedStateHandle["play_stream"] = null
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 6 hours  
**Deliverable:** LiveFragment refactored with MVVM

---

### Week 2: Hilt Dependency Injection

#### 2.1 Setup Hilt Application
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/App.kt`

```kotlin
package com.tvonnet.debridxtreamiptv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize app-level components
    }
}
```

**Update:** `app/src/main/AndroidManifest.xml`
```xml
<application
    android:name=".App"
    ...>
```

**Priority:** 🔴 CRITICAL  
**Effort:** 1 hour

---

#### 2.2 Create Hilt Modules
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/di/AppModule.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.di

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideCacheHelper(@ApplicationContext context: Context): CacheHelper {
        return CacheHelper(context)
    }
    
    @Provides
    @Singleton
    fun provideCredentialsPreferences(@ApplicationContext context: Context): CredentialsPreferences {
        return CredentialsPreferences(context)
    }
    
    @Provides
    @Singleton
    fun provideSettingsPreferences(@ApplicationContext context: Context): SettingsPreferences {
        return SettingsPreferences(context)
    }
    
    @Provides
    @Singleton
    fun provideXtreamRepository(
        @ApplicationContext context: Context,
        cacheHelper: CacheHelper
    ): XtreamRepository {
        return XtreamRepository(context, cacheHelper)
    }
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 4 hours  
**Deliverable:** Complete DI setup

---

### Week 3: Unit Testing Foundation

#### 3.1 Setup Testing Infrastructure
**New File:** `app/src/test/java/com/tvonnet/debridxtreamiptv/LiveViewModelTest.kt`

```kotlin
package com.tvonnet.debridxtreamiptv

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.live.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var repository: XtreamRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: LiveViewModel
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }
    
    @Test
    fun `initial state is correct`() = runTest {
        // Given
        viewModel = LiveViewModel(repository, savedStateHandle)
        
        // When
        val state = viewModel.uiState.value
        
        // Then
        assertTrue(state.categories.isEmpty())
        assertFalse(state.isLoadingCategories)
        assertEquals(null, state.error)
    }
    
    @Test
    fun `loadCategories success updates state correctly`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Sports"),
            XtreamCategory(category_id = "2", category_name = "News")
        )
        val mockCache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(
                categories = mockCategories,
                streams = emptyList()
            ),
            vod = null,
            series = null,
            epg = null
        )
        every { repository.readCache() } returns mockCache
        
        // When
        viewModel = LiveViewModel(repository, savedStateHandle)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(2, state.categories.size)
        assertEquals("Sports", state.categories[0].category_name)
        assertFalse(state.isLoadingCategories)
        assertEquals(null, state.error)
    }
    
    @Test
    fun `loadCategories with empty cache shows error`() = runTest {
        // Given
        every { repository.readCache() } returns null
        
        // When
        viewModel = LiveViewModel(repository, savedStateHandle)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertTrue(state.categories.isEmpty())
        assertTrue(state.error?.contains("No categories found") == true)
    }
    
    @Test
    fun `selectCategory loads channels correctly`() = runTest {
        // Given
        val mockStreams = listOf(
            XtreamStream(stream_id = "1", name = "Channel 1", category_id = "1"),
            XtreamStream(stream_id = "2", name = "Channel 2", category_id = "1")
        )
        val mockCache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(
                categories = listOf(XtreamCategory(category_id = "1", category_name = "Sports")),
                streams = mockStreams
            ),
            vod = null,
            series = null,
            epg = null
        )
        every { repository.readCache() } returns mockCache
        viewModel = LiveViewModel(repository, savedStateHandle)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // When
        viewModel.onEvent(LiveEvent.SelectCategory("1"))
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(2, state.channels.size)
        assertEquals("1", state.selectedCategoryId)
        assertFalse(state.isLoadingChannels)
    }
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 8 hours  
**Deliverable:** 10+ unit tests with 50% coverage

---

### Week 4: Repository Pattern Refinement

#### 4.1 Add Result Wrapper
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/Result.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
    object Loading : Result<Nothing>()
    
    val isSuccess: Boolean
        get() = this is Success
    
    val isError: Boolean
        get() = this is Error
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }
    
    fun exceptionOrNull(): Exception? = when (this) {
        is Error -> exception
        else -> null
    }
}

suspend fun <T> resultOf(block: suspend () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e)
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 2 hours

---

## 🎯 PHASE 2: PERFORMANCE OPTIMIZATION (Weeks 5-8)

### Week 5: Pagination with Paging3

#### 5.1 Create PagingSource for Channels
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/paging/ChannelPagingSource.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

class ChannelPagingSource(
    private val repository: XtreamRepository,
    private val categoryId: String
) : PagingSource<Int, XtreamStream>() {
    
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, XtreamStream> {
        return try {
            val page = params.key ?: 0
            val pageSize = params.loadSize
            
            val allStreams = repository.readCache()?.live?.streams?.filter {
                it.category_id == categoryId ||
                it.category_ids?.contains(categoryId.toIntOrNull() ?: -1) == true
            } ?: emptyList()
            
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, allStreams.size)
            val items = if (startIndex < allStreams.size) {
                allStreams.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            LoadResult.Page(
                data = items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (endIndex < allStreams.size) page + 1 else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
    
    override fun getRefreshKey(state: PagingState<Int, XtreamStream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 6 hours  
**Deliverable:** Pagination for smooth scrolling

---

### Week 6: Room Database Integration

#### 6.1 Define Room Entities
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/local/entity/ChannelEntity.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val streamId: String,
    val name: String,
    val categoryId: String,
    val streamIcon: String?,
    val epgChannelId: String?,
    val added: Long,
    val isFavorite: Boolean = false,
    val lastWatched: Long? = null
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val type: String // "live", "vod", "series"
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val streamId: String,
    val type: String,
    val addedAt: Long
)
```

**Priority:** 🟠 HIGH  
**Effort:** 8 hours  
**Deliverable:** Complete Room database schema

---

### Week 7: Multi-Level Caching Strategy

#### 7.1 Implement Cache Manager
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/cache/CacheManager.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.cache

import androidx.collection.LruCache
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    private val channelDao: ChannelDao
) {
    // Level 1: Memory cache (fastest)
    private val memoryCache = LruCache<String, List<XtreamStream>>(10 * 1024 * 1024) // 10MB
    
    suspend fun getChannels(categoryId: String): List<XtreamStream>? {
        // Try memory cache first
        memoryCache.get(categoryId)?.let { return it }
        
        // Try Room database
        val entities = channelDao.getChannelsByCategory(categoryId)
        if (entities.isNotEmpty()) {
            val streams = entities.map { it.toXtreamStream() }
            memoryCache.put(categoryId, streams)
            return streams
        }
        
        return null
    }
    
    suspend fun putChannels(categoryId: String, channels: List<XtreamStream>) {
        // Update memory cache
        memoryCache.put(categoryId, channels)
        
        // Update Room database
        channelDao.insertChannels(channels.map { it.toChannelEntity(categoryId) })
    }
    
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 10 hours  
**Deliverable:** 3-level caching (Memory → Room → Network)

---

### Week 8: Network Optimization

#### 8.1 Add OkHttp Interceptors
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/CacheInterceptor.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.remote

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

class CacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        val cacheControl = CacheControl.Builder()
            .maxAge(1, TimeUnit.HOURS)
            .build()
        
        val modifiedRequest = request.newBuilder()
            .cacheControl(cacheControl)
            .build()
        
        return chain.proceed(modifiedRequest)
    }
}

class OfflineCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        
        // If offline, use stale cache
        if (!isNetworkAvailable()) {
            val cacheControl = CacheControl.Builder()
                .maxStale(7, TimeUnit.DAYS)
                .onlyIfCached()
                .build()
            
            request = request.newBuilder()
                .cacheControl(cacheControl)
                .build()
        }
        
        return chain.proceed(request)
    }
    
    private fun isNetworkAvailable(): Boolean {
        // Implement network check
        return true
    }
}
```

**Priority:** 🟡 MEDIUM  
**Effort:** 6 hours  
**Deliverable:** Smart network caching

---

## 🎯 PHASE 3: FEATURE COMPLETION (Weeks 9-12)

### Week 9: Search Functionality

#### 9.1 Search ViewModel
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchViewModel.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<XtreamStream> = emptyList(),
    val isSearching: Boolean = false,
    val recentSearches: List<String> = emptyList()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: XtreamRepository
) : ViewModel() {
    
    private val _searchQuery = MutableStateFlow("")
    
    @OptIn(FlowPreview::class)
    val uiState: StateFlow<SearchUiState> = _searchQuery
        .debounce(300) // Wait 300ms after user stops typing
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.length < 2) {
                flowOf(SearchUiState(query = query))
            } else {
                performSearch(query)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SearchUiState()
        )
    
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }
    
    private fun performSearch(query: String): Flow<SearchUiState> = flow {
        emit(SearchUiState(query = query, isSearching = true))
        
        val cache = repository.readCache()
        val allStreams = listOfNotNull(
            cache?.live?.streams,
            cache?.vod?.streams,
            cache?.series?.streams?.map { it.toXtreamStream() }
        ).flatten()
        
        val results = allStreams.filter { stream ->
            stream.name?.contains(query, ignoreCase = true) == true
        }
        
        emit(SearchUiState(query = query, results = results, isSearching = false))
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 8 hours  
**Deliverable:** Global search across all content

---

### Week 10: Favorites System

#### 10.1 Favorites Repository
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/FavoritesRepository.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val xtreamRepository: XtreamRepository
) {
    
    fun getFavorites(): Flow<List<XtreamStream>> {
        return favoriteDao.getAllFavorites().map { favorites ->
            val cache = xtreamRepository.readCache()
            val allStreams = cache?.live?.streams ?: emptyList()
            
            favorites.mapNotNull { favorite ->
                allStreams.find { it.stream_id == favorite.streamId }
            }
        }
    }
    
    suspend fun addFavorite(streamId: String, type: String) {
        val favorite = FavoriteEntity(
            streamId = streamId,
            type = type,
            addedAt = System.currentTimeMillis()
        )
        favoriteDao.insertFavorite(favorite)
    }
    
    suspend fun removeFavorite(streamId: String) {
        favoriteDao.deleteFavoriteByStreamId(streamId)
    }
    
    fun isFavorite(streamId: String): Flow<Boolean> {
        return favoriteDao.isFavorite(streamId)
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 6 hours  
**Deliverable:** Complete favorites management

---

### Week 11: EPG (Electronic Program Guide)

#### 11.1 EPG ViewModel
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/epg/EpgViewModel.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.model.EpgProgram
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class EpgUiState(
    val selectedDate: Long = System.currentTimeMillis(),
    val programs: Map<String, List<EpgProgram>> = emptyMap(),
    val currentProgram: EpgProgram? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: XtreamRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(EpgUiState())
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()
    
    init {
        loadEpg()
    }
    
    private fun loadEpg() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val cache = repository.readCache()
            val epgData = cache?.epg ?: emptyMap()
            
            // Filter programs for selected date
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = _uiState.value.selectedDate
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val startOfDay = calendar.timeInMillis
            
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            val endOfDay = calendar.timeInMillis
            
            val filteredPrograms = epgData.mapValues { (_, programs) ->
                programs.filter { program ->
                    val start = program.start?.toLongOrNull() ?: 0
                    start in startOfDay until endOfDay
                }
            }
            
            _uiState.update {
                it.copy(
                    programs = filteredPrograms,
                    isLoading = false
                )
            }
        }
    }
    
    fun selectDate(dateMillis: Long) {
        _uiState.update { it.copy(selectedDate = dateMillis) }
        loadEpg()
    }
    
    fun getCurrentProgram(channelId: String): EpgProgram? {
        val programs = _uiState.value.programs[channelId] ?: return null
        val now = System.currentTimeMillis()
        
        return programs.find { program ->
            val start = program.start?.toLongOrNull() ?: 0
            val end = program.stop?.toLongOrNull() ?: 0
            now in start until end
        }
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 12 hours  
**Deliverable:** Complete EPG with timeline

---

### Week 12: Parental Controls

#### 12.1 Parental Control Settings
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/ParentalControlViewModel.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ParentalControlState(
    val isEnabled: Boolean = false,
    val isPinSet: Boolean = false,
    val blockedCategories: Set<String> = emptySet()
)

@HiltViewModel
class ParentalControlViewModel @Inject constructor(
    private val preferences: SettingsPreferences
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ParentalControlState())
    val uiState: StateFlow<ParentalControlState> = _uiState.asStateFlow()
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            val isEnabled = preferences.isParentalControlEnabled()
            val isPinSet = preferences.getParentalPin() != null
            val blocked = preferences.getBlockedCategories()
            
            _uiState.update {
                it.copy(
                    isEnabled = isEnabled,
                    isPinSet = isPinSet,
                    blockedCategories = blocked
                )
            }
        }
    }
    
    fun setPin(pin: String) {
        viewModelScope.launch {
            preferences.setParentalPin(pin)
            _uiState.update { it.copy(isPinSet = true) }
        }
    }
    
    fun verifyPin(pin: String): Boolean {
        return preferences.getParentalPin() == pin
    }
    
    fun toggleParentalControl(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setParentalControlEnabled(enabled)
            _uiState.update { it.copy(isEnabled = enabled) }
        }
    }
    
    fun blockCategory(categoryId: String) {
        viewModelScope.launch {
            val current = _uiState.value.blockedCategories.toMutableSet()
            current.add(categoryId)
            preferences.setBlockedCategories(current)
            _uiState.update { it.copy(blockedCategories = current) }
        }
    }
}
```

**Priority:** 🟡 MEDIUM  
**Effort:** 8 hours  
**Deliverable:** PIN-protected content filtering

---

## 🎯 PHASE 4: PRODUCTION READINESS (Weeks 13-16)

### Week 13: Firebase Integration

#### 13.1 Add Firebase Dependencies
**Update:** `app/build.gradle`

```gradle
plugins {
    id 'com.google.gms.google-services'
    id 'com.google.firebase.crashlytics'
}

dependencies {
    // Firebase
    implementation platform('com.google.firebase:firebase-bom:32.7.0')
    implementation 'com.google.firebase:firebase-crashlytics-ktx'
    implementation 'com.google.firebase:firebase-analytics-ktx'
    implementation 'com.google.firebase:firebase-perf-ktx'
    implementation 'com.google.firebase:firebase-config-ktx'
}
```

#### 13.2 Initialize Firebase
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/analytics/AnalyticsManager.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsManager @Inject constructor() {
    
    private val analytics: FirebaseAnalytics = Firebase.analytics
    private val crashlytics = Firebase.crashlytics
    
    fun logEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
        analytics.logEvent(eventName, bundle)
    }
    
    fun logScreenView(screenName: String) {
        logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, mapOf(
            FirebaseAnalytics.Param.SCREEN_NAME to screenName
        ))
    }
    
    fun logStreamPlayed(streamId: String, streamType: String) {
        logEvent("stream_played", mapOf(
            "stream_id" to streamId,
            "stream_type" to streamType
        ))
    }
    
    fun setUserId(userId: String) {
        analytics.setUserId(userId)
        crashlytics.setUserId(userId)
    }
    
    fun recordException(exception: Throwable) {
        crashlytics.recordException(exception)
    }
    
    fun log(message: String) {
        crashlytics.log(message)
    }
}
```

**Priority:** 🟠 HIGH  
**Effort:** 6 hours  
**Deliverable:** Complete analytics & crash reporting

---

### Week 14: Security Hardening

#### 14.1 Certificate Pinning
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/SecurityInterceptor.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.remote

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

object NetworkSecurity {
    
    fun createSecureOkHttpClient(): OkHttpClient {
        val certificatePinner = CertificatePinner.Builder()
            .add("yourdomain.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()
        
        return OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .build()
    }
}
```

#### 14.2 Encrypted SharedPreferences
**New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/SecurePreferences.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveCredentials(url: String, username: String, password: String) {
        sharedPreferences.edit()
            .putString("url", url)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }
    
    // ... rest of implementation
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 8 hours  
**Deliverable:** Encrypted credentials & certificate pinning

---

### Week 15: ProGuard & Build Optimization

#### 15.1 ProGuard Rules
**New File:** `app/proguard-rules.pro`

```proguard
# DebridXtreamIPTV ProGuard Rules

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson
-keep class com.google.gson.** { *; }
-keep class com.tvonnet.debridxtreamiptv.data.model.** { *; }

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Crashlytics
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
```

#### 15.2 Update Build Config
**Update:** `app/build.gradle`

```gradle
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            
            // Enable R8 full mode
            android.enableR8.fullMode=true
        }
        
        debug {
            applicationIdSuffix ".debug"
            versionNameSuffix "-debug"
        }
    }
    
    // Split APKs by ABI
    splits {
        abi {
            enable true
            reset()
            include 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
            universalApk true
        }
    }
}
```

**Priority:** 🔴 CRITICAL  
**Effort:** 6 hours  
**Deliverable:** Optimized production build (40-60% smaller APK)

---

### Week 16: Final QA & Documentation

#### 16.1 Create User Documentation
**New File:** `docs/USER_GUIDE.md`

```markdown
# DebridXtreamIPTV User Guide

## Installation
1. Download APK from [releases](link)
2. Enable "Unknown Sources" in TV settings
3. Install APK
4. Launch app

## First-Time Setup
1. Enter your Xtream Codes credentials:
   - Server URL (e.g., http://example.com:8080)
   - Username
   - Password
2. Click "Login"
3. Wait for content to sync

## Features
- Live TV with EPG
- VOD Movies
- TV Series
- Search
- Favorites
- Parental Controls

## Troubleshooting
...
```

#### 16.2 Developer Documentation
**New File:** `docs/DEVELOPER_GUIDE.md`

```markdown
# DebridXtreamIPTV Developer Guide

## Architecture
- MVVM with Hilt DI
- Single Activity, Multiple Fragments
- Repository Pattern
- Room Database for offline support

## Building
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## Testing
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Code Structure
```
app/
├── data/           # Data layer
│   ├── local/      # Room DB
│   ├── remote/     # Retrofit API
│   ├── repository/ # Repository pattern
│   └── model/      # Data models
├── ui/             # Presentation layer
│   ├── live/       # Live TV feature
│   ├── vod/        # VOD feature
│   └── base/       # Base classes
└── di/             # Dependency Injection
```
```

**Priority:** 🟡 MEDIUM  
**Effort:** 8 hours  
**Deliverable:** Complete documentation

---

## 📊 IMPLEMENTATION CHECKLIST

### Phase 1: Architecture (✅ = Done, 🔄 = In Progress, ⏳ = Pending)

- [ ] ⏳ Add MVVM dependencies
- [ ] ⏳ Create BaseViewModel
- [ ] ⏳ Implement LiveViewModel
- [ ] ⏳ Refactor LiveFragment
- [ ] ⏳ Setup Hilt Application
- [ ] ⏳ Create Hilt modules
- [ ] ⏳ Write 10+ unit tests
- [ ] ⏳ Refine Repository pattern

### Phase 2: Performance (⏳ = All Pending)

- [ ] ⏳ Implement Paging3
- [ ] ⏳ Create Room database
- [ ] ⏳ Implement multi-level caching
- [ ] ⏳ Add network interceptors

### Phase 3: Features (⏳ = All Pending)

- [ ] ⏳ Search functionality
- [ ] ⏳ Favorites system
- [ ] ⏳ EPG implementation
- [ ] ⏳ Parental controls

### Phase 4: Production (⏳ = All Pending)

- [ ] ⏳ Firebase integration
- [ ] ⏳ Security hardening
- [ ] ⏳ ProGuard optimization
- [ ] ⏳ Final QA & documentation

---

## 💰 RESOURCE REQUIREMENTS

### Development Team
- **1 Senior Android Developer** (Full-time, 16 weeks)
- **1 QA Engineer** (Part-time, weeks 12-16)
- **1 DevOps Engineer** (Part-time, weeks 15-16)

### Tools & Services
- Android Studio Arctic Fox or later
- Firebase account (Blaze plan for analytics)
- Physical Android TV device for testing
- CI/CD pipeline (GitHub Actions)

### Budget Estimate
| Item | Cost |
|------|------|
| Senior Developer (16 weeks @ $50/hr, 40hr/week) | $32,000 |
| QA Engineer (4 weeks @ $35/hr, 20hr/week) | $2,800 |
| DevOps (2 weeks @ $60/hr, 10hr/week) | $1,200 |
| Firebase (monthly) | $100 |
| Play Store publishing | $25 |
| **Total** | **~$36,125** |

---

## 🎯 SUCCESS METRICS

### Code Quality
- [ ] 80%+ unit test coverage
- [ ] 0 critical bugs in production
- [ ] <2% crash rate

### Performance
- [ ] App startup < 2 seconds
- [ ] Channel list load < 1 second
- [ ] Memory usage < 200MB
- [ ] APK size < 30MB

### User Experience
- [ ] 4.5+ star rating on Play Store
- [ ] <5% uninstall rate
- [ ] 60%+ day-1 retention

---

## 📞 SUPPORT

For questions or issues during implementation:
- Create GitHub issues
- Email: dev@debridxtream.tv
- Slack: #android-dev

---

**Last Updated:** November 2024  
**Version:** 1.0  
**Status:** Ready for Implementation


