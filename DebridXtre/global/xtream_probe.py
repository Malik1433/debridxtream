#!/usr/bin/env python3
"""
Xtream Codes Probe Agent
Tests Xtream server connectivity, endpoints, and stream URL patterns.
"""

import json
import sys
import requests
from typing import Dict, Optional, Tuple
from urllib.parse import urljoin
import urllib3

# Disable SSL warnings for testing
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

class XtreamProbe:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url.rstrip('/')
        self.username = username
        self.password = password
        self.session = requests.Session()
        self.session.timeout = 10
        
        # Add headers to mimic a real IPTV player/client
        self.session.headers.update({
            'User-Agent': 'VLC/3.0.16 LibVLC/3.0.16',
            'Accept': '*/*',
            'Connection': 'keep-alive'
        })
        
        self.report = {
            "base_url": self.base_url,
            "auth": "unknown",
            "live_categories": "unknown",
            "vod_categories": "unknown",
            "series_categories": "unknown",
            "preferred_live_url": "unknown",
            "details": {}
        }
    
    def test_auth(self, use_https: bool = False) -> Tuple[bool, Optional[Dict]]:
        """Test authentication endpoint"""
        protocol = "https" if use_https else "http"
        base_domain = self.base_url.replace('http://', '').replace('https://', '')
        url = f"{protocol}://{base_domain}/player_api.php"
        params = {
            "username": self.username,
            "password": self.password
        }
        
        try:
            print(f"[AUTH] Testing {url}")
            response = self.session.get(url, params=params, timeout=15, verify=False)
            print(f"[AUTH] Status: {response.status_code}")
            
            if response.status_code == 200:
                try:
                    data = response.json()
                    if "user_info" in data:
                        print(f"[AUTH] ✓ Authentication successful")
                        self.report["details"]["auth_response"] = {
                            "status": data.get("user_info", {}).get("status"),
                            "message": data.get("user_info", {}).get("message")
                        }
                        return True, data
                    else:
                        print(f"[AUTH] ✗ No user_info in response")
                        self.report["details"]["auth_error"] = "No user_info in response"
                        return False, None
                except json.JSONDecodeError as e:
                    print(f"[AUTH] ✗ Invalid JSON response")
                    print(f"[AUTH] Response text: {response.text[:200]}")
                    self.report["details"]["auth_error"] = f"Invalid JSON: {str(e)}"
                    return False, None
            elif response.status_code == 403:
                print(f"[AUTH] ✗ Access Forbidden (403)")
                self.report["details"]["auth_error"] = "403 Forbidden - Server blocked request"
                # Try HTTPS if HTTP failed with 403
                if not use_https:
                    print("[AUTH] Retrying with HTTPS...")
                    return self.test_auth(use_https=True)
                return False, None
            else:
                print(f"[AUTH] ✗ HTTP {response.status_code}")
                print(f"[AUTH] Response: {response.text[:200]}")
                self.report["details"]["auth_error"] = f"HTTP {response.status_code}"
                return False, None
        except requests.exceptions.SSLError as e:
            print(f"[AUTH] ✗ SSL Error: {e}")
            self.report["details"]["auth_error"] = f"SSL Error: {str(e)}"
            if not use_https:
                print("[AUTH] Retrying with HTTPS...")
                return self.test_auth(use_https=True)
            return False, None
        except requests.exceptions.ConnectionError as e:
            print(f"[AUTH] ✗ Connection Error: {e}")
            self.report["details"]["auth_error"] = f"Connection failed: {str(e)}"
            return False, None
        except Exception as e:
            print(f"[AUTH] ✗ Error: {e}")
            self.report["details"]["auth_error"] = str(e)
            return False, None
    
    def test_endpoint(self, action: str, use_https: bool = False) -> bool:
        """Test a specific Xtream endpoint"""
        protocol = "https" if use_https else "http"
        base_domain = self.base_url.replace('http://', '').replace('https://', '')
        url = f"{protocol}://{base_domain}/player_api.php"
        params = {
            "username": self.username,
            "password": self.password,
            "action": action
        }
        
        try:
            print(f"[{action.upper()}] Testing endpoint...")
            response = self.session.get(url, params=params, timeout=15, verify=False)
            print(f"[{action.upper()}] Status: {response.status_code}")
            
            if response.status_code == 200:
                try:
                    data = response.json()
                    if isinstance(data, list) and len(data) > 0:
                        print(f"[{action.upper()}] ✓ Found {len(data)} items")
                        self.report["details"][action] = {"count": len(data)}
                        return True
                    else:
                        print(f"[{action.upper()}] ✗ Empty or invalid response")
                        return False
                except json.JSONDecodeError:
                    print(f"[{action.upper()}] ✗ Invalid JSON")
                    return False
            else:
                print(f"[{action.upper()}] ✗ HTTP {response.status_code}")
                return False
        except requests.exceptions.SSLError:
            print(f"[{action.upper()}] ✗ SSL Error")
            if not use_https:
                print(f"[{action.upper()}] Retrying with HTTPS...")
                return self.test_endpoint(action, use_https=True)
            return False
        except Exception as e:
            print(f"[{action.upper()}] ✗ Error: {e}")
            return False
    
    def get_live_streams(self, use_https: bool = False, limit: int = 10) -> list:
        """Get multiple live stream IDs for testing"""
        protocol = "https" if use_https else "http"
        base_domain = self.base_url.replace('http://', '').replace('https://', '')
        url = f"{protocol}://{base_domain}/player_api.php"
        params = {
            "username": self.username,
            "password": self.password,
            "action": "get_live_streams"
        }
        
        try:
            print("[STREAMS] Fetching live streams...")
            response = self.session.get(url, params=params, timeout=15, verify=False)
            
            if response.status_code == 200:
                data = response.json()
                if isinstance(data, list) and len(data) > 0:
                    streams = data[:limit]
                    print(f"[STREAMS] ✓ Found {len(data)} total streams, testing first {len(streams)}")
                    return streams
                else:
                    print("[STREAMS] ✗ No streams found")
                    return []
            else:
                print(f"[STREAMS] ✗ HTTP {response.status_code}")
                return []
        except Exception as e:
            print(f"[STREAMS] ✗ Error: {e}")
            return []
    
    def test_stream_url(self, stream_id: str, url_type: str) -> bool:
        """Test a stream URL (m3u8 or ts)"""
        # Extract base domain
        base_domain = self.base_url.replace('http://', '').replace('https://', '')
        
        if url_type == "m3u8":
            url = f"http://{base_domain}/live/{self.username}/{self.password}/{stream_id}.m3u8"
        else:  # ts
            url = f"http://{base_domain}/live/{self.username}/{self.password}/{stream_id}.ts"
        
        try:
            print(f"[STREAM-{url_type.upper()}] Testing {url}")
            # Use HEAD request first
            response = self.session.head(url, timeout=10, allow_redirects=True)
            
            if response.status_code in [200, 206, 302]:
                print(f"[STREAM-{url_type.upper()}] ✓ Status {response.status_code}")
                return True
            else:
                print(f"[STREAM-{url_type.upper()}] ✗ Status {response.status_code}")
                # Try GET with small range
                try:
                    response = self.session.get(url, timeout=10, stream=True, headers={"Range": "bytes=0-1023"})
                    if response.status_code in [200, 206]:
                        print(f"[STREAM-{url_type.upper()}] ✓ GET request successful")
                        return True
                except:
                    pass
                return False
        except Exception as e:
            print(f"[STREAM-{url_type.upper()}] ✗ Error: {e}")
            return False
    
    def run_probe(self) -> Dict:
        """Run the complete probe sequence"""
        print("=" * 60)
        print("XTREAM CODES PROBE AGENT")
        print("=" * 60)
        print(f"Base URL: {self.base_url}")
        print(f"Username: {self.username}")
        print(f"Password: {'*' * len(self.password)}")
        print("=" * 60)
        
        # Step 1: Test authentication
        print("\n[STEP 1] Testing authentication...")
        auth_ok, auth_data = self.test_auth()
        self.report["auth"] = "ok" if auth_ok else "fail"
        
        if not auth_ok:
            print("\n✗ Authentication failed. Cannot continue.")
            self.report["details"]["error"] = "Authentication failed"
            return self.report
        
        # Store server info if available
        if auth_data and "server_info" in auth_data:
            self.report["details"]["server_info"] = auth_data["server_info"]
        
        # Step 2: Test category endpoints
        print("\n[STEP 2] Testing category endpoints...")
        
        print("\n[2a] Testing live categories...")
        live_ok = self.test_endpoint("get_live_categories")
        self.report["live_categories"] = "ok" if live_ok else "fail"
        
        print("\n[2b] Testing VOD categories...")
        vod_ok = self.test_endpoint("get_vod_categories")
        self.report["vod_categories"] = "ok" if vod_ok else "fail"
        
        print("\n[2c] Testing series categories...")
        series_ok = self.test_endpoint("get_series_categories")
        self.report["series_categories"] = "ok" if series_ok else "fail"
        
        # Step 3: Test stream URLs
        print("\n[STEP 3] Testing stream URL patterns...")
        streams = self.get_live_streams(limit=10)
        
        if streams:
            m3u8_working = False
            ts_working = False
            tested_streams = []
            
            # Try up to 10 streams to find a working one
            for i, stream in enumerate(streams):
                stream_id = str(stream.get("stream_id"))
                stream_name = stream.get("name", "Unknown")
                
                print(f"\n[3.{i+1}] Testing stream: {stream_name} (ID: {stream_id})")
                tested_streams.append({
                    "id": stream_id,
                    "name": stream_name
                })
                
                # Test M3U8
                if not m3u8_working:
                    m3u8_ok = self.test_stream_url(stream_id, "m3u8")
                    if m3u8_ok:
                        m3u8_working = True
                        self.report["details"]["working_stream_m3u8"] = {
                            "id": stream_id,
                            "name": stream_name
                        }
                        print(f"[3.{i+1}] ✓ Found working M3U8 stream!")
                
                # Test TS
                if not ts_working:
                    ts_ok = self.test_stream_url(stream_id, "ts")
                    if ts_ok:
                        ts_working = True
                        self.report["details"]["working_stream_ts"] = {
                            "id": stream_id,
                            "name": stream_name
                        }
                        print(f"[3.{i+1}] ✓ Found working TS stream!")
                
                # If we found working URLs for both formats, we can stop
                if m3u8_working and ts_working:
                    break
            
            self.report["details"]["tested_streams"] = tested_streams
            
            if m3u8_working:
                self.report["preferred_live_url"] = "m3u8"
            elif ts_working:
                self.report["preferred_live_url"] = "ts"
            else:
                self.report["preferred_live_url"] = "unknown"
                print("\n✗ No working stream URLs found in tested streams")
        else:
            print("\n✗ Could not fetch live streams for URL testing")
            self.report["preferred_live_url"] = "unknown"
        
        # Final summary
        print("\n" + "=" * 60)
        print("PROBE SUMMARY")
        print("=" * 60)
        print(f"Authentication: {self.report['auth']}")
        print(f"Live Categories: {self.report['live_categories']}")
        print(f"VOD Categories: {self.report['vod_categories']}")
        print(f"Series Categories: {self.report['series_categories']}")
        print(f"Preferred Stream URL: {self.report['preferred_live_url']}")
        print("=" * 60)
        
        return self.report
    
    def save_report(self, output_path: str):
        """Save the probe report to a JSON file"""
        try:
            with open(output_path, 'w') as f:
                json.dump(self.report, f, indent=2)
            print(f"\n✓ Report saved to: {output_path}")
        except Exception as e:
            print(f"\n✗ Failed to save report: {e}")


def main():
    # Fixed credentials from the task
    BASE_URL = "http://line.spainott.net"
    USERNAME = "CVV1JCTL3E"
    PASSWORD = "KRSYQDUYER"
    OUTPUT_PATH = "/home/alik_iving_room/debxtrem/DebridXtre/global/xtream_probe_result.json"
    
    # Allow command line overrides
    if len(sys.argv) >= 4:
        BASE_URL = sys.argv[1]
        USERNAME = sys.argv[2]
        PASSWORD = sys.argv[3]
    if len(sys.argv) >= 5:
        OUTPUT_PATH = sys.argv[4]
    
    # Run the probe
    probe = XtreamProbe(BASE_URL, USERNAME, PASSWORD)
    report = probe.run_probe()
    
    # Save the report
    probe.save_report(OUTPUT_PATH)
    
    # Exit with appropriate code
    if report["auth"] == "ok":
        sys.exit(0)
    else:
        sys.exit(1)


if __name__ == "__main__":
    main()

