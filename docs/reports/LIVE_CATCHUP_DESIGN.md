# Live catch-up (timeshift): design

**Status:** DESIGN. Owner ki approval chahiye. Yeh Live playback ka landmine area hai (ek connection,
zap, shared player). Har phase alag commit mein aayega, aur har phase ke baad Fire TV par alag QA
hogi (CLAUDE.md).
**Tareekh:** 2026-09-27. **Pehle ke qadam:** match mode (`95dcf09`), yani overloaded channel
dobara aazmaya jata hai; halki feed pehle (`ddef31c`).

---

## 1. Provider ne kya dikhaya (network checks, 2026-09-27)

| Cheez | Nateeja |
|---|---|
| Archive wale channels | 17,704 mein se **1,735** (`tv_archive=1`), sab ka `tv_archive_duration` = **2 din** |
| Un mein sports | **182** (TR/IT/beIN/NL/PL sport, Eurosport 1/2, Canal+ Sport, DD Sports, Ziggo Sport 4 …) |
| Timeshift endpoint | `/timeshift/<u>/<p>/<min>/<YYYY-MM-DD:HH-MM>/<id>.ts` → 302 (token edge) → **200**. `streaming/timeshift.php` bhi 200 |
| Raftaar | Recording **poori line speed** par aati hai (~40 Mbps), real-time par nahi. 10 minute chand second mein aa jate hain |
| Seek | `Range` honour hota hai (206) |
| EPG | `get_simple_data_table` har programme par `has_archive` deta hai |
| ⚠️ Per-channel | Kuch `tv_archive=1` channels har koshish par **503** dete hain (misaal: `|SP| DAZN 2 HD`), jabke unka live chalta hai |
| ⚠️ Unverified | Pehli bytes 188-byte TS par aligned nahi lagin. Fire TV par ek play test zaroori hai |
| Waqt | `server_info.time_now` / timezone (Europe/Amsterdam). Start time **server ke waqt** mein bhejna hota hai |

### C0: Fire TV par nateeje (2026-09-27)

- **Chalta hai:** `|HINDI| DD SPORTS` ki recording par hardware decode (AVC 1920x1080) hua aur audio
  bhi chala. Test player VLC tha, jo baad mein uninstall kar diya gaya. **4 min 44 s bina toote**
  chala. Picture aur awaz saaf hai ya nahi, yeh owner ki aankh se confirm hona baqi hai.
- **Seek:** protocol poora support karta hai. `Content-Length` milta hai, beech ke offset par `206`
  aata hai, aur wahan ke bytes **valid TS** hain (sync lock, 1063/1063 packets aligned, PAT).
  Recording byte 0 se aligned nahi, lekin TS hai. Pichla khula sawal isi se band hua.
- ⚠️ **`duration` nahi mana jata.** Server recording ko **~10 minute ke chunks** mein bhejta hai.
  5 min maango ya 30 min, jawab mein woh ~10 min ka chunk aata hai jis mein `start` ka waqt ho.
- **Chalta hua chunk badhta hai.** "Ab" wala chunk pehle recorded hissa tezi se (burst) bhejta hai,
  phir **real-time** (2.43 Mbps) par aage badhta hai, aur chunk ki had par khatam ho jata hai.
  Ek test mein 6 min 45 s par khatam hua, taqreeban 10 min ka content.

**Design par asar:** har catch-up playback **chunks ki zanjeer** (`CatchupChunkChain`) hogi. Ek
chunk `ENDED` ho to agla chunk mangna hai, `start = pichle chunk ka aakhri waqt`. Timeshift minute
se neeche granularity nahi deta, is liye jod par kuch second ka hissa do dafa aa sakta hai. Us ko
naye chunk ke andar seek karke chhodna hoga (`Range`/TS seek). Yeh sab se nazuk hissa hai, aur C2 mein
hi pehli dafa saabit karna hoga ke jod be-jhatke hai. C4 (delayed live) bhi isi zanjeer par chalega:
2 min peeche chalne ka matlab hai ke agla chunk hamesha pehle se recorded hoga aur burst mein aayega.
Yahi 2 min ka zakheera hai. Is liye C4 ab mumkin lagta hai, lekin jod ke be-jhatke hone par tikka hai.

## 2. Yeh match ki raat kyun ahem hai

Live `.ts` real-time raftaar par aata hai, is liye aage ka buffer nahi banta. **Catch-up recording
line speed par aati hai.** Is liye player minton ka buffer jama kar sakta hai, aur server ki chhoti
rukawatein nazar hi nahi aatin. Jis channel ka archive ho, us par "thoda peeche dekhna" asli
anti-buffer ban jata hai. HLS ki jagah yahi hamara zakheera ho sakta hai, kyunki yeh provider HLS
nahi deta.

## 3. Features, tarjeeh ke hisab se

### C2: "Jahan ruka tha wahan se" (outage rewind) ⭐
- Live channel (`tv_archive=1`) atka. Match mode ne use wapas chala diya, **ya** atakne ke dauran hi
  (dono mauqe).
- Player waqt yaad rakhta hai ke **picture kab ruki thi** (`lastGoodPlaybackWallClock`).
- Wapas aane par ek chhota, D-pad/touch se chalne wala prompt: **"Jahan ruka tha wahan se dekhein
  (3 min peeche) · Live par rahein"**. Default "Live", aur 8 s mein khud hat jata hai.
- "Wahan se" chunne par `timeshift(start = rukne ka waqt − 30 s, duration = ab tak + 5 min)`.
  Recording khatam hone par app khud **live par wapas** aati hai, aur toast dikhata hai.
- Sirf un channels par jahan archive ho aur us sitting mein 503 na aaya ho.

### C3: "Shuru se dekhein" (start over)
- Live OSD / EPG strip mein chalte programme par (agar `has_archive` ya `tv_archive=1` ho):
  timeshift(start = programme ka start). Khatam hone par live.

### C4: "Match safe mode" (delayed live), sirf agar C0 saabit kare
- Channel ko jaan boojh kar **~2 min peeche** timeshift se chalana, aur rolling windows se aage
  badhte rehna, taake player ke paas hamesha 1–2 min ka zakheera rahe.
- Pehle C0 mein naapna hoga ke "abhi tak chal rahi" recording badhti rehti hai ya band ho jati hai,
  aur windows ke beech jod (stitch) be-jhatke ho sakta hai ya nahi. Na ho sake to C4 cancel.

### C5: EPG catch-up (pichle 2 din)
- Guide mein guzre hue programme par `has_archive=1` ho to chalana. Yeh aam catch-up hai jo TiviMate
  mein bhi hai.
- Bada UI kaam hai, aur dono rulebook lagte hain: TV par D-pad ladder, phone par touch.

## 4. Technical design

- **`CatchupUrlBuilder` (pure):** `timeshift(server, user, pass, streamId, startServerTime, minutes)`.
  Start ko **server timezone** mein format karna hai (`YYYY-MM-DD:HH-MM`). Minute se neeche granularity
  nahi hai, is liye seek ko bhi seconds tak recording ke andar karna hoga. Credentials URL mein sirf
  bante waqt jayenge. **Identity stream id aur waqt hai, URL nahi** (CLAUDE.md).
- **Server waqt:** login/sync par `server_info.timezone` save karna, server-scoped. ⚠️ Yeh per-provider
  data hai, is liye `ServerDataReset.purge` aur `ServerDataResetTest` mein lazmi jayega (server-switch
  contract).
- **`CatchupAvailability` (pure):** `tv_archive`, programme ka `has_archive`, archive ki umar (2 din),
  aur is sitting mein us channel par 503 aaya ya nahi. Is se tay hota hai ke button dikhe ya na dikhe.
  Ek dafa 503 aaye to us channel par catch-up ke options is sitting mein chhup jayenge, aur toast
  bataye ga ke "is channel ki recording dastiyab nahi".
- **Ek connection (`max_connections=1`):** live se catch-up aur catch-up se live, dono switch **usi
  player par** `performSeamlessSwitch` se honge. Is se purana connection band hota hai aur tab naya
  khulta hai. Guide ka preview aur `LiveSharedPlayer.adopt()` ka raasta nahi badlega. Catch-up sirf
  fullscreen mein.
- **Media item:** catch-up recording VOD jaisi hai (duration maloom, seekable). Is liye
  `LiveConfiguration` **nahi** lagegi. TS par yeh landmine hai, us ka qaida waisa hi rahega.
  Buffer VOD wala hoga (line speed ka faida).
- **Zap:** catch-up ke dauran channel badla to naya channel **live** se shuru hoga.
- **Naapna:** scoreboard mein `catchup_resume` aur `catchup_start_over`. `qoe session` mein
  `mode=live_catchup`, taake is ka `rebuffer_permille` live se alag dikhe.

## 5. Phases (har ek alag commit, alag QA)

| Phase | Kya | Risk |
|---|---|---|
| **C0** | ✅ Ho gaya (upar "C0 nateeje"): decode theek hai, seek protocol theek hai, 10-min chunks, chalta chunk real-time badhta hai | — |
| **C1** | `CatchupUrlBuilder` + server timezone save (+ `ServerDataReset` + test) + `CatchupAvailability` | Low |
| **C2** | Outage rewind prompt + `CatchupChunkChain` (chunk khatam ho to agla chunk, jod par seek) | Medium–High (Live landmine, jod) |
| **C3** | Start over | Medium |
| **C4** | Match safe mode (sirf agar C0 haan kahe) | High |
| **C5** | EPG catch-up UI | Medium (UI, dono rulebooks) |

## 6. QA (har phase ke baad, Fire TV .64, release build)

1. `scripts/landmine_check.sh`: L1–L7 PASS, khaas kar **L7 (Live hand-off, ek connection)**.
2. Archive channel par network ~1 min kaat kar wapas lagayein. Prompt aaye, "wahan se" chunne par
   ruka hua hissa chale, aur recording ke baad khud live par aa jaye.
3. `max_connections=1` account par kisi switch mein provider ka 403 **nahi** aana chahiye.
4. 503 wale channel (maslan `|SP| DAZN 2 HD`) par: option chhup jaye, sirf ek saaf toast aaye, aur
   player live par hi rahe.
5. Zap ×10 tez rahe, aur catch-up ke dauran zap karne par naya channel live se khule.

## 7. Owner ke faisle

1. ~~C0 chalwayein?~~ Ho gaya.
2. Tarjeeh C2 → C3 → C4 → C5 theek hai?
3. C2 ka prompt default "Live par rahein" rakhein, ya default "jahan ruka tha wahan se"?
