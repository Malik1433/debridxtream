# Real-Debrid Login Fix - Complete Documentation

## 🔧 All Fixes Applied (Based on Official API Documentation)

### API Endpoint Fixes

1. **Device Code Request** ✅
   - **Before:** `POST /oauth/v2/device/code` with Form data
   - **After:** `GET /oauth/v2/device/code` with Query parameters
   - **Reference:** [Real-Debrid API Docs - Device Flow Step 1](https://api.real-debrid.com/)

2. **Device Credentials Polling** ✅
   - **Before:** `POST /oauth/v2/device/credentials` with Form data
   - **After:** `GET /oauth/v2/device/credentials` with Query parameters
   - **Reference:** [Real-Debrid API Docs - Device Flow Step 3](https://api.real-debrid.com/)

3. **Field Names Fixed** ✅
   - `user_code`: String (short 8-char code for user)
   - `device_code`: String (long code for polling)
   - `interval`: Int (not Long)
   - `expires_in`: Int (not Long)

### Data Model Fixes

**File:** `RealDebridModels.kt`

```kotlin
data class RealDebridDeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String,        // Long code for API
    @SerializedName("user_code") val userCode: String,            // Short code for USER
    @SerializedName("interval") val interval: Int,                // Polling interval
    @SerializedName("expires_in") val expiresIn: Int,            // Expiration time
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("direct_verification_url") val directVerificationUrl: String? = null
)
```

### Repository Logic Fix

**File:** `DebridAccountRepository.kt`

Now properly maps:
- `userCode` → Short code displayed to user (e.g., "J45KBGK6")
- `deviceCode` → Used internally for polling
- `verificationUrl` → Simple URL: https://real-debrid.com/device

### Debug Logging Added

Added comprehensive logging to diagnose issues:
- ViewModel: Logs device code response and state changes
- Fragment: Logs UI updates

## 🧪 Testing Instructions

### Step 1: Force Stop App
```bash
# From computer:
adb shell am force-stop com.tvonnet.debridxtreamiptv

# Or from TV:
Settings > Apps > DebridXtreamIPTV > Force Stop
```

### Step 2: Clear Logs & Open App
```bash
adb logcat -c
```

### Step 3: Test Login Flow

1. **Open App on TV**
2. **Navigate to DEBRID section**
3. **Click "Sign In with Real-Debrid"**

### Step 4: Verify Expected Behavior

**TV Screen Should Show:**
```
Real-Debrid Authentication

Visit the URL below on your phone or computer and enter this code:

        A1B2C3D4  ← Short code (8 characters)

https://real-debrid.com/device

[CANCEL]
```

**With Progress indicator showing polling is active**

### Step 5: Complete Authorization

1. **On Phone/PC:** Visit https://real-debrid.com/device
2. **Enter the short code** shown on TV (e.g., "A1B2C3D4")
3. **Log in** to Real-Debrid (if not already)
4. **Authorize** the application
5. **TV should automatically detect** and show success message

## 🐛 Debugging

### Check Logs in Real-Time

```bash
adb logcat | grep -E "DebridAuth|okhttp"
```

### Expected Log Sequence

1. **Device Code Request:**
```
DebridAuth: Starting authentication...
okhttp: --> GET https://api.real-debrid.com/oauth/v2/device/code?client_id=X245A4XAIBGVM&new_credentials=yes
okhttp: <-- 200 (response with user_code + device_code)
DebridAuth: Device code received: userCode=A1B2C3D4, deviceCode=LONG_CODE_HERE
DebridAuth: State updated to ShowingCode
DebridAuthFragment: updateUI called with state: ShowingCode
DebridAuthFragment: Showing code: A1B2C3D4, URL: https://real-debrid.com/device
```

2. **Polling Starts (403 until authorized):**
```
okhttp: --> GET https://api.real-debrid.com/oauth/v2/device/credentials?client_id=X245A4XAIBGVM&code=LONG_DEVICE_CODE
okhttp: <-- 403 {"error": null, "error_code": null}
```

3. **After User Authorizes (200 with credentials):**
```
okhttp: <-- 200 {"client_id": "...", "client_secret": "..."}
```

4. **Token Exchange:**
```
okhttp: --> POST https://api.real-debrid.com/oauth/v2/token
okhttp: <-- 200 {"access_token": "...", "refresh_token": "..."}
DebridAuth: Success!
```

## 📋 Known Issues & Solutions

### Issue 1: Loading Stuck / Code Not Showing

**Symptoms:**
- Button shows "Fetching device code..."
- Never progresses to showing code
- Or shows long URL instead of short code

**Solution:**
1. Force stop app
2. Reopen app
3. Try again

**Root Cause:** Old polling job from previous session still running

### Issue 2: 403 Error During Polling

**This is NORMAL!** According to [Real-Debrid API docs](https://api.real-debrid.com/):
> "Your application will receive an error message until the user has entered the code and authorized the application."

403 with `{"error": null}` means: "Waiting for user to authorize"

### Issue 3: Timeout After 15 Minutes

**Expected Behavior:** Device code expires after 15 minutes (900 seconds)

**Solution:** Click "Cancel" and start fresh authentication

## 🎯 Success Criteria

✅ Short 8-character code displays on TV (not long URL)
✅ Simple verification URL: https://real-debrid.com/device
✅ Polling happens automatically in background
✅ Success detected when user authorizes
✅ Token saved securely
✅ Debrid content loads after successful auth

## 📚 Reference

- **Official API Docs:** https://api.real-debrid.com/
- **OAuth Device Flow:** Section "Workflow for limited input devices" in API docs
- **Implementation Files:**
  - `RealDebridApiService.kt` - API endpoints
  - `RealDebridModels.kt` - Response models
  - `DebridAccountRepository.kt` - Business logic
  - `DebridAuthViewModel.kt` - Auth state management
  - `DebridAuthFragment.kt` - UI display

## 🔄 Build Info

- **Last Build:** November 9, 2025
- **Build Type:** Debug
- **Logging:** Enabled (DebridAuth tag)
- **Device:** AFTKM - 11

---

**All fixes are based on official Real-Debrid API documentation and implement the standard OAuth Device Code Flow for limited input devices (TVs, streaming boxes, etc.).**

