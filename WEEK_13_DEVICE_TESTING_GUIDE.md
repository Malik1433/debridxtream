# 📱 WEEK 13: DEVICE TESTING GUIDE

**Date:** November 5, 2025  
**Device:** 192.168.0.54:5555 (Android TV)  
**APK:** app-debug.apk (11MB)  
**Status:** ✅ Installing...

---

## 🎯 TEST OBJECTIVES

### Week 13 Features to Test:

1. **✅ Task 1: VOD/Series Favorite Indicators**
   - VOD mein heart icons dikhte hain?
   - Series mein heart icons dikhte hain?
   - Long-press se add/remove kaam karta hai?
   - Instant updates hote hain?

2. **✅ Task 2: EPG Background Refresh**
   - EPG data load hota hai?
   - Background sync schedule hai?
   - Auto-cleanup kaam karta hai?

---

## 📋 COMPREHENSIVE TEST CHECKLIST

### 🔐 **Test 1: Login & Authentication**
```
[ ] Login screen properly loads
[ ] Credentials input kaam karta hai
[ ] Login successful hota hai
[ ] Home screen load hota hai
[ ] EPG auto-fetch trigger hota hai (check logs)
```

---

### 📺 **Test 2: Live TV**
```
[ ] Live TV categories load hote hain
[ ] Channels list properly display hoti hai
[ ] EPG "Now Playing" dikhta hai
[ ] EPG "Next" program dikhta hai
[ ] Heart icons favorited channels pe dikhte hain ❤️
[ ] Long-press add to favorites (new channel)
[ ] Long-press remove from favorites (favorited channel)
[ ] Toast messages show hote hain
[ ] Channel playback kaam karta hai
```

---

### 🎬 **Test 3: VOD (Movies) - NEW FEATURES!**
```
[ ] Movie categories load hote hain
[ ] Movies grid properly display hoti hai
[ ] Movie posters/thumbnails load hote hain
[ ] Heart icons favorited movies pe dikhte hain ❤️ (NEW!)
[ ] Long-press add to favorites (new movie) (NEW!)
[ ] Long-press remove from favorites (NEW!)
[ ] Toast messages show hote hain
[ ] Movie click se detail page khulta hai
[ ] Movie playback kaam karta hai
```

---

### 📺 **Test 4: Series - NEW FEATURES!**
```
[ ] Series categories load hote hain
[ ] Series grid properly display hoti hai
[ ] Series covers/thumbnails load hote hain
[ ] Heart icons favorited series pe dikhte hain ❤️ (NEW!)
[ ] Long-press add to favorites (new series) (NEW!)
[ ] Long-press remove from favorites (NEW!)
[ ] Toast messages show hote hain
[ ] Series click se message dikhta hai
```

---

### 💝 **Test 5: Favorites Screen**
```
[ ] Favorites screen opens
[ ] All favorited items display hote hain
[ ] Live TV favorites dikhai dete hain
[ ] VOD favorites dikhai dete hain
[ ] Series favorites dikhai dete hain
[ ] Proper names display hote hain (not stream IDs)
[ ] Thumbnails load hote hain
[ ] Type badges show hote hain (LIVE/VOD/SERIES)
[ ] Click se playback shuru hota hai
[ ] Long-press remove kaam karta hai
```

---

### 🔍 **Test 6: Search**
```
[ ] Search screen opens
[ ] Search input kaam karta hai
[ ] Live TV results milte hain
[ ] VOD results milte hain
[ ] Series results milte hain
[ ] Results click se play hota hai
[ ] Recent searches save hote hain
```

---

### 📅 **Test 7: EPG (Electronic Program Guide)**
```
[ ] EPG screen opens
[ ] Current programs show hote hain
[ ] Upcoming programs show hote hain
[ ] Timeline properly render hota hai
[ ] Scrolling smooth hai
[ ] Program details accurate hain
```

---

### ⚙️ **Test 8: Settings**
```
[ ] Settings screen opens
[ ] Refresh interval option hai
[ ] Manual refresh button kaam karta hai
[ ] Cache stats show hote hain
[ ] About section accessible hai
```

---

### 🔄 **Test 9: Background Sync (NEW!)**
```
[ ] Check logs: "EPG Background sync scheduled"
[ ] Check WorkManager status (adb logcat)
[ ] Trigger manual sync (if option available)
[ ] Verify EPG data updates
```

Expected logs:
```bash
adb logcat | grep -E "EpgSync|WorkManager"

# Should see:
# App: EPG Background sync scheduled
# EpgSyncScheduler: EPG Sync scheduled: Every 6 hours
```

---

### ⚡ **Test 10: Performance**
```
[ ] App launch time < 3 seconds
[ ] Smooth scrolling (60fps)
[ ] No ANR (Application Not Responding)
[ ] No crashes
[ ] Memory usage reasonable
[ ] Battery drain minimal
```

---

### 🎨 **Test 11: UI/UX**
```
[ ] All screens properly render
[ ] TV D-pad navigation kaam karta hai
[ ] Focus indicators visible hain
[ ] Colors consistent hain
[ ] Text readable hai
[ ] Icons properly display hote hain
[ ] Loading states show hote hain
[ ] Error states handle hote hain
```

---

## 🐛 KNOWN ISSUES TO VERIFY

### From Previous Weeks:
```
[ ] Database migration preserves data?
[ ] Favorites performance optimized? (O(1) lookups)
[ ] EPG cleanup working properly?
[ ] Network errors handled gracefully?
```

---

## 📊 PERFORMANCE METRICS TO COLLECT

### App Launch:
```
- Cold start time: ___ seconds
- Warm start time: ___ seconds
- Memory on launch: ___ MB
```

### Scrolling:
```
- Live TV list: Smooth? Yes/No
- VOD grid: Smooth? Yes/No
- Series grid: Smooth? Yes/No
- Favorites list: Smooth? Yes/No
```

### Operations:
```
- Add to favorites: ___ ms
- Remove from favorites: ___ ms
- Search query: ___ ms
- Category switch: ___ ms
```

---

## 🧪 STRESS TESTING

### Test Scenarios:

#### 1. Rapid Add/Remove Favorites
```
Action: Rapidly long-press 10 items
Expected: No crashes, UI stays responsive
Result: ___
```

#### 2. Long Scrolling Session
```
Action: Scroll through 100+ channels
Expected: Smooth 60fps, no memory leaks
Result: ___
```

#### 3. Background/Foreground Switch
```
Action: Home button → Return to app
Expected: State preserved, no crashes
Result: ___
```

#### 4. Network Interruption
```
Action: Disable Wi-Fi during playback
Expected: Error message, graceful degradation
Result: ___
```

---

## 📱 ADB COMMANDS FOR TESTING

### Install APK:
```bash
adb connect 192.168.0.54:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch App:
```bash
adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity
```

### View Logs:
```bash
# All logs
adb logcat | grep -E "DebridXtream|EPG|Favorite"

# EPG Sync logs
adb logcat | grep EpgSync

# WorkManager logs
adb logcat | grep WorkManager

# Crash logs
adb logcat | grep -E "FATAL|AndroidRuntime"
```

### Check Database:
```bash
adb shell run-as com.tvonnet.debridxtreamiptv
cd databases
ls -lh
```

### Force Stop App:
```bash
adb shell am force-stop com.tvonnet.debridxtreamiptv
```

### Clear App Data:
```bash
adb shell pm clear com.tvonnet.debridxtreamiptv
```

---

## ✅ ACCEPTANCE CRITERIA

### Must Pass (Critical):
```
✅ App launches without crashes
✅ Login works properly
✅ All screens accessible
✅ Playback functional
✅ Favorites add/remove works
✅ Heart icons display correctly (VOD/Series)
✅ EPG background sync scheduled
✅ No data loss
✅ No ANR/crashes
```

### Should Pass (Important):
```
✅ Smooth scrolling
✅ Fast operations (<500ms)
✅ Memory usage <200MB
✅ Battery efficient
✅ Error handling proper
✅ UI consistent
```

### Nice to Have (Optional):
```
✅ Animations smooth
✅ Transitions polish
✅ Advanced features working
```

---

## 📝 BUG REPORT TEMPLATE

If issues found:

```markdown
### Bug: [Title]

**Severity:** Critical/High/Medium/Low
**Screen:** [Which screen]
**Steps to Reproduce:**
1. 
2. 
3. 

**Expected:** 
**Actual:** 
**Logs:** 
**Screenshots:** 
```

---

## 🎯 TESTING PRIORITIES

### Priority 1 (Must Test):
1. ✅ VOD/Series heart icons
2. ✅ Long-press add/remove favorites
3. ✅ EPG background sync
4. ✅ Favorites screen functionality
5. ✅ Basic playback

### Priority 2 (Should Test):
6. ✅ Search functionality
7. ✅ EPG timeline
8. ✅ Performance metrics
9. ✅ Memory usage
10. ✅ Error handling

### Priority 3 (Nice to Test):
11. ✅ Advanced features
12. ✅ Edge cases
13. ✅ Stress testing
14. ✅ Long-term stability

---

## 📊 EXPECTED RESULTS

### Week 13 Features:

#### VOD/Series Favorites:
```
✅ Heart icons visible on favorited items
✅ Long-press adds to favorites
✅ Long-press removes from favorites
✅ Toast messages show feedback
✅ Instant UI updates
✅ Consistent with Live TV
```

#### EPG Background Sync:
```
✅ Scheduled on app launch
✅ Runs every 6 hours
✅ Network + Battery constraints
✅ Auto-cleanup old data
✅ Non-blocking operation
✅ Comprehensive logging
```

---

## 🎉 SUCCESS CRITERIA

**Week 13 Testing Complete when:**
```
✅ All Priority 1 tests passed
✅ 90%+ Priority 2 tests passed
✅ No critical bugs found
✅ Performance acceptable
✅ User experience smooth
✅ Documentation updated
```

---

## 📞 NEXT STEPS AFTER TESTING

### If All Tests Pass:
1. ✅ Mark device testing complete
2. ✅ Generate QA report
3. ✅ Document any minor issues
4. ✅ Plan improvements
5. ✅ Move to Week 14

### If Issues Found:
1. ⚠️ Document all bugs
2. ⚠️ Prioritize fixes
3. ⚠️ Fix critical issues
4. ⚠️ Re-test
5. ⚠️ Update documentation

---

**Created:** November 5, 2025  
**Week:** 13 of 16  
**Progress:** 40% → Testing Phase  
**Device:** 192.168.0.54:5555  
**APK:** 11MB (app-debug.apk)

**Ready to test! Sab features check karein! 🚀**

---

**END OF TESTING GUIDE**

