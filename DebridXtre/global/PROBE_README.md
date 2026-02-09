# Xtream Codes Probe Agent

A standalone Python tool to test Xtream Codes server connectivity, endpoint availability, and stream URL patterns.

## Purpose

This probe agent helps determine:
1. ✅ **Authentication** - Is the server accessible with given credentials?
2. ✅ **Endpoints** - Which API endpoints are working (live/VOD/series)?
3. ✅ **Stream Formats** - Which URL pattern works best (m3u8 vs ts)?
4. 📊 **Server Info** - Port, protocol, timezone, and statistics

## Requirements

```bash
pip3 install requests urllib3
```

## Usage

### Quick Test (Default Credentials)
```bash
python3 xtream_probe.py
```

### Custom Server Test
```bash
python3 xtream_probe.py <base_url> <username> <password> [output_path]
```

**Example:**
```bash
python3 xtream_probe.py \
  http://example.xtream.tv \
  myusername \
  mypassword \
  /path/to/report.json
```

## Output

### Console Output
Real-time progress with color-coded results:
- ✓ Success indicators
- ✗ Failure indicators
- Status codes and error messages
- Stream testing progress

### JSON Report
Generated at: `xtream_probe_result.json`

```json
{
  "base_url": "http://line.spainott.net",
  "auth": "ok",
  "live_categories": "ok",
  "vod_categories": "ok",
  "series_categories": "ok",
  "preferred_live_url": "ts",
  "details": {
    "server_info": { ... },
    "working_stream_ts": { ... },
    "tested_streams": [ ... ]
  }
}
```

## What It Tests

### Step 1: Authentication
- Tests `/player_api.php?username={u}&password={p}`
- Verifies `user_info` exists in response
- Collects server configuration

### Step 2: Category Endpoints
Tests three endpoints:
1. `get_live_categories` - Live TV categories
2. `get_vod_categories` - Video on Demand categories
3. `get_series_categories` - TV series categories

### Step 3: Stream URL Patterns
- Fetches first 10 live streams
- Tests `.m3u8` (HLS) format
- Tests `.ts` (Transport Stream) format
- Reports which format works

## Features

- **Auto-retry with HTTPS** - If HTTP fails, automatically tries HTTPS
- **Multiple Stream Testing** - Tests up to 10 streams to find working URLs
- **Detailed Error Reporting** - Captures HTTP codes, SSL errors, connection issues
- **SSL Verification Bypass** - For testing servers with self-signed certificates
- **User-Agent Spoofing** - Mimics VLC player to avoid blocking
- **Timeout Protection** - 15-second timeout prevents hanging

## Exit Codes

- `0` - Success (authentication passed)
- `1` - Failure (authentication failed)

## Troubleshooting

### 403 Forbidden Error
**Solution:** The probe automatically adds VLC headers. If still failing:
- Check credentials are correct
- Verify server allows API access
- Try HTTPS variant of the URL

### 520 Server Error
**Meaning:** CloudFlare/server overload or stream offline  
**Solution:** Normal for some streams. Probe tests multiple streams to find working ones.

### Connection Timeout
**Causes:**
- Server is down
- Network connectivity issues
- Firewall blocking requests

**Solution:**
- Verify URL is correct
- Check internet connection
- Try from different network

### SSL Certificate Error
**Solution:** Probe automatically disables SSL verification for testing. For production, ensure proper SSL certificates.

## Integration with DebridXtreamIPTV

The probe results inform the Android app implementation:

1. **Stream URL Builder** - Use the preferred format from probe results
2. **Headers** - Apply the same headers in Retrofit client
3. **Error Handling** - Handle 520 errors gracefully
4. **Fallback Strategy** - Try alternative formats if preferred fails

## Security Note

⚠️ **This tool is for testing only.** 

- Credentials are passed as command-line arguments (visible in process list)
- SSL verification is disabled
- Not suitable for production deployments
- Use only in development/testing environments

## Advanced Usage

### Testing Only Authentication
```bash
# Probe will stop after auth test if it fails
python3 xtream_probe.py http://server.com user pass
```

### Batch Testing Multiple Servers
```bash
#!/bin/bash
while IFS=',' read -r url user pass; do
  echo "Testing $url..."
  python3 xtream_probe.py "$url" "$user" "$pass" "report_$user.json"
done < servers.csv
```

## Extending the Probe

To add more tests, modify the `run_probe()` method:

```python
# Test EPG endpoint
epg_ok = self.test_endpoint("get_simple_data_table", "stream_id", "12345")

# Test VOD stream URLs
vod_url_ok = self.test_vod_stream_url(vod_id)
```

## License

Part of the DebridXtreamIPTV project. For internal development use.

## Support

For issues or questions:
1. Check `xtream_probe_summary.md` for results analysis
2. Review `xtream_probe_result.json` for detailed output
3. Verify server credentials and accessibility

---

**Last Updated:** 2025-11-01  
**Version:** 1.0  
**Status:** Production Ready ✅

