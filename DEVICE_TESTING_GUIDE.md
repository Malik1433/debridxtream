# 📱 Device Testing Guide - Week 4 Task 4.1

**Date:** November 2, 2025  
**Build:** app-debug.apk (9.5 MB)  
**Checkpoint:** task_4.1_complete  
**What's New:** Custom Result wrapper with improved error handling

---

## 📦 APK Information

```
File: app/build/outputs/apk/debug/app-debug.apk
Size: 9.5 MB
Build: Debug
Version: Week 4 Task 4.1
Status: ✅ BUILD SUCCESSFUL
Tests: ✅ 42/42 passing
```

---

## 🚀 Installation Instructions

### Method 1: ADB Install (Recommended)

#### Step 1: Connect Device
```bash
# Check if device is connected
adb devices

# If multiple devices, specify target
adb -s <device_id> install app/build/outputs/apk/debug/app-debug.apk
```

#### Step 2: Install APK
```bash
cd /home/alik_iving_room/debxtrem

# Fresh install (removes old version)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# OR keep data (upgrade)
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### Step 3: Launch App
```bash
# Launch the app
adb shell am start -n com.tvonnet.debridxtreamiptv/.MainActivity
```

---

### Method 2: USB Transfer

1. **Copy APK to USB drive:**
   ```bash
   cp app/build/outputs/apk/debug/app-debug.apk /path/to/usb/
   ```

2. **On Android TV:**
   - Insert USB drive
   - Use file manager app
   - Navigate to APK
   - Install

---

### Method 3: Network Transfer

1. **Start HTTP server:**
   ```bash
   cd app/build/outputs/apk/debug/
   python3 -m http.server 8000
   ```

2. **On Android TV browser:**
   - Navigate to: `http://<your-ip>:8000/app-debug.apk`
   - Download and install

---

## 🧪 Testing Checklist

### Phase 1: Basic Functionality ✅

#### 1.1 App Launch
- [ ] App opens without crash
- [ ] Home screen loads
- [ ] No error dialogs on startup

#### 1.2 Login Screen (if applicable)
- [ ] Can enter credentials
- [ ] Login button works
- [ ] Error messages display correctly (test with wrong credentials)
- [ ] Success message shows on correct login

---

### Phase 2: Result Wrapper Testing 🎯

**What We're Testing:** The new custom Result wrapper with Success/Error/Loading states

#### 2.1 Live TV Section
- [ ] **Loading State:** Spinner shows while loading categories
- [ ] **Success State:** Categories load and display correctly
- [ ] **Error State:** If no data, appropriate error message shows
- [ ] **Retry:** Can retry after error
- [ ] **Category Selection:** Selecting category loads channels
- [ ] **Channel Play:** Clicking channel launches player

**Expected Behavior:**
```
Loading → Shows spinner
Success → Shows channel list
Error → Shows error message with retry option
```

#### 2.2 VOD (Movies) Section
- [ ] **Loading State:** Spinner shows while loading
- [ ] **Success State:** Movie categories display
- [ ] **Error State:** Error message if no content
- [ ] **Category Switch:** Can switch between categories
- [ ] **Lazy Loading:** Movies load only when category selected
- [ ] **Movie Play:** Clicking movie works

#### 2.3 Series Section
- [ ] **Loading State:** Spinner shows while loading
- [ ] **Success State:** Series categories display
- [ ] **Error State:** Error message if no content
- [ ] **Category Switch:** Can switch between categories
- [ ] **Lazy Loading:** Series load only when category selected
- [ ] **Series Details:** Clicking series shows details

---

### Phase 3: Error Handling Testing 🔧

**Purpose:** Verify Result wrapper handles errors gracefully

#### 3.1 Network Errors
Test with:
- [ ] **No Internet:** Disable WiFi, see if cached data loads
- [ ] **Slow Connection:** Check loading states show correctly
- [ ] **Server Down:** Error message is user-friendly

**Expected:**
- Cached data should load if available
- Error messages should be clear and actionable
- App should not crash

#### 3.2 Invalid Credentials
- [ ] Login with wrong credentials shows error
- [ ] Error message is clear
- [ ] Can retry login
- [ ] App doesn't crash

#### 3.3 Empty Data
- [ ] New account with no content shows appropriate message
- [ ] "No content" message is clear
- [ ] Doesn't show empty screens without message

---

### Phase 4: State Management Testing 🔄

#### 4.1 State Transitions
Test the three states of Result wrapper:

**Loading → Success:**
- [ ] Smooth transition from spinner to content
- [ ] No flickering
- [ ] Content displays correctly

**Loading → Error:**
- [ ] Spinner disappears
- [ ] Error message displays
- [ ] Retry button available

**Error → Success (Retry):**
- [ ] Retry button works
- [ ] Loading shows again
- [ ] Success loads content

#### 4.2 Navigation Between Screens
- [ ] States reset properly when switching tabs
- [ ] No lingering error messages
- [ ] Loading states appropriate for each screen

---

### Phase 5: Performance Testing ⚡

#### 5.1 Memory Usage
Monitor while using app:
- [ ] No memory leaks
- [ ] App stays responsive
- [ ] Memory usage stays ~157MB or similar

#### 5.2 Response Time
- [ ] Category selection is instant
- [ ] Content loads within 2 seconds
- [ ] No lag when scrolling

#### 5.3 Stability
- [ ] No crashes during normal use
- [ ] Can switch between sections multiple times
- [ ] App recovers from errors without restart

---

## 🐛 Bug Reporting Template

If you find issues, report them like this:

```
**Bug:** [Brief description]

**Steps to Reproduce:**
1. [Step 1]
2. [Step 2]
3. [Step 3]

**Expected Behavior:**
[What should happen]

**Actual Behavior:**
[What actually happened]

**Device Info:**
- Android TV Model: [model]
- Android Version: [version]
- App Version: Week 4 Task 4.1

**Logs (if available):**
[ADB logcat output]
```

---

## 📊 Monitoring & Debugging

### View Logs in Real-Time
```bash
# Filter app logs
adb logcat | grep "DebridXtream"

# Filter by tag
adb logcat | grep "XtreamRepository"

# Clear and watch
adb logcat -c && adb logcat | grep -E "DebridXtream|XtreamRepository|LiveViewModel|VodViewModel|SeriesViewModel"
```

### Check for Crashes
```bash
# View crash logs
adb logcat | grep "AndroidRuntime"

# Get crash dump
adb logcat -d | grep -A 50 "FATAL EXCEPTION"
```

### Monitor Memory
```bash
# App memory usage
adb shell dumpsys meminfo com.tvonnet.debridxtreamiptv
```

---

## ✅ Success Criteria

### Critical (Must Pass)
- [✅] App launches without crash
- [✅] Can navigate all sections (Live, VOD, Series)
- [✅] Content loads successfully
- [✅] Error messages display when appropriate
- [✅] No crashes during normal use

### Important (Should Pass)
- [ ] Loading states show correctly
- [ ] Error states are user-friendly
- [ ] Can retry after errors
- [ ] Navigation is smooth
- [ ] Memory usage is reasonable

### Nice to Have
- [ ] Animations are smooth
- [ ] States transition nicely
- [ ] Performance is excellent
- [ ] No visual glitches

---

## 🔍 What We're Verifying

### Result Wrapper Implementation
The core changes in Week 4 Task 4.1:

1. **Success State:**
   - Data loads and displays
   - No error messages
   - Content is accessible

2. **Error State:**
   - Clear error messages
   - Retry functionality
   - No crashes

3. **Loading State:**
   - Spinners show appropriately
   - UI remains responsive
   - Transitions are smooth

4. **Type Safety:**
   - No runtime type errors
   - Proper null handling
   - Safe data access

---

## 📝 Testing Notes Template

Use this to track your testing:

```
=== DEVICE TESTING SESSION ===
Date: [date]
Device: [Android TV model]
APK: app-debug.apk (9.5 MB)
Build: Week 4 Task 4.1

[✅] App Installation
[✅] First Launch
[✅] Login/Setup
[✅] Live TV Loading
[✅] VOD Loading
[✅] Series Loading
[✅] Error Handling
[✅] State Transitions
[✅] Performance

Issues Found:
1. [Issue description]
2. [Issue description]

Overall: ✅ PASS / ❌ FAIL

Notes:
[Additional observations]
```

---

## 🚨 Common Issues & Solutions

### Issue: App Won't Install
**Solution:**
```bash
# Uninstall old version first
adb uninstall com.tvonnet.debridxtreamiptv

# Then install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Issue: App Crashes on Launch
**Check:**
1. View crash logs: `adb logcat | grep "AndroidRuntime"`
2. Check if Hilt is initialized
3. Verify all dependencies loaded

### Issue: No Data Loading
**Check:**
1. Internet connection
2. Login credentials
3. Cache exists: Look for "Using cached data" in logs

### Issue: Error States Not Showing
**Verify:**
1. Check Result wrapper is being used
2. Verify `onFailure()` is called
3. Check error message strings

---

## 📞 Support Commands

```bash
# Quick install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.tvonnet.debridxtreamiptv/.MainActivity

# View logs
adb logcat | grep "DebridXtream"

# Clear app data (fresh start)
adb shell pm clear com.tvonnet.debridxtreamiptv

# Uninstall
adb uninstall com.tvonnet.debridxtreamiptv
```

---

## 🎯 Expected Results

Based on our 42 passing unit tests, you should see:

✅ **Live TV:**
- Categories load correctly
- Channels display in grid
- Channel selection works
- Player launches

✅ **VOD (Movies):**
- Categories load lazily
- Movies display when category selected
- Navigation is smooth
- Details show correctly

✅ **Series:**
- Categories load lazily
- Series display when category selected
- Season/episode navigation works

✅ **Error Handling:**
- Clear error messages
- Retry buttons work
- No app crashes
- Graceful degradation

---

## 🎉 Completion

Once testing is complete:

1. **Mark all checkboxes** above
2. **Document any issues** found
3. **Report results** (PASS/FAIL)
4. **Save testing notes** for reference

### If All Tests Pass:
✅ Week 4 Task 4.1 is **PRODUCTION READY**  
✅ Safe to proceed to Week 5

### If Issues Found:
⚠️ Document issues  
⚠️ Create bug reports  
⚠️ Fix before proceeding  

---

**Status:** Ready for Testing  
**APK Location:** `app/build/outputs/apk/debug/app-debug.apk`  
**Size:** 9.5 MB  
**Build:** ✅ SUCCESS

**Happy Testing! 🚀**

