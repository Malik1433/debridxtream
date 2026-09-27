# Live: HLS "Auto" fallback — design

**Status:** DESIGN. Owner ki approval chahiye. Yeh Live playback ka landmine area hai (zap, ek
connection, shared player hand-off, TS LiveConfiguration), is liye har phase alag commit mein
aayega, aur har phase ke baad Fire TV par alag QA hogi (CLAUDE.md).
**Tareekh:** 2026-09-27 · **Pichla qadam:** match mode (`95dcf09`), yani overloaded channel band
nahi hota, dobara try hota hai.

---

## 1. Masla

Bade match ki raat provider ka server overload hota hai. Aaj har Live channel `.ts` par chalta hai
(`HomeScreenModels.kt:206`, `…/live/<user>/<pass>/<id>.ts`). `.ts` live stream server
**real-time raftaar** se bhejta hai, is liye:

- client aage ka buffer nahi bana sakta. Buffer utna hi hota hai jitna server connect par
  ek dafa aage bhej de. `PlayerBufferConfigFactory` mein Live ki 30 s ki had is liye kaagaz par hai;
- server ki raftaar real-time se neeche jaye to buffer foran khali hota hai aur picture atak jati hai;
- ek TCP connection toote to poori stream dobara shuru hoti hai.

Xtream providers aam taur par wahi channel **HLS** (`…/live/<user>/<pass>/<id>.m3u8`) mein bhi dete
hain. HLS mein stream chhote segments (aksar 2–10 s) mein aati hai:

- har segment alag download hota hai, aur server ki bandwidth ho to real-time se tez;
- ek segment fail ho to sirf wahi dobara mangta hai, poori stream nahi;
- player **live se 20–30 s peeche** chal sakta hai, aur yahi zakheera server ki chhoti
  rukawaton ko chhupa leta hai.

Nuqsan bhi hain: pehli picture thodi der se aati hai (zap 1–3 s slow), live edge se zyada peeche
chalte hain, aur kuch providers ka HLS unke TS se kharab hota hai. Is liye **hamesha HLS nahi,
balki "Auto"**: normal taur par TS, aur sirf usi channel ke liye HLS jo waqai atak raha ho.

## 2. Pehle se kya mojood hai (jin par yeh banega)

| Cheez | Kahan |
|---|---|
| Provider kaun se formats deta hai | `user_info.allowed_output_formats` (`XtreamModels.kt:23`). Parse hota hai, lekin **kahin istemal nahi hota aur save bhi nahi hota** |
| "Yeh feed load mein hai, internet theek hai" ka faisla | `StreamHealthMonitor` → `StreamHealth.CHANNEL_SLOW` → `actOnStreamHealth()` (BasePlayerFragment) |
| Usi channel ki doosri feed par jana | `tryLiveAlternateSource()` / `LiveAlternateSources` |
| Connection toote bina URL badalna | `PlayerLiveTuner.performSeamlessSwitch(url)` |
| HLS par live-offset aur speed control | `PlayerMediaItemFactory`: `LiveConfiguration` **sirf** m3u8/mpd par (TS par yeh landmine hai: first frame par freeze). Yeh qaida waisa hi rahega |
| Naapna | `RecoveryScoreboard` aur `qoe session … rebuffer_permille` (mode=live) |

## 3. Design

### 3.1 Provider ki salahiyat yaad rakhna
- Login/sync par `allowed_output_formats` ko **server-scoped prefs** mein save karna (`ServerScopedPrefs`).
- ⚠️ CLAUDE.md "server-switch contract": yeh per-provider data hai, is liye `ServerDataReset.purge`
  aur `ServerDataResetTest` mein **lazmi** add hoga.
- Field khali ho ya na mile to "maloom nahi" maana jayega. HLS ek dafa aazmaya jayega, aur agar
  pehle hi request par 4xx aaye to us provider ke liye band kar diya jayega.

### 3.2 URL banana (ek hi jagah)
- `toLiveStreamUrl(serverUrl, user, pass, format)`: `.ts` ya `.m3u8`. Dono jagah
  (`HomeScreenModels.kt:206/251`) yahi function istemal karein.
- **Identity URL nahi, stream id hai** (CLAUDE.md "Never store an absolute stream URL as identity").
  Format sirf URL banate waqt lagta hai.

### 3.3 Kab HLS par jana (Auto)
Nayi pure class **`LiveFormatPolicy`**, unit-tested:

| Halat | Faisla |
|---|---|
| Channel TS par hai, `CHANNEL_SLOW` aaya, provider m3u8 deta hai, aur is channel par HLS abhi aazmaya nahi gaya | **Pehle wahi feed HLS par** (`performSeamlessSwitch(m3u8Url)`). Doosri feed se pehle, kyunki yeh wahi channel aur wahi quality hai |
| HLS par bhi `CHANNEL_SLOW`, ya HLS ki pehli request fail | Maujooda raasta: doosri feed (`tryLiveAlternateSource`), phir match mode |
| HLS par chalne ke baad channel badla (zap) | Naya channel **TS** se shuru hoga, taake zap tez rahe. Neeche "yaad" dekhein |
| User ne Settings mein "TS" chuna | Kabhi HLS nahi |
| User ne Settings mein "HLS" chuna | Har channel HLS se shuru |

**Yaad (sirf is sitting mein):** jis channel ko HLS par jaana pada, woh is app session mein dobara
khule to seedha HLS par khule. Disk par save nahi hoga, is liye server switch par purge ka masla
nahi. Agar baad mein save karna ho to woh bhi `ServerDataReset` mein jayega.

### 3.4 HLS par zakheera
- HLS `MediaItem` par `LiveConfiguration.targetOffsetMs = 20_000` ("Auto" mein). Player live se
  ~20 s peeche chalega, aur speed control (0.97–1.03×) pehle se hai.
- Live buffer ki 30 s ki had HLS par **45 s** tak badhai ja sakti hai (low-RAM par 25 s hi rahe).
  TS par had wahi rahegi.

### 3.5 Ek connection wale accounts (`max_connections=1`)
- Format badalna **usi player par** `performSeamlessSwitch` se hoga. Is se purana TS connection
  band hota hai aur tab naya HLS request jata hai. Kabhi do connection ek saath nahi khulenge.
- Guide ka preview TS par ho aur fullscreen HLS chahe, to `LiveSharedPlayer.adopt()` URL match
  nahi karega (`adopt: url mismatch`) aur doosra connection khul jayega. **Qaida:** fullscreen hamesha
  preview wala URL adopt kare, aur HLS ka faisla sirf fullscreen mein `CHANNEL_SLOW` ke baad ho.
  Yaani guide aur hand-off ka raasta bilkul nahi badlega.

### 3.6 Settings
- Settings → Playback → **"Live stream format": Auto (default) · TS · HLS**.
- Phone (`values/`) aur TV (`values-television/`) dono par, har zabaan mein string.
- TV par D-pad se pahunch; phone par 48dp.

### 3.7 Naapna
- Scoreboard mein naya kind `live_hls_switch`: kitni baar HLS par gaye, aur kitni baar picture
  wapas aayi.
- `qoe session` mein `mode=live_ts` / `live_hls`, taake agli match raat dono ka `rebuffer_permille`
  alag dikhe. Isi number se tay hoga ke Auto ka default sahi hai ya nahi.

## 4. Phases (har ek alag commit, alag QA)

| Phase | Kya | Risk |
|---|---|---|
| **H0** | Provider check (neeche). Koi code nahi | — |
| **H1** | `allowed_output_formats` save + `ServerDataReset` + test, aur `toLiveStreamUrl(format)` (behaviour zero: sab TS) | Low |
| **H2** | `LiveFormatPolicy` + `CHANNEL_SLOW` par same-feed HLS switch + sitting memory + scoreboard/qoe mode | **Medium–High** (Live landmine) |
| **H3** | HLS par `targetOffsetMs` 20 s + buffer 45 s | Medium |
| **H4** | Settings mein format ka intekhab | Low–Medium (UI, dono rulebooks) |

## 5. H0 — pehle yeh check (local Claude, TV ki zaroorat nahi, sirf network)

Credentials report mein **na** aayein.
1. `player_api.php` (login) ke jawab mein `user_info.allowed_output_formats` kya hai? Maslan `["m3u8","ts"]`.
2. Ek chalte channel ka `.m3u8` URL `curl -sI` karein: status code kya hai, redirect kahan jata hai,
   aur playlist mein segment ki lambai (`#EXT-X-TARGETDURATION`) aur ginti kitni hai.
3. Wahi channel 60 s tak `.m3u8` par (`ffprobe` ya `curl` se segments) aur `.ts` par chala kar dekhein
   ke dono chalte hain.

Agar provider m3u8 **nahi** deta, to yeh poora design bekaar hai, aur hamara zor match mode, doosri
feed aur catch-up par rahega.

## 6. QA (har phase ke baad, Fire TV .64, release build)

1. `scripts/landmine_check.sh`: L1–L7 PASS rehne chahiye, khaas kar **L7 (Live hand-off, ek connection)**.
2. Zap ×10 tez rahe (TTFF pehle jaisa), kyunki naya channel TS se shuru hota hai.
3. Ek atakne wale channel par (ya network throttle karke) `CHANNEL_SLOW` → HLS switch → picture
   chalti rahe, aur `live_hls_switch` ok gina jaye.
4. `max_connections=1` account par: switch ke dauran provider se 403 **nahi** aana chahiye.
5. Settings "TS" par kabhi HLS nahi.

## 7. Owner ke faisle

1. H0 check chalwayein? (Mashwara: haan, pehle yahi.)
2. Default "Auto" theek hai? Ya shuru mein default "TS" rakh kar Auto ko sirf Settings se chalu karein?
3. HLS par live se 20 s peeche chalna qubool hai? Match ka goal ~20 s der se dikhega. Yahi asal
   zakheera hai.
