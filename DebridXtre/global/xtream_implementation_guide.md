# Xtream Implementation Guide - Based on Probe Results

**Generated:** 2025-11-01  
**Based On:** Probe test of http://line.spainott.net

---

## ⚠️ Required Updates to Existing Code

### 1. Stream URL Format (HIGH PRIORITY)

**Issue:** Current code builds stream URLs without file extensions.

**Current Code (INCORRECT):**
```kotlin
// In HomeScreenModels.kt line 92
val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}"

// In LiveFragment.kt line 142
val streamUrl = "$serverUrl/live/$username/$password/$streamId"
```

**Required Fix:**
```kotlin
// Add .ts extension for live streams
val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}.ts"
```

**Files to Update:**
1. ✏️ `app/src/main/java/com/tvonnet/debridxtreamiptv/data/model/HomeScreenModels.kt` - Line 92, 130
2. ✏️ `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt` - Line 142

---

### 2. Add Required HTTP Headers (MEDIUM PRIORITY)

**Issue:** Retrofit client is missing headers that prevent 403 errors.

**Current Code:**
```kotlin
// XtreamRetrofitClient.kt - Missing headers
val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
```

**Required Update:**
```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("User-Agent", "VLC/3.0.16 LibVLC/3.0.16")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .method(original.method, original.body)
            .build()
        chain.proceed(request)
    }
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
```

**Files to Update:**
1. ✏️ `app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/XtreamRetrofitClient.kt`

---

### 3. VOD URL Format (ALREADY CORRECT ✓)

**Good News:** VOD URLs already include extensions:
```kotlin
// HomeScreenModels.kt line 105
val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
```

This format is correct and matches the Xtream Codes standard.

---

## 🔧 Recommended Enhancements

### 4. Create Stream URL Builder Utility

**Recommendation:** Centralize URL building to avoid inconsistencies.

**Create New File:** `app/src/main/java/com/tvonnet/debridxtreamiptv/data/util/StreamUrlBuilder.kt`

```kotlin
package com.tvonnet.debridxtreamiptv.data.util

object StreamUrlBuilder {
    
    /**
     * Builds a live stream URL in the format:
     * http://server.com/live/username/password/streamId.ts
     */
    fun buildLiveStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        extension: String = "ts"  // Default to .ts based on probe results
    ): String {
        val cleanServerUrl = serverUrl.trimEnd('/')
        return "$cleanServerUrl/live/$username/$password/$streamId.$extension"
    }
    
    /**
     * Builds a VOD stream URL in the format:
     * http://server.com/movie/username/password/streamId.ext
     */
    fun buildVodStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        containerExtension: String = "mp4"
    ): String {
        val cleanServerUrl = serverUrl.trimEnd('/')
        return "$cleanServerUrl/movie/$username/$password/$streamId.$containerExtension"
    }
    
    /**
     * Builds a series stream URL in the format:
     * http://server.com/series/username/password/streamId.ext
     */
    fun buildSeriesStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        containerExtension: String = "mp4"
    ): String {
        val cleanServerUrl = serverUrl.trimEnd('/')
        return "$cleanServerUrl/series/$username/$password/$streamId.$containerExtension"
    }
    
    /**
     * Builds alternative stream URL with different extension (fallback strategy)
     */
    fun buildAlternativeLiveStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String
    ): String {
        // Try .m3u8 as fallback if .ts fails
        return buildLiveStreamUrl(serverUrl, username, password, streamId, "m3u8")
    }
}
```

**Then update all URL building to use this utility:**

```kotlin
// In HomeScreenModels.kt
val streamUrl = StreamUrlBuilder.buildLiveStreamUrl(serverUrl, username, password, stream_id ?: "")

// In LiveFragment.kt
val streamUrl = StreamUrlBuilder.buildLiveStreamUrl(serverUrl, username, password, streamId)
```

---

### 5. Error Handling for 520 Errors

**Issue:** Some streams return 520 (server error/offline).

**Recommended Implementation in PlayerActivity:**

```kotlin
private fun initializePlayer(streamUrl: String) {
    Log.d(TAG, "Initializing player with URL: $streamUrl")
    
    player = ExoPlayer.Builder(this)
        .build()
        .also { exoPlayer ->
            binding.playerView.player = exoPlayer
            
            val mediaItem = MediaItem.fromUri(streamUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            
            // Add listener for playback errors
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback error: ${error.message}")
                    
                    // If stream fails, try alternative format
                    if (streamUrl.endsWith(".ts")) {
                        val alternativeUrl = streamUrl.replace(".ts", ".m3u8")
                        Log.d(TAG, "Retrying with alternative format: $alternativeUrl")
                        retryWithAlternativeUrl(alternativeUrl)
                    } else {
                        showErrorToast("Stream unavailable. This channel may be offline.")
                    }
                }
            })
        }
}

private fun retryWithAlternativeUrl(alternativeUrl: String) {
    player?.release()
    player = ExoPlayer.Builder(this)
        .build()
        .also { exoPlayer ->
            binding.playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(alternativeUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
}
```

---

### 6. Add Server Info to XtreamLoginResponse

**Enhancement:** Store server protocol preferences from login response.

**Update XtreamRepository.kt:**

```kotlin
private var baseUrl: String = ""
private var preferredProtocol: String = "http"
private var preferredPort: String = "80"

suspend fun login(username: String, password: String): Result<XtreamLoginResponse> {
    return try {
        if (apiService == null) {
            return Result.failure(Exception("API service not initialized"))
        }
        val response = apiService!!.login(username, password)
        if (response.isSuccessful && response.body() != null) {
            val loginResponse = response.body()!!
            
            // Store server preferences
            loginResponse.server_info?.let { serverInfo ->
                preferredProtocol = serverInfo.server_protocol ?: "http"
                preferredPort = serverInfo.port ?: "80"
                Log.d(TAG, "Server prefers: $preferredProtocol on port $preferredPort")
            }
            
            Result.success(loginResponse)
        } else {
            Result.failure(Exception("Login failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Login error", e)
        Result.failure(e)
    }
}
```

---

## 📊 Probe Results Summary (Reference)

### Working Endpoints ✓
- ✅ Authentication: `player_api.php?username={u}&password={p}`
- ✅ Live Categories: `action=get_live_categories` (365 categories)
- ✅ VOD Categories: `action=get_vod_categories` (313 categories)  
- ✅ Series Categories: `action=get_series_categories` (192 categories)

### Stream Format ✓
- ✅ **Preferred:** `.ts` (Transport Stream)
- ⚠️ **Fallback:** `.m3u8` (HLS) - May work for some streams
- 📊 **Tested:** 10 streams, found working TS format

### Server Configuration ✓
- **Protocol:** HTTP (Port 80)
- **HTTPS Available:** Yes (Port 443)
- **Status:** Active
- **Total Streams:** 18,248+

---

## 🚀 Implementation Checklist

### Phase 1: Critical Fixes (Do First)
- [ ] Add `.ts` extension to live stream URLs (HomeScreenModels.kt line 92, 130)
- [ ] Add `.ts` extension to live stream URLs (LiveFragment.kt line 142)
- [ ] Add required HTTP headers to XtreamRetrofitClient.kt

### Phase 2: Enhancements (Do Next)
- [ ] Create StreamUrlBuilder utility class
- [ ] Refactor all URL building to use utility
- [ ] Add error handling for 520 responses in PlayerActivity
- [ ] Add retry logic with alternative formats

### Phase 3: Testing
- [ ] Test live stream playback with .ts extension
- [ ] Test VOD playback (already has correct format)
- [ ] Test error handling when stream is offline
- [ ] Test fallback to .m3u8 format
- [ ] Verify headers prevent 403 errors

---

## ⚠️ Known Limitations

1. **520 Errors Are Normal:** Many streams may be offline or under maintenance. This is server-side, not a bug in our app.

2. **M3U8 Availability:** While .ts is preferred, some streams may only work with .m3u8. Implement fallback logic.

3. **Geographic Restrictions:** Some streams may be geo-blocked or require specific conditions.

4. **Server Load:** Peak times may cause delays or 520 errors. Implement retry logic with exponential backoff.

---

## 📝 Testing with Probe Results

You can test the implementation against the probe findings:

```bash
# Re-run probe to verify server status
python3 DebridXtre/global/xtream_probe.py

# Check probe results
cat DebridXtre/global/xtream_probe_result.json

# Test specific stream (from probe results)
# Stream ID: 1127989 (BEIN SPORTS AR)
# Working URL: http://line.spainott.net/live/CVV1JCTL3E/KRSYQDUYER/1127989.ts
```

Use this URL in your Android player to verify the .ts format works correctly.

---

## 🔗 Related Documentation

- `xtream_probe_summary.md` - Detailed probe analysis
- `xtream_probe_result.json` - Raw probe data
- `PROBE_README.md` - How to run the probe
- `global_rules.md` - Project guidelines

---

**Status:** Ready for Implementation ✅  
**Priority:** HIGH - Stream playback currently broken without .ts extension  
**Estimated Effort:** 2-3 hours  
**Testing Required:** YES - Test with multiple stream types

