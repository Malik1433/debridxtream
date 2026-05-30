import sys
import json
import re
import html

# Enforce UTF-8 for print statements in Windows
sys.stdout.reconfigure(encoding='utf-8')

with open('watch_history.xml', 'r', encoding='utf-16', errors='ignore') as f:
    content = f.read()

# Try utf-8 if utf-16 doesn't look right
if 'continue_watching' not in content:
    with open('watch_history.xml', 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

# Find the JSON array inside the XML string
match = re.search(r'name="continue_watching">([^<]+)</string>', content)
if match:
    json_str = html.unescape(match.group(1))
    items = json.loads(json_str)
    print(f"Total items: {len(items)}")
    
    def print_item(item, label):
        print(f"--- {label} ---")
        print(f"contentId: {item.get('contentId')}")
        print(f"contentType: {item.get('contentType')}")
        print(f"source: {item.get('source')}")
        print(f"tmdbId: {item.get('tmdbId')}")
        print(f"imdbId: {item.get('imdbId')}")
        print(f"seriesId: {item.get('seriesId')}")
        print(f"seasonNumber: {item.get('seasonNumber')}")
        print(f"episodeNumber: {item.get('episodeNumber')}")
        
        print(f"has streamUrl: {'yes' if item.get('streamUrl') else 'no'}")
        print(f"expiresAt: {item.get('expiresAt')}")
        print(f"directDebridPlayback: {item.get('directDebridPlayback')}")
        
        print(f"has debridInfoHash: {'yes' if item.get('debridInfoHash') else 'no'}")
        print(f"has debridMagnet: {'yes' if item.get('debridMagnet') else 'no'}")
        
        print(f"debridProvider: {item.get('debridProvider')}")
        print(f"debridSourceType: {item.get('debridSourceType')}")
        print(f"debridSourceName: {item.get('debridSourceName')}")
        print(f"debridStreamId: {item.get('debridStreamId')}")
        print(f"debridFileIdx: {item.get('debridFileIdx')}")
        print(f"debridBingeGroup: {item.get('debridBingeGroup')}")
        
        print(f"currentPosition: {item.get('currentPosition')}")
        print(f"totalDuration: {item.get('totalDuration')}")
        print(f"lastWatchedTimestamp: {item.get('lastWatchedTimestamp')}")

    if items:
        # Search for first debrid item (recent working)
        for item in items:
            if item.get('source') == 'DEBRID' or 'debrid' in str(item).lower():
                print_item(item, "Recent Working Item (first debrid)")
                break

        # Search for last debrid item (old failing)
        for item in reversed(items):
            if item.get('source') == 'DEBRID' or 'debrid' in str(item).lower():
                print_item(item, "Old Failing Item (last debrid)")
                break
else:
    print("Could not find continue_watching data")
