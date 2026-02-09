# 📱 Week 6 Manual Testing Guide

**Week:** 6 - Room Database Integration  
**Date:** November 2, 2025  
**Tester:** Manual verification needed

---

## ✅ Test Checklist

### Test 1: Database File Creation
**Purpose:** Verify Room database file is created on app launch

**Steps:**
1. Connect to device: `adb connect 192.168.0.54:5555`
2. Force stop app: `adb shell am force-stop com.tvonnet.debridxtreamiptv`
3. Launch app: `adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity`
4. Check database files:
   ```bash
   adb shell "run-as com.tvonnet.debridxtreamiptv ls -lh databases/"
   ```

**Expected Result:**
- Database file: `debrid_xtream_db` should exist
- No crash on launch
- App should start normally

**Status:** ⬜ Not Tested Yet

---

### Test 2: App Doesn't Crash with Empty Database
**Purpose:** Verify app works fine even with empty database (first launch scenario)

**Steps:**
1. Clear app data: `adb shell pm clear com.tvonnet.debridxtreamiptv`
2. Launch app: `adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity`
3. Navigate to Live TV section
4. Navigate to Movies section
5. Navigate to Series section

**Expected Result:**
- No crashes
- App may show "no data" or loading state
- UI should be responsive

**Status:** ⬜ Not Tested Yet

---

### Test 3: Existing Features Still Work
**Purpose:** Verify Room database addition didn't break existing functionality

**Steps:**
1. Launch app normally
2. Test Live TV:
   - Open Live TV section
   - Check if channels load
   - Try to play a channel
3. Test Movies:
   - Open Movies section
   - Check if movies load
   - Scroll through list
4. Test Series:
   - Open Series section
   - Check if series load
   - Scroll through list

**Expected Result:**
- All existing features work as before
- No performance degradation
- No crashes

**Status:** ⬜ Not Tested Yet

---

### Test 4: Memory Usage Check
**Purpose:** Verify Room database doesn't cause memory issues

**Steps:**
1. Check memory before: 
   ```bash
   adb shell dumpsys meminfo com.tvonnet.debridxtreamiptv | grep TOTAL
   ```
2. Use app for 2-3 minutes (navigate between sections)
3. Check memory after:
   ```bash
   adb shell dumpsys meminfo com.tvonnet.debridxtreamiptv | grep TOTAL
   ```

**Expected Result:**
- Memory usage should be stable (~150-200 MB)
- No OutOfMemoryError
- No excessive memory growth

**Status:** ⬜ Not Tested Yet

---

### Test 5: Database Survives App Restart
**Purpose:** Verify database persists across app restarts

**Steps:**
1. Launch app and use it
2. Force stop: `adb shell am force-stop com.tvonnet.debridxtreamiptv`
3. Relaunch: `adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity`
4. Check database still exists:
   ```bash
   adb shell "run-as com.tvonnet.debridxtreamiptv ls -lh databases/"
   ```

**Expected Result:**
- Database file still exists
- App launches normally
- No data loss

**Status:** ⬜ Not Tested Yet

---

### Test 6: Logcat Analysis
**Purpose:** Check for any database-related errors or warnings

**Steps:**
1. Clear logcat: `adb logcat -c`
2. Launch app: `adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity`
3. Use app for 1-2 minutes
4. Check logs:
   ```bash
   adb logcat -d | grep -E "(Room|Database|SQLException|FATAL)"
   ```

**Expected Result:**
- No FATAL errors
- No SQLException
- Room database initialization should succeed
- No "database locked" errors

**Status:** ⬜ Not Tested Yet

---

### Test 7: Database Size Check
**Purpose:** Verify database size is reasonable

**Steps:**
1. Use app normally for few minutes
2. Check database size:
   ```bash
   adb shell "run-as com.tvonnet.debridxtreamiptv du -h databases/"
   ```

**Expected Result:**
- Database size should be small initially (<1 MB for empty)
- Size should grow reasonably with data
- No excessive space usage

**Status:** ⬜ Not Tested Yet

---

## 🎯 Critical Tests (Must Do)

These are the MOST IMPORTANT tests for Week 6:

1. ✅ **Test 1:** Database Creation - MUST VERIFY
2. ✅ **Test 2:** No Crash with Empty DB - MUST VERIFY
3. ✅ **Test 3:** Existing Features Work - MUST VERIFY
4. ⚠️ **Test 4:** Memory Usage - IMPORTANT
5. ⚠️ **Test 6:** Logcat Check - IMPORTANT

---

## 📝 Testing Notes

### What Changed in Week 6?
- Added Room Database dependencies
- Created 3 entities (Channel, Category, Favorite)
- Created 3 DAOs with database operations
- Added Hilt DI providers for database

### What Should NOT Change?
- App launch behavior
- Live TV functionality
- Movies functionality
- Series functionality
- Memory usage
- Performance

### Red Flags to Watch For:
- ❌ App crashes on launch
- ❌ OutOfMemoryError
- ❌ Database locked errors
- ❌ SQLiteException errors
- ❌ Excessive memory usage
- ❌ Slow app performance

---

## 🔍 Advanced Testing (Optional)

### Database Inspection
```bash
# Pull database to local machine
adb shell "run-as com.tvonnet.debridxtreamiptv cat databases/debrid_xtream_db" > /tmp/debrid_xtream_db

# Inspect with sqlite3
sqlite3 /tmp/debrid_xtream_db
.tables
.schema channels
.schema categories
.schema favorites
```

### Thread Analysis
```bash
# Check for database deadlocks
adb shell "run-as com.tvonnet.debridxtreamiptv cat /proc/$(adb shell pidof com.tvonnet.debridxtreamiptv)/status" | grep -E "Threads|voluntary"
```

---

## ✅ Sign-Off

- [ ] All critical tests passed
- [ ] No crashes observed
- [ ] No memory leaks
- [ ] Performance is acceptable
- [ ] Database is working correctly

**Tested By:** ________________  
**Date:** ________________  
**Approval:** ⬜ PASS | ⬜ FAIL

---

## 📞 Quick Commands Reference

```bash
# Connect
adb connect 192.168.0.54:5555

# Check app
adb shell pm list packages | grep tvonnet

# Launch app
adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity

# Force stop
adb shell am force-stop com.tvonnet.debridxtreamiptv

# Check database
adb shell "run-as com.tvonnet.debridxtreamiptv ls -lh databases/"

# Memory check
adb shell dumpsys meminfo com.tvonnet.debridxtreamiptv | grep TOTAL

# Logcat
adb logcat -d | grep -E "(Room|Database|tvonnet|FATAL)"
```

---

**Document Version:** 1.0  
**Last Updated:** November 2, 2025

