# F1 — VOD ~7 s chal kar PAUSED par ruk jata hai: design

**Status:** DESIGN — owner approval chahiye. Player/playback landmine area hai; koi code is doc ke
saath nahi aaya. Har phase alag commit, alag Fire TV QA (CLAUDE.md: "UI / playback changes require
device QA on the Fire TV").
**Tareekh:** 2026-09-26 · **Device:** AFTGAZL (Fire OS 7.7.1.6, Android 9) at 192.168.178.64
**Source:** on-device QA of branch `claude/gifted-einstein-5mdvd2` (report ke F1 section se).

---

## 1. Kya dekha gaya (facts)

Title: Continue Watching → `7.Dogs.2026.1080p.CAMRip.LAT.DUB.1XBET.mp4` (debrid, DIRECT path).
Teen baar reproduce hua:

| Run | Nateeja |
|---|---|
| 20:22 | release par `TotalVideoPlaybackTimeMs = 7215` |
| 20:30 | `TotalVideoPlaybackTimeMs = 7549` (player ~112 s khula raha) |
| 20:38 | MediaSession `state=2` (PAUSED), `position=247588` — t+25 s aur t+65 s par bilkul same |

- Stream **shuru hoti hai**: `Rendering first frame`, `1920x800`, `Frame-rate match 23.976 -> 50 Hz`.
- Saath mein 3× `W/AudioTrack: dead IAudioTrack, PCM, creating a new one from getPosition()`, aur
  audio HAL `Standby: yes`, `Frames written: 0`.
- OSD: `PAUSED · 04:08 · 1:51:21 remaining · 1080p · STEREO, 0.22 MBPS`.
- Usi session mein Lanterns S01E06 (50,384 ms) aur Kattalan (tunneled HEVC, 55,760 ms) poora real-time chale.

## 2. QA report ki diagnosis durust nahi — yeh ahem hai

QA report ne kaha tha "source sirf 0.22 Mbps de raha hai, buffer khatam hua, player PAUSED par baith gaya".
Code is ki tardeed karta hai:

1. **`STEREO, 0.22 MBPS` network speed nahi hai.** Yeh `PlayerVodControlsUi.selectedTrackLabel()` hai:
   selected **audio track** ka naam, jo Media3 ka `DefaultTrackNameProvider` banata hai — yaani
   "stereo AAC, 220 kbps audio". Is ka download speed se koi taluq nahi.
2. **"PAUSED" = `playWhenReady == false`.** `updateStatusChrome()` sirf `p.playWhenReady` dekhta hai;
   MediaSession `STATE_PAUSED` bhi sirf `READY && !playWhenReady` par aata hai
   (`PlayerMediaSessionManager.playbackStateFor`). Bhooka (starved) stream **BUFFERING** dikhata,
   PAUSED nahi.
3. **Asli slow source ke liye failover pehle se hai.** BUFFERING par `PlayerEventListener` watchdog
   (`timeoutRunnable` → `PlayerRecoveryController.handleTimeout()`) arm karta hai, jo debrid par
   `finishWithReturnToSources(autoPlayNext = true)` — yaani **agla source** — chalata hai.

**Nateeja:** kisi cheez ne player ko **pause kiya**. Aur pause ho jane ke baad app ka koi watchdog
dekhta hi nahi:
- `PlayerStallDetector.onTick` → `!isActivelyPlaying` par IDLE + reset.
- `checkFastWedge()` → `!p.playWhenReady` par return.
- BUFFERING watchdog → state READY hai, arm hi nahi hota.

Is liye player hamesha ke liye PAUSED par khada rehta hai, bina kisi message ke. **Masla "slow source"
nahi, "anjaan pause jise koi nahi pakadta" hai.**

## 3. Pause kahan se aa sakta hai (code mein har rasta)

| # | Rasta | Code | Is case mein kitna mumkin |
|---|---|---|---|
| A | `AUDIO_BECOMING_NOISY` | `PlayerEngineFactory`: `setHandleAudioBecomingNoisy(true)` | **Zyada.** Yeh headphone nikalne ke liye hai. Fire TV par HDMI audio route badle to yeh broadcast aa sakta hai, aur `dead IAudioTrack` ×3 route/AudioFlinger churn ki taraf ishara karta hai |
| B | Audio focus permanent loss | `setAudioAttributes(..., handleAudioFocus = true)` | Mumkin. Koi aur app/system (Amazon ka `MRMMediaSessionPlayer` usi waqt `F/` lines de raha tha) focus le le |
| C | MediaSession `onPause`/`onStop` | `PlayerMediaSessionManager` L126–127 | Mumkin. System/Alexa/HDMI-CEC ka "pause" |
| D | Fragment `onPause` | `BasePlayerFragment.onPause()` → `player?.pause()` + `stopStallMonitor()` | Mumkin. Koi system overlay/activity upar aa jaye |
| E | User key (CENTER / PLAY_PAUSE) | `PlayerInputRouter` / transport | QA script ke key presses. Sirf teen runs ka ek jaisa ~7.5 s timing isse kam mumkin banata hai |
| F | `PlayerTrackManager` L244 | `playWhenReady=false→true` toggle | **Nahi.** Sirf live (`isCurrentMediaItemDynamic`) ke liye, aur foran true bhi karta hai |

**Sab se mazboot shak (A/B), aur landmine se rishta:** yeh file **stereo PCM** audio chala rahi thi, jabke
theek chalne wali Kattalan tunneled/passthrough thi. Stereo PCM ka primary HDMI mixer is Fire TV par
pehle se jaana-pehchana landmine hai (`AudioWedgeEscape`, 2026-08-27 aur 2026-09-15 captures). `dead
IAudioTrack` usi khandan ki alamat hai. Yaani F1 bohot mumkin hai ke **audio wedge family ka naya roop**
ho: AudioTrack marta hai → route churn → NOISY/focus event → Media3 player ko pause kar deta hai → wedge
escape kabhi nahi chalta (woh `playWhenReady` maangta hai).

Yeh abhi **hypothesis** hai. Is liye design pehle wajah napta hai, phir fix karta hai.

---

## 4. Design — teen phases

### Phase 0 — wajah napna (sirf diagnostics, behaviour zero change) · Risk: LOW

Maqsad: agli reproduction mein ek line bataye ke **kis ne** pause kiya.

1. `PlayerEventListener.onPlayWhenReadyChanged(playWhenReady, reason)` mein, jab `!playWhenReady`:
   `Log.i("PlayerActivity", "playWhenReady=false reason=<name> pos=<ms> state=<state>")` aur
   `PlaybackDiagnosticsRecorder.record("play_when_ready_false", fields + reason)`.
   Reason names: USER_REQUEST(1), AUDIO_FOCUS_LOSS(2), AUDIO_BECOMING_NOISY(3), REMOTE(4),
   END_OF_MEDIA_ITEM(5), SUPPRESSED_TOO_LONG(6).
2. `onPlaybackSuppressionReasonChanged(reason)` override: log + diagnostics.
3. `PlayerMediaSessionManager` `onPause`/`onStop`: pehle log karein ke session command aayi
   (`remote_pause`), taake rasta C baqi rasto se alag dikhe.
4. `BasePlayerFragment.onPause`: `Log.i(... "fragment onPause, wasPlaying=...")`.
5. App ke apne user-pause (transport button / key) ko ek line mein tag karein (`user_pause source=key|button`),
   taake E ko A–D se alag kiya ja sake.

Tests: `PlayerEventListener` mein reason→name mapping ek pure function (`playWhenReadyReasonName(int)`)
banayein aur uska unit test.
QA (Fire TV .64): wahi title kholein, **player khulne ke baad koi key na dabayein**, 90 s logcat.
Output: ek line — "reason=AUDIO_BECOMING_NOISY" (ya jo bhi). Isi ke baad Phase 1 ki shakal tay hogi.

### Phase 1 — wajah ke hisab se jadd (root-cause) fix · Risk: MEDIUM · owner approval per branch

Phase 0 ka nateeja in mein se ek rasta chunega. Har rasta alag commit:

- **A (NOISY):** TV form factor par becoming-noisy handling **band** karein
  (`setHandleAudioBecomingNoisy(!isTelevision)`). TV par headphone-unplug ka concept nahi, aur HDMI
  route churn ko "user ne headphone nikala" samajhna ghalat hai. Phone par yeh sahi behaviour hai, wahan
  wahi rahe (CLAUDE.md: do platform, do rulebook).
- **B (focus loss):** kaun focus le raha hai woh log karein (`AudioManager` focus stack via
  `dumpsys audio`). Agar system component (Alexa/MRM) hai jo wapas nahi deta, to **permanent loss par
  pause ki jagah "duck/resume on regain"** policy — magar sirf TV par, aur sirf VOD.
- **C (REMOTE):** caller package log karein; agar CEC/TV remote hai to yeh jaiz pause hai — kuch na
  badlein, sirf Phase 2 ka message dikhayein.
- **D (fragment onPause):** jo overlay/activity upar aayi usay dhoondein; `onResume` mein
  `wasPlayingBeforePause` se resume ho raha hai ya nahi, verify karein.
- **E (user key):** koi product bug nahi; QA harness ka artefact. Report mein band karein.

Agar Phase 0 `dead IAudioTrack` ke saath rasta A/B dikhaye, to `AudioWedgeEscape` ke owner-notes ke saath
milayein — shayad escape ka trigger `playWhenReady` ke bajaye "audio track died + position frozen" par bhi
hona chahiye. Yeh landmine code hai: verbatim-first, alag commit, alag QA.

### Phase 2 — "anjaan pause" ki hifazat (safety net) · Risk: MEDIUM

Root-cause fix ke bawajood, koi bhi **gair-user** pause chup-chaap hamesha ke liye nahi rehna chahiye.

- Naya collaborator **`PlayerUnexpectedPauseGuard`** (`player/stabilized/`, `Player*Manager`/`*Controller`
  pattern ke mutabiq, pure decision logic + unit test). Input: `playWhenReady` change + reason + kya app ne
  khud pause kiya (user key/button) + content type. Output: `IGNORE | RESUME_ONCE | SHOW_PAUSED_HINT`.
- Qaida:
  - USER_REQUEST jo **hamare** key/button se aaya → IGNORE (user ne pause kiya).
  - USER_REQUEST jo hamari taraf se nahi aaya, ya NOISY/FOCUS_LOSS (TV par) → **ek baar** auto-resume
    (2 s baad, sirf tab jab activity RESUMED ho aur player wahi ho). Dobara 60 s ke andar ho to
    resume nahi — **message dikhayein**: "Playback system ne rok diya — OK dabayein" (string
    `values/` + `values-television/`, 5 zabanon mein).
  - REMOTE / END_OF_MEDIA_ITEM → IGNORE.
- Live TV par yeh guard **off** (live ki apni recovery hai; zap landmines na chhedein).
- `PlayerStallMonitor` ko chhua nahi jayega (PlayerNetworkStallManager wala scope BLOCKED/approval-gated hai).
  Guard alag collaborator hai jo sirf `onPlayWhenReadyChanged` se chalta hai.

### Phase 3 — (sirf agar Phase 0 asli bhook dikhaye) READY-starve failover · Risk: HIGH · gated

Agar Phase 0 mein pause ki jagah lamba **READY-but-position-frozen with playWhenReady=true** mile jo
`STALLED` tak na pahunche, to tab hi `readyStallRequiredStrikes` / debrid strike policy par baat hogi. Yeh
§8 wala stall scope hai — **alag, explicit owner approval ke baghair shuru nahi hoga.** Abhi ke saboot is
taraf ishara nahi karte.

---

## 5. Kya nahi badlega

- `PlayerStallDetector`, `PlayerStallMonitor`, `AudioWedgeEscape` ke thresholds aur reactions — Phase 0–2 mein nahi.
- Live zap, shared-player hand-off, tunneling-off recovery — koi taluq nahi.
- Phone ka behaviour — sirf Phase 1-A mein TV/phone split, aur phone wahi rahega.

## 6. QA har phase ke liye (Fire TV .64, release build)

1. `scripts/landmine_check.sh` — L1–L7 PASS rehne chahiye.
2. F1 title: 3 runs, **player khulne ke baad koi key nahi**; nateeja `TotalVideoPlaybackTimeMs` release par
   (≥ 60 s = theek) — MediaCodecLogger aur SurfaceFlinger yahan kaam nahi karte (QA report ke measurement
   notes).
3. Control titles: Lanterns + Kattalan wahi real-time rahein.
4. Phase 2: user ka apna pause (OK / PLAY_PAUSE) pause hi rahe — kabhi auto-resume na ho. Yeh sab se
   ahem regression check hai.
5. Phase 1-A ke baad: phone par headphone/BT disconnect ab bhi pause kare (phone rulebook).

## 7. Faisle jo owner ke hain

1. Phase 0 shuru karein? (behaviour change zero — mashwara: haan)
2. Phase 2 mein TV par gair-user pause ke baad **ek auto-resume** theek hai, ya sirf message?
3. Phase 3 abhi band rahe — agree?
