# DESIGN OUTPUT - DebridXtreamIPTV

This document contains design specifications from all specialized agents.

---

## ### TV_UI_DESIGNER

**Goal**: Android TV (Leanback) friendly UI layouts with proper D-pad navigation

### Files to Create:
- `res/layout/activity_main.xml` - Root activity layout with left menu + right content area
- `res/layout/fragment_home_shell.xml` - Shell layout with menu and fragment container
- `res/layout/fragment_live.xml` - Live TV categories and channels grid
- `res/layout/fragment_vod.xml` - VOD categories and movies grid
- `res/layout/fragment_series.xml` - Series categories and shows grid
- `res/layout/fragment_settings.xml` - Settings screen
- `res/layout/fragment_login.xml` - Login screen with server/username/password fields
- `res/layout/item_channel_card.xml` - Channel card item (16:9 aspect)
- `res/layout/item_movie_card.xml` - Movie/VOD card item (3:4 poster)
- `res/layout/item_series_card.xml` - Series card item (3:4 poster)
- `ui/MainActivity.kt` - Single activity hosting all fragments
- `ui/HomeShellFragment.kt` - Container fragment with left menu + right content
- `ui/LoginFragment.kt` - Login screen fragment
- `ui/live/LiveFragment.kt` - Live TV fragment
- `ui/vod/VodFragment.kt` - VOD fragment
- `ui/series/SeriesFragment.kt` - Series fragment

### Layout Structure:

**activity_main.xml** (Root):
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    
    <fragment
        android:id="@+id/nav_host_fragment"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</FrameLayout>
```

**fragment_home_shell.xml** (Shell with left menu):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal"
    android:paddingStart="48dp"
    android:paddingEnd="48dp"
    android:paddingTop="32dp"
    android:paddingBottom="32dp">
    
    <!-- Left Vertical Menu -->
    <LinearLayout
        android:id="@+id/left_menu"
        android:layout_width="280dp"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:background="?attr/colorSurface">
        
        <TextView
            android:id="@+id/menu_live"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="24dp"
            android:text="Live TV"
            android:textSize="18sp"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:nextFocusDown="@id/menu_vod"
            android:background="?attr/selectableItemBackground"
            android:textColor="?attr/colorOnSurface" />
        
        <TextView
            android:id="@+id/menu_vod"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="24dp"
            android:text="Movies"
            android:textSize="18sp"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:nextFocusUp="@id/menu_live"
            android:nextFocusDown="@id/menu_series"
            android:background="?attr/selectableItemBackground"
            android:textColor="?attr/colorOnSurface" />
        
        <TextView
            android:id="@+id/menu_series"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="24dp"
            android:text="Series"
            android:textSize="18sp"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:nextFocusUp="@id/menu_vod"
            android:nextFocusDown="@id/menu_settings"
            android:background="?attr/selectableItemBackground"
            android:textColor="?attr/colorOnSurface" />
        
        <TextView
            android:id="@+id/menu_settings"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="24dp"
            android:text="Settings"
            android:textSize="18sp"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:nextFocusUp="@id/menu_series"
            android:background="?attr/selectableItemBackground"
            android:textColor="?attr/colorOnSurface" />
    </LinearLayout>
    
    <!-- Right Content Area -->
    <FrameLayout
        android:id="@+id/content_container"
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1"
        android:layout_marginStart="48dp" />
</LinearLayout>
```

**fragment_login.xml** (TV-friendly login):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="64dp">
    
    <EditText
        android:id="@+id/et_server_url"
        android:layout_width="600dp"
        android:layout_height="wrap_content"
        android:hint="Server URL"
        android:inputType="textUri"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusDown="@id/et_username"
        android:textSize="18sp"
        android:padding="20dp" />
    
    <EditText
        android:id="@+id/et_username"
        android:layout_width="600dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:hint="Username"
        android:inputType="text"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusUp="@id/et_server_url"
        android:nextFocusDown="@id/et_password"
        android:textSize="18sp"
        android:padding="20dp" />
    
    <EditText
        android:id="@+id/et_password"
        android:layout_width="600dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:hint="Password"
        android:inputType="textPassword"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusUp="@id/et_username"
        android:nextFocusDown="@id/btn_login"
        android:textSize="18sp"
        android:padding="20dp" />
    
    <Button
        android:id="@+id/btn_login"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="48dp"
        android:text="Login"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusUp="@id/et_password"
        android:textSize="18sp"
        android:paddingStart="48dp"
        android:paddingEnd="48dp"
        android:paddingTop="16dp"
        android:paddingBottom="16dp" />
</LinearLayout>
```

**fragment_live.xml** (Live channels grid):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="32dp">
    
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_live_categories"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusLeft="@id/left_menu" />
    
    <TextView
        android:id="@+id/tv_category_title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:textSize="20sp"
        android:textStyle="bold" />
    
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_channels"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_marginTop="16dp"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusLeft="@id/left_menu" />
</LinearLayout>
```

**item_channel_card.xml** (16:9 channel card):
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="300dp"
    android:layout_height="169dp"
    android:layout_margin="8dp"
    android:focusable="true"
    android:focusableInTouchMode="true"
    android:background="?attr/selectableItemBackground">
    
    <ImageView
        android:id="@+id/iv_channel_logo"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop" />
    
    <TextView
        android:id="@+id/tv_channel_name"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#80000000"
        android:padding="8dp"
        android:textColor="@android:color/white"
        android:textSize="14sp"
        android:maxLines="1"
        android:ellipsize="end" />
</FrameLayout>
```

**item_movie_card.xml** (3:4 poster card):
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="200dp"
    android:layout_height="300dp"
    android:layout_margin="8dp"
    android:focusable="true"
    android:focusableInTouchMode="true"
    android:background="?attr/selectableItemBackground">
    
    <ImageView
        android:id="@+id/iv_poster"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop"
        android:contentDescription="@string/poster" />
    
    <TextView
        android:id="@+id/tv_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#80000000"
        android:padding="8dp"
        android:textColor="@android:color/white"
        android:textSize="14sp"
        android:maxLines="2"
        android:ellipsize="end" />
</FrameLayout>
```

### Focus Map Rules:
- Default focus on `left_menu` (first item: Live TV) when HomeShellFragment starts
- All content RecyclerViews must have `android:nextFocusLeft="@id/left_menu"` to return to menu
- Menu items navigate vertically with explicit `nextFocusUp/Down`
- Login fields navigate with `nextFocusUp/Down` in sequence

### Image Loading Strategy:
- Use **Glide** for all images (channel logos, movie posters, series posters)
- Placeholder: `R.drawable.tv_card_placeholder` (TV-safe default image)
- Error fallback: same placeholder
- Never crash if URL is null/empty - show placeholder instead

### Kotlin Implementation Signatures:

```kotlin
// ui/MainActivity.kt
class MainActivity : AppCompatActivity() {
    private fun setupNavigation()
    private fun navigateToLogin()
    private fun navigateToHomeShell()
}

// ui/HomeShellFragment.kt
class HomeShellFragment : Fragment() {
    private fun setupMenu()
    private fun selectMenuItem(position: Int)
    private fun showFragment(fragment: Fragment)
    override fun onStart() { /* set default focus to left_menu */ }
}

// ui/LoginFragment.kt
class LoginFragment : Fragment() {
    private fun onLoginClick()
    private fun validateInputs(): Boolean
    private fun saveCredentials(server: String, user: String, pass: String)
}

// ui/live/LiveFragment.kt
class LiveFragment : Fragment() {
    private fun loadCategories()
    private fun loadChannelsForCategory(categoryId: String)
    private fun onChannelClick(channel: LiveChannel)
}

// Adapters
class ChannelAdapter : RecyclerView.Adapter<ChannelViewHolder>() {
    // Use Glide to load channel logos
    // Handle null/empty URLs gracefully
}

class MovieAdapter : RecyclerView.Adapter<MovieViewHolder>() {
    // Use Glide to load posters
    // Handle null/empty URLs gracefully
}
```

### Dependencies:
- AndroidX Leanback (optional, but compatible)
- Glide: `implementation 'com.github.bumptech.glide:glide:4.16.0'`
- RecyclerView: `androidx.recyclerview:recyclerview:1.3.2`

---

## ### IPTV_BACKEND

**Goal**: Xtream Codes API integration with defensive error handling and caching

### Files to Create:
- `data/remote/XtreamApiService.kt` - Retrofit interface
- `data/remote/XtreamRetrofitClient.kt` - Retrofit client builder
- `data/repository/XtreamRepository.kt` - Repository with defensive error handling
- `data/model/XtreamModels.kt` - Data classes for API responses
- `data/cache/CacheHelper.kt` - JSON cache read/write utility
- `data/model/CacheModel.kt` - Cache file structure

### Retrofit Interface Design:

**data/remote/XtreamApiService.kt**:
```kotlin
interface XtreamApiService {
    @GET("player_api.php")
    suspend fun login(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<XtreamLoginResponse>
    
    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<XtreamStream>>
    
    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<XtreamVodInfo>>
    
    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null
    ): Response<List<XtreamSeriesInfo>>
    
    @GET("xmltv.php")
    suspend fun getEpg(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<String> // XML string
}
```

**data/remote/XtreamRetrofitClient.kt**:
```kotlin
object XtreamRetrofitClient {
    fun create(baseUrl: String): XtreamApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit.create(XtreamApiService::class.java)
    }
}
```

### Data Models:

**data/model/XtreamModels.kt**:
```kotlin
data class XtreamLoginResponse(
    val user_info: XtreamUserInfo?,
    val server_info: XtreamServerInfo?
)

data class XtreamUserInfo(
    val username: String?,
    val password: String?,
    val message: String?,
    val auth: Int?,
    val status: String?,
    val exp_date: String?,
    val is_trial: String?,
    val active_cons: String?,
    val created_at: String?,
    val max_connections: String?,
    val allowed_output_formats: List<String>?
)

data class XtreamServerInfo(
    val url: String?,
    val port: String?,
    val https_port: String?,
    val server_protocol: String?,
    val rtmp_port: String?,
    val timezone: String?,
    val timestamp_now: Long?
)

data class XtreamCategory(
    val category_id: String?,
    val category_name: String?,
    val parent_id: String?
)

data class XtreamStream(
    val num: Int?,
    val name: String?,
    val stream_type: String?,
    val stream_id: String?,
    val stream_icon: String?, // nullable
    val epg_channel_id: String?,
    val added: String?,
    val category_id: String?,
    val category_ids: List<Int>?,
    val container_extension: String?,
    val custom_sid: String?,
    val direct_source: String?,
    val tv_archive: Int?,
    val tv_archive_duration: Int?
)

data class XtreamVodInfo(
    val num: Int?,
    val name: String?,
    val stream_type: String?,
    val stream_id: String?,
    val stream_icon: String?, // nullable
    val rating: String?,
    val rating_5based: Double?,
    val added: String?,
    val category_id: String?,
    val category_ids: List<Int>?,
    val container_extension: String?,
    val custom_sid: String?,
    val direct_source: String?,
    val releaseDate: String?,
    val duration: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val youtube_trailer: String?,
    val cover: String?, // nullable
    val rating_imdb: String?
)

data class XtreamSeriesInfo(
    val num: Int?,
    val name: String?,
    val series_id: String?,
    val cover: String?, // nullable
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val rating_5based: Double?,
    val episodes: Map<String, XtreamEpisodeInfo>?
)

data class XtreamEpisodeInfo(
    val id: String?,
    val title: String?,
    val container_extension: String?,
    val info: XtreamEpisodeDetail?,
    val stream_type: String?
)

data class XtreamEpisodeDetail(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val duration_secs: String?,
    val cover: String?
)

data class EpgProgram(
    val channelId: String,
    val start: Long, // timestamp
    val stop: Long, // timestamp
    val title: String?,
    val desc: String?,
    val category: String?
)
```

**data/model/CacheModel.kt**:
```kotlin
data class IptvCache(
    val timestamp: Long,
    val live: LiveCacheData?,
    val vod: VodCacheData?,
    val series: SeriesCacheData?,
    val epg: Map<String, List<EpgProgram>>?
)

data class LiveCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamStream>
)

data class VodCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamVodInfo>
)

data class SeriesCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamSeriesInfo>
)
```

### Repository Design (DEFENSIVE):

**data/repository/XtreamRepository.kt**:
```kotlin
class XtreamRepository(private val context: Context) {
    private var apiService: XtreamApiService? = null
    private val cacheHelper = CacheHelper(context)
    
    fun initialize(baseUrl: String, username: String, password: String) {
        try {
            val normalizedUrl = baseUrl.trimEnd('/') + "/"
            apiService = XtreamRetrofitClient.create(normalizedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize API service", e)
            apiService = null
        }
    }
    
    suspend fun login(username: String, password: String): Result<XtreamLoginResponse> {
        return try {
            if (apiService == null) {
                return Result.failure(Exception("API service not initialized"))
            }
            val response = apiService!!.login(username, password)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }
    
    suspend fun fetchAllAndCache(): Result<IptvCache> {
        return try {
            if (apiService == null) {
                // Try to read from cache if API not available
                val cached = cacheHelper.readCache()
                return if (cached != null) {
                    Result.success(cached)
                } else {
                    Result.failure(Exception("API service not initialized and no cache available"))
                }
            }
            
            val live = fetchLiveCategoriesAndStreams()
            val vod = fetchVodCategoriesAndStreams()
            val series = fetchSeriesCategoriesAndStreams()
            val epg = fetchEpg()
            
            val cache = IptvCache(
                timestamp = System.currentTimeMillis(),
                live = live.getOrNull(),
                vod = vod.getOrNull(),
                series = series.getOrNull(),
                epg = epg.getOrNull()
            )
            
            cacheHelper.writeCache(cache)
            Result.success(cache)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch all data", e)
            // Fallback to cache
            val cached = cacheHelper.readCache()
            if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }
    
    private suspend fun fetchLiveCategoriesAndStreams(): Result<LiveCacheData> {
        return try {
            val categoriesResponse = apiService?.getLiveCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            val streamsResponse = apiService?.getLiveStreams(username, password)
            val streams = if (streamsResponse?.isSuccessful == true) {
                streamsResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Result.success(LiveCacheData(categories, streams))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch live data", e)
            Result.failure(e)
        }
    }
    
    private suspend fun fetchVodCategoriesAndStreams(): Result<VodCacheData> {
        return try {
            val categoriesResponse = apiService?.getVodCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            val streamsResponse = apiService?.getVodStreams(username, password)
            val streams = if (streamsResponse?.isSuccessful == true) {
                streamsResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Result.success(VodCacheData(categories, streams))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VOD data", e)
            Result.success(VodCacheData(emptyList(), emptyList())) // Return empty, don't fail
        }
    }
    
    private suspend fun fetchSeriesCategoriesAndStreams(): Result<SeriesCacheData> {
        return try {
            val categoriesResponse = apiService?.getSeriesCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            val streamsResponse = apiService?.getSeries(username, password)
            val streams = if (streamsResponse?.isSuccessful == true) {
                streamsResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Result.success(SeriesCacheData(categories, streams))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch series data", e)
            Result.success(SeriesCacheData(emptyList(), emptyList())) // Return empty, don't fail
        }
    }
    
    private suspend fun fetchEpg(): Result<Map<String, List<EpgProgram>>> {
        return try {
            val epgResponse = apiService?.getEpg(username, password)
            if (epgResponse?.isSuccessful == true && epgResponse.body() != null) {
                val xmlContent = epgResponse.body()!!
                val parsedEpg = EpgParser.parse(xmlContent)
                Result.success(parsedEpg)
            } else {
                Result.failure(Exception("EPG fetch failed but continuing..."))
            }
        } catch (e: Exception) {
            Log.w(TAG, "EPG fetch failed, continuing without EPG", e)
            Result.failure(e) // EPG failure is non-critical
        }
    }
    
    fun readCache(): IptvCache? {
        return cacheHelper.readCache()
    }
    
    suspend fun forceRefresh(): Result<IptvCache> {
        return fetchAllAndCache()
    }
    
    companion object {
        private const val TAG = "XtreamRepository"
        private var username: String = ""
        private var password: String = ""
    }
}
```

### Cache Helper:

**data/cache/CacheHelper.kt**:
```kotlin
class CacheHelper(private val context: Context) {
    private val cacheFileName = "iptv_cache.json"
    
    fun writeCache(cache: IptvCache) {
        try {
            val gson = Gson()
            val json = gson.toJson(cache)
            val file = File(context.filesDir, cacheFileName)
            file.writeText(json)
            Log.d(TAG, "Cache written successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cache", e)
        }
    }
    
    fun readCache(): IptvCache? {
        return try {
            val file = File(context.filesDir, cacheFileName)
            if (!file.exists()) {
                return null
            }
            val json = file.readText()
            val gson = Gson()
            gson.fromJson(json, IptvCache::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache", e)
            null
        }
    }
    
    fun clearCache() {
        try {
            val file = File(context.filesDir, cacheFileName)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }
    
    companion object {
        private const val TAG = "CacheHelper"
    }
}
```

### EPG Parser:

**data/epg/EpgParser.kt** (Simple XML parser):
```kotlin
object EpgParser {
    fun parse(xmlContent: String): Map<String, List<EpgProgram>> {
        val epgMap = mutableMapOf<String, MutableList<EpgProgram>>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))
            
            var eventType = parser.eventType
            var currentChannelId: String? = null
            var program: EpgProgram? = null
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")
                            }
                            "programme" -> {
                                program = EpgProgram(
                                    channelId = parser.getAttributeValue(null, "channel") ?: "",
                                    start = parseTimestamp(parser.getAttributeValue(null, "start")),
                                    stop = parseTimestamp(parser.getAttributeValue(null, "stop")),
                                    title = null,
                                    desc = null,
                                    category = null
                                )
                            }
                            "title" -> {
                                program?.title = parser.nextText()
                            }
                            "desc" -> {
                                program?.desc = parser.nextText()
                            }
                            "category" -> {
                                program?.category = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && program != null && currentChannelId != null) {
                            val list = epgMap.getOrPut(currentChannelId!!) { mutableListOf() }
                            list.add(program!!)
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG parsing failed", e)
        }
        
        return epgMap
    }
    
    private fun parseTimestamp(timestamp: String?): Long {
        // Parse XMLTV format: YYYYMMDDHHmmss +0000
        // Return as milliseconds since epoch
        return try {
            // Simple implementation - adjust based on actual XMLTV format
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    companion object {
        private const val TAG = "EpgParser"
    }
}
```

### Dependencies:
- Retrofit: `com.squareup.retrofit2:retrofit:2.9.0`
- Gson: `com.squareup.retrofit2:converter-gson:2.9.0`
- OkHttp Logging: `com.squareup.okhttp3:logging-interceptor:4.12.0`
- Gson: `com.google.code.gson:gson:2.10.1`

### Error Handling Strategy:
- ALL API calls wrapped in try/catch
- Missing endpoints return empty lists, never crash
- EPG failure is logged but non-critical (graceful degrade)
- Repository always falls back to cache on network failure
- Null/empty image URLs handled at UI layer (Glide placeholder)

---

## ### PLAYER_SPECIALIST

**Goal**: Standalone ExoPlayer activity with proper lifecycle management

### Files to Create:
- `player/PlayerActivity.kt` - Standalone player activity
- `res/layout/activity_player.xml` - PlayerView layout
- `player/PlayerViewModel.kt` (optional) - For state management

### PlayerActivity Design:

**player/PlayerActivity.kt**:
```kotlin
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    
    companion object {
        const val EXTRA_STREAM_URL = "STREAM_URL"
        const val EXTRA_STREAM_TITLE = "STREAM_TITLE"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        
        playerView = findViewById(R.id.player_view)
        
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE)
        
        if (streamUrl.isNullOrBlank()) {
            finish()
            return
        }
        
        initializePlayer(streamUrl)
        
        // Set title if available
        supportActionBar?.title = streamTitle ?: "Playing"
    }
    
    private fun initializePlayer(streamUrl: String) {
        player = ExoPlayer.Builder(this)
            .build()
            .also {
                playerView?.player = it
            }
        
        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }
    
    override fun onResume() {
        super.onResume()
        if (player == null) {
            val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
            if (!streamUrl.isNullOrBlank()) {
                initializePlayer(streamUrl)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        player?.pause()
    }
    
    override fun onStop() {
        super.onStop()
        releasePlayer()
    }
    
    override fun onBackPressed() {
        releasePlayer()
        super.onBackPressed()
    }
    
    private fun releasePlayer() {
        player?.stop()
        player?.release()
        player = null
        playerView?.player = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
```

### Layout:

**res/layout/activity_player.xml**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">
    
    <com.google.android.exoplayer2.ui.PlayerView
        android:id="@+id/player_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:use_controller="true"
        app:show_buffering="when_playing"
        app:controller_layout_id="@layout/custom_player_control_view" />
</FrameLayout>
```

### Intent Launch Example:

```kotlin
// From LiveFragment or VodFragment
fun launchPlayer(streamUrl: String, title: String) {
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
        putExtra(PlayerActivity.EXTRA_STREAM_TITLE, title)
    }
    startActivity(intent)
}
```

### Stream URL Construction:

```kotlin
// In repository or fragment
fun buildStreamUrl(baseUrl: String, username: String, password: String, streamId: String): String {
    return "$baseUrl/player_api.php?username=$username&password=$password&action=download_video&id=$streamId"
}

// For live streams
fun buildLiveStreamUrl(baseUrl: String, username: String, password: String, streamId: String): String {
    return "$baseUrl/live/$username/$password/$streamId.m3u8"
    // Or alternative format based on Xtream API
}
```

### Lifecycle Rules:
- `onCreate`: Initialize ExoPlayer and load media
- `onResume`: Restore player if needed
- `onPause`: Pause playback
- `onStop`: Release player resources
- `onBackPressed`: Release player and finish activity
- `onDestroy`: Final cleanup (release player)

### Dependencies:
- ExoPlayer Core: `com.google.android.exoplayer:exoplayer-core:2.19.1`
- ExoPlayer UI: `com.google.android.exoplayer:exoplayer-ui:2.19.1`

### Notes:
- Player MUST be released in `onStop()` and `onBackPressed()` to prevent resource leaks
- PlayerView reference set to null after release
- Handle null/empty stream URLs gracefully (finish activity)

---

## ### FILTER_SETTINGS_AGENT

**Goal**: Settings fragment with manual refresh and interval selection

### Files to Create:
- `ui/settings/SettingsFragment.kt` - Settings fragment
- `res/layout/fragment_settings.xml` - Settings layout
- `data/prefs/SettingsPreferences.kt` - SharedPreferences helper for refresh interval

### SettingsFragment Design:

**ui/settings/SettingsFragment.kt**:
```kotlin
class SettingsFragment : Fragment() {
    private lateinit var repository: XtreamRepository
    private lateinit var preferences: SettingsPreferences
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        repository = XtreamRepository(requireContext())
        preferences = SettingsPreferences(requireContext())
        
        setupRefreshButton()
        setupIntervalSelector()
        loadCurrentInterval()
    }
    
    private fun setupRefreshButton() {
        view.findViewById<Button>(R.id.btn_refresh_now).apply {
            focusable = true
            focusableInTouchMode = true
            setOnClickListener {
                refreshNow()
            }
        }
    }
    
    private fun setupIntervalSelector() {
        val intervalOptions = arrayOf("12 hours", "24 hours", "48 hours")
        val intervalValues = arrayOf(12, 24, 48)
        
        view.findViewById<RecyclerView>(R.id.rv_interval_options).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = IntervalAdapter(intervalOptions, intervalValues) { hours ->
                preferences.saveRefreshInterval(hours)
                loadCurrentInterval()
            }
            focusable = true
            focusableInTouchMode = true
            nextFocusLeft = R.id.left_menu
        }
    }
    
    private fun loadCurrentInterval() {
        val currentHours = preferences.getRefreshInterval()
        view.findViewById<TextView>(R.id.tv_current_interval).text = 
            "Current refresh interval: $currentHours hours"
    }
    
    private fun refreshNow() {
        view.findViewById<Button>(R.id.btn_refresh_now).isEnabled = false
        view.findViewById<ProgressBar>(R.id.progress_refresh).visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.forceRefresh()
                result.onSuccess {
                    Toast.makeText(
                        context,
                        "IPTV data refreshed successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        "Refresh failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Refresh error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                view.findViewById<Button>(R.id.btn_refresh_now).isEnabled = true
                view.findViewById<ProgressBar>(R.id.progress_refresh).visibility = View.GONE
            }
        }
    }
}
```

### Layout:

**res/layout/fragment_settings.xml**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="32dp"
    android:focusable="true"
    android:focusableInTouchMode="true"
    android:nextFocusLeft="@id/left_menu">
    
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Settings"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginBottom="32dp" />
    
    <!-- Manual Refresh Section -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Refresh IPTV Data"
        android:textSize="18sp"
        android:textStyle="bold"
        android:layout_marginBottom="16dp" />
    
    <Button
        android:id="@+id/btn_refresh_now"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Refresh IPTV data now"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusDown="@id/rv_interval_options"
        android:paddingStart="32dp"
        android:paddingEnd="32dp"
        android:paddingTop="16dp"
        android:paddingBottom="16dp"
        android:textSize="16sp" />
    
    <ProgressBar
        android:id="@+id/progress_refresh"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:visibility="gone" />
    
    <!-- Interval Selection Section -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Update Interval"
        android:textSize="18sp"
        android:textStyle="bold"
        android:layout_marginTop="48dp"
        android:layout_marginBottom="16dp" />
    
    <TextView
        android:id="@+id/tv_current_interval"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="16sp"
        android:layout_marginBottom="16dp" />
    
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv_interval_options"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:nextFocusUp="@id/btn_refresh_now"
        android:nextFocusLeft="@id/left_menu" />
</LinearLayout>
```

**res/layout/item_interval_option.xml**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="8dp"
    android:focusable="true"
    android:focusableInTouchMode="true"
    android:background="?attr/selectableItemBackground"
    android:padding="24dp">
    
    <TextView
        android:id="@+id/tv_interval_label"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="18sp"
        android:textColor="?attr/colorOnSurface" />
</FrameLayout>
```

### Interval Adapter:

```kotlin
class IntervalAdapter(
    private val options: Array<String>,
    private val values: Array<Int>,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<IntervalViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntervalViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_interval_option, parent, false)
        return IntervalViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: IntervalViewHolder, position: Int) {
        holder.bind(options[position], values[position], onSelect)
    }
    
    override fun getItemCount() = options.size
}

class IntervalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun bind(label: String, hours: Int, onSelect: (Int) -> Unit) {
        itemView.findViewById<TextView>(R.id.tv_interval_label).text = label
        itemView.setOnClickListener {
            onSelect(hours)
        }
    }
}
```

### Preferences Helper:

**data/prefs/SettingsPreferences.kt**:
```kotlin
class SettingsPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun saveRefreshInterval(hours: Int) {
        prefs.edit().putInt(KEY_REFRESH_INTERVAL, hours).apply()
    }
    
    fun getRefreshInterval(): Int {
        return prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
    }
    
    fun shouldRefresh(lastRefreshTimestamp: Long): Boolean {
        val intervalHours = getRefreshInterval()
        val intervalMillis = intervalHours * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastRefreshTimestamp) >= intervalMillis
    }
    
    companion object {
        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval_hours"
        private const val DEFAULT_REFRESH_INTERVAL = 24
    }
}
```

### Refresh Flow Integration:

```kotlin
// In HomeShellFragment or MainActivity
private fun checkAndRefreshIfNeeded() {
    val preferences = SettingsPreferences(context)
    val cacheHelper = CacheHelper(context)
    val cached = cacheHelper.readCache()
    
    if (cached == null || preferences.shouldRefresh(cached.timestamp)) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.forceRefresh()
        }
    }
}
```

### Dependencies:
- Lifecycle KTX: `androidx.lifecycle:lifecycle-runtime-ktx:2.6.2`
- Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`

### Focus Map:
- Refresh button has `nextFocusDown` → interval RecyclerView
- Interval RecyclerView has `nextFocusUp` → refresh button
- All focusable items have `nextFocusLeft` → `@id/left_menu`

---

## Design Phase Complete

### Checklist:
- [tv_ui_ready] ✅ TV UI Designer - Layouts, focus maps, navigation flows, Glide integration
- [backend_ready] ✅ IPTV Backend - Retrofit interfaces, defensive repository, cache format, EPG parsing
- [player_ready] ✅ Player Specialist - PlayerActivity with proper lifecycle, ExoPlayer config
- [settings_ready] ✅ Filter Settings Agent - SettingsFragment with refresh button and interval selector

All design specifications have been collected and documented in this file. Ready for implementation phase.

