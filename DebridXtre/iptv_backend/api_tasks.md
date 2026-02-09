IPTV BACKEND AGENT

goal:
- talk to Xtream Codes API
- do NOT touch UI

tasks:
1. create data/remote/XtreamApiService.kt
   - GET player_api.php
   - login(username, password, baseUrl)
   - get_live_categories
   - get_vod_categories
   - get_series_categories

2. create data/XtreamRepository.kt
   - functions that call the above
   - one function that downloads ALL and returns combined result
   - WRAP EVERYTHING in try/catch
   - if VOD/Series missing -> return emptyList()
   - never crash UI layer

3. create cache helper
   - write JSON to files dir: iptv_cache.json
   - content:
     {
       "timestamp": "...",
       "live": [...],
       "vod": [...],
       "series": [...],
       "epg": {...}
     }

4. add EPG fetch right after categories
   - try to fetch EPG (xml/json) from same server
   - parse minimal fields: channel_id, start, end, title
   - store under "epg" in the same cache file
   - if EPG fails, continue

output target:
- /global/design_output.md under ### IPTV_BACKEND
