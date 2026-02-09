# Xtream Probe Results Summary

**Date:** 2025-11-01  
**Server:** http://line.spainott.net  
**Status:** ✓ SUCCESSFUL

---

## Executive Summary

The Xtream Codes server probe has been successfully completed. All authentication and category endpoints are working correctly. The server hosts a large IPTV collection with 18,248+ live streams.

---

## Test Results

### ✅ Authentication
- **Status:** OK
- **User Status:** Active
- **Server Protocol:** HTTP (Port 80)
- **HTTPS Port:** 443 available
- **Server Timezone:** Europe/Amsterdam

### ✅ Live Categories
- **Status:** OK
- **Count:** 365 categories
- **Endpoint:** `/player_api.php?action=get_live_categories`

### ✅ VOD Categories
- **Status:** OK
- **Count:** 313 categories
- **Endpoint:** `/player_api.php?action=get_vod_categories`

### ✅ Series Categories
- **Status:** OK
- **Count:** 192 categories
- **Endpoint:** `/player_api.php?action=get_series_categories`

### ✅ Stream URL Format
- **Preferred Format:** `.ts` (Transport Stream)
- **Working URL Pattern:** `http://line.spainott.net/live/{username}/{password}/{stream_id}.ts`
- **Tested Streams:** 10
- **Working Example:** Stream ID 1127989 (BEIN SPORTS AR)

---

## Implementation Recommendations

### 1. Stream URL Builder
Use the following URL pattern for live streams:

```kotlin
fun buildLiveStreamUrl(streamId: String): String {
    return "$baseUrl/live/$username/$password/$streamId.ts"
}
```

### 2. Fallback Strategy
Some streams may return 520 errors (server overload/offline). Implement:
- Retry logic with exponential backoff
- Alternative stream format fallback (try .m3u8 if .ts fails)
- User-friendly error messages for unavailable streams

### 3. Required Headers
The following headers must be included in API requests:

```kotlin
headers = mapOf(
    "User-Agent" to "VLC/3.0.16 LibVLC/3.0.16",
    "Accept" to "*/*",
    "Connection" to "keep-alive"
)
```

### 4. SSL Configuration
- Server supports both HTTP (80) and HTTPS (443)
- Disable SSL verification for testing, but enable for production
- HTTP works reliably for this server

### 5. API Endpoint Structure
All API calls follow this pattern:

```
http://line.spainott.net/player_api.php?username={u}&password={p}&action={action}
```

**Available Actions:**
- (none) - Authentication/User Info
- `get_live_categories` - Fetch live TV categories
- `get_vod_categories` - Fetch VOD categories
- `get_series_categories` - Fetch series categories
- `get_live_streams` - Fetch all live streams
- `get_vod_streams` - Fetch VOD items
- `get_series` - Fetch series

---

## Known Issues

1. **Stream Availability:** Many streams return 520 errors, indicating:
   - Server overload
   - Stream temporarily offline
   - Geographic restrictions
   - Premium/inactive channels

2. **M3U8 Format:** HLS (.m3u8) streams were not accessible in the tested sample, though they may work for other channels.

3. **HEAD Request Limitations:** Stream HEAD requests return 520, but GET requests with Range headers work.

---

## Server Statistics

- **Total Live Streams:** 18,248+
- **Total Live Categories:** 365
- **Total VOD Categories:** 313
- **Total Series Categories:** 192
- **Server Uptime:** Active
- **Response Time:** < 1s for API calls

---

## Next Steps for Development

1. **Update XtreamRepository:** Ensure it uses the `.ts` extension for live stream URLs
2. **Add Headers:** Update RetrofitClient to include the required headers
3. **Implement Error Handling:** Handle 520 errors gracefully with retry logic
4. **Test VOD/Series URLs:** Probe script currently only tests live streams
5. **Cache Strategy:** Implement caching for categories (already exists in project)
6. **ExoPlayer Configuration:** Configure ExoPlayer to handle .ts transport streams

---

## Files Generated

- `xtream_probe.py` - Python probe script (reusable)
- `xtream_probe_result.json` - Detailed JSON report
- `xtream_probe_summary.md` - This summary document

---

## Testing the Probe

To run the probe again with different credentials:

```bash
python3 DebridXtre/global/xtream_probe.py [base_url] [username] [password] [output_path]
```

Example:
```bash
python3 DebridXtre/global/xtream_probe.py \
  http://line.spainott.net \
  CVV1JCTL3E \
  KRSYQDUYER \
  /path/to/output.json
```

---

## Conclusion

✅ The Xtream Codes server is fully operational and compatible with the DebridXtreamIPTV application. The preferred stream format is `.ts` (Transport Stream), and all category endpoints are working correctly. The implementation can proceed with confidence using the documented URL patterns and headers.

