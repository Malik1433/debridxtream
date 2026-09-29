# Samsung + LG TV app (web): design

**Status:** DESIGN. Owner ne rasta manzoor kiya hai (2026-09-28): Samsung (Tizen) aur LG (webOS) ke liye
**ek web TV app**, aur Apple TV ke liye baad mein alag native app. Yeh doc sirf web TV app ka hai.
**Pehla qadam W0 hai** (neeche): asal TV par chhota sa proof. Us ke nateejay se pehle koi bada code nahi.

**Owner ke faisle (2026-09-28):** naam **DX Play**; pehla store version **sirf IPTV**. Test ke liye ghar
mein **Samsung** aur **VIDAA (Hisense)** TV hain, LG nahi. Is liye W0 Samsung + VIDAA par hoga, aur LG
tab jab LG TV mile. VIDAA bhi web TV hai, to wahi code chalega; W0 mein woh PC se chalne wale page
(`npm run vidaa`) ko TV ke browser mein khol kar test hota hai. Code: `tv-web/`.

---

## 0. W0 ke nateeje (VIDAA, 2026-09-29) ⭐

Samsung abhi baqi hai (Tizen Studio + owner ka Samsung certificate chahiye). VIDAA par, TV ke browser
mein LAN se khule page par:

| Sawal | Jawab |
|---|---|
| Browser | Chrome **111** (armv7l). 2018 wale Chromium 53–56 ka dar yahan nahi |
| **CORS** (sab se bada khatra) | ✅ **Masla nahi:** `player_api.php` seedha HTTP 200 (164–465 ms) browser se. Yeh provider CORS allow karta hai. Doosre providers ke liye proxy ka plan wahi rahe, lekin pehle din zaroori nahi |
| MSE | ✅ hai (`mpegts.js` chal sakta hai) |
| Firebase anonymous login | ✅ |
| **mpegts.js**, live `.ts`, 10 min | ✅ pehli picture 1.5 s, **0 rukawat**, 622 s chala. 5 zaps: 0.7–1.6 s. Awaz theek (DD SPORTS bhi) |
| Slow-fill 0.97x → 1.0x | ✅ `playbackRate` se chalta hai |
| TV ka apna `<video>` | Picture aur awaz chalte hain, **lekin us ke upar koi HTML overlay nazar nahi aata** |
| Device ID (`Hisense_GetDeviceID`) | ❌ browser mein nahi. VIDAA **app** (store/partner) mein dobara dekhna |
| Remote | Browser arrows aur CH+/− khud rakh leta hai, page ko sirf number keys milti hain. Asli VIDAA app mein keys app ko milni chahiyein, W1 mein dekhna |

**Faisle is se:**
1. **VIDAA (aur ghaliban LG) par player = `mpegts.js`.** TV ke `<video>` ke upar OSD, channel list aur
   "Connecting…" pill dikhte hi nahi, jabke `mpegts.js` ke upar sab dikhta hai.
2. **Samsung par AVPlay** abhi bhi pehla ummeedwar hai (woh video ko HTML ke *neeche* alag layer par
   chalata hai, jo overlay ka aam tareeqa hai). Lekin Samsung W0 mein **dono** (AVPlay aur `mpegts.js`)
   ke upar overlay dikhta hai ya nahi, yeh zaroor dekhna hai.
3. CORS ka proxy **pehle din nahi.** Sirf tab jab koi provider block kare.

## 1. Kya banana hai

Ek TypeScript web app jo Samsung aur LG ke store par **apne naam se** jaye, remote (D-pad) se chale,
aur wahi account/license istemal kare jo Android app karta hai. Code ek, packaging do:

| | Samsung | LG |
|---|---|---|
| Package | `.wgt` (Tizen Studio / `tizen` CLI) | `.ipk` (`ares-package`) |
| Store | Samsung Seller Office | LG Seller Lounge (Content Store) |
| Player | AVPlay (`webapis.avplay`) — ya `mpegts.js` (W0 tay karega) | `<video>` (webOS media) — ya `mpegts.js` |
| Kam az kam | Tizen 4.0 (2018 TV) | webOS 4.0 (2018 TV) |

2018 ke TVs ka browser purana hai (Chromium ~53–56), is liye build ES5 tak transpile hoga.

## 2. Mojooda system se kya dobara istemal hoga

| Cheez | Android mein kahan | Web app mein |
|---|---|---|
| License / trial / activation code | `LicenseManager`, Firestore `licenses/{deviceKey}` | **Wahi Firestore** (Firebase JS SDK). Sirf device ki pehchan alag (neeche §6) |
| QR se phone par setup | `LoginQrOverlayController`, `device_codes/{code}` | **Wahi flow.** TV par typing mushkil hai, is liye QR yahan aur bhi zaroori |
| Account ki playlists | `AccountPlaylistSync`, `device_auth`, `playlists` | Wahi |
| App config / addons | `app_config`, `addons` | Wahi |
| Admin panel, App Update | `admin-panel/` | Wahi panel. Update ka tareeqa store ka hoga, APK wala nahi |
| Xtream API | `XtreamApiService` (`player_api.php`, `xmltv.php`) | TypeScript mein dobara (seedha HTTP JSON) |
| Server-switch contract | `ServerDataReset`, `ServerIdentity` | **Wahi qaida:** provider badle to us ka sab data jaye (CLAUDE.md) |
| Anti-buffer qaide | `LiveCushionPolicy`, `LiveHoldOn`, `NetworkGrace` | Qaide wahi, player API alag (§5) |
| Zabaan wala auto-next | `DebridAutoNextPicker` | Phase W4 mein |
| Rukawat ka meter | `PlaybackInterruptionMeter` | Pehle din se, taake TV par bhi naap ho |

Pure Kotlin policies (patience, hold-on, auto-next, interruption meter) **line ba line TypeScript mein
port** hongi aur un ke unit tests bhi, taake dono platform ek jaisa bartaav karein.

## 3. Tech stack

- **TypeScript + Vite**, `@vitejs/plugin-legacy` se ES5 build.
- **UI:** React 18 + `@noriginmedia/norigin-spatial-navigation` (TV par D-pad focus ka mana hua hal).
  LG ka apna Enact bhi React hai, is liye yeh rasta dono par chalta hai.
- **State:** halki store (zustand). **Storage:** bara catalogue (17,000+ live channels) IndexedDB mein,
  chhoti settings localStorage mein.
- **Lists:** virtualised (sirf screen wali rows DOM mein) — purane TV par hazaron rows DOM mein app ko
  jama deti hain.
- **Firebase JS SDK** (modular, sirf Firestore + anonymous auth), App Check baad mein.
- **Tests:** Vitest (policies aur API parsers), asal TV par dast QA (CLAUDE.md: TV rulebook).

Folder: repo ki root par `tv-web/` (Android `app/` ko haath nahi lagega).

```
tv-web/
  src/
    platform/      tizen.ts, webos.ts, browser.ts  — keys, device id, exit, player adapter
    player/        PlayerAdapter.ts, AvplayAdapter.ts, VideoAdapter.ts, MpegtsAdapter.ts
    policy/        liveCushion.ts, liveHoldOn.ts, networkGrace.ts, interruptionMeter.ts (+ tests)
    api/           xtream.ts, epg.ts
    license/       license.ts, pairing.ts (Firestore)
    data/          catalogueStore.ts (IndexedDB), serverReset.ts
    ui/            screens/, components/, focus/
  packaging/       tizen/config.xml, webos/appinfo.json
```

## 4. Player adapter

Ek interface, har TV ka apna implementation:

```ts
interface PlayerAdapter {
  load(url: string, opts: { live: boolean }): void
  play(): void; pause(): void; stop(): void
  onState(cb: (s: 'idle'|'buffering'|'ready'|'ended') => void): void
  onError(cb: (e: { http?: number; network: boolean; message: string }) => void): void
  bufferedAheadMs(): number
  setSpeed?(rate: number): boolean      // slow-fill; false = yeh TV nahi kar sakta
  audioTracks(): Track[]; selectAudio(id: string): void
  subtitleTracks(): Track[]; selectSubtitle(id: string | null): void
  destroy(): void
}
```

- **AvplayAdapter (Samsung):** `webapis.avplay` — `.ts` live, HLS, MKV, HEVC hardware par. Buffer
  `setBufferingParam` se, speed `setSpeed` se (live par chalta hai ya nahi, W0 batayega).
- **VideoAdapter (LG):** `<video>` — webOS ka media pipeline. `playbackRate` se slow-fill.
- **MpegtsAdapter (dono ka fallback):** `mpegts.js` MSE par `.ts` ko fMP4 mein badal kar chalata hai.
  Web IPTV players ka aam hal. Agar kisi TV ka apna player `.ts` live mein kamzor nikla to yeh.

**Ek connection (`max_connections=1`):** channel badalne par pehla stream **band** ho, phir naya khule —
Android ka wahi qaida. Preview aur fullscreen ek hi player istemal karein.

## 5. Anti-buffer TV par

| Android | Web TV |
|---|---|
| Patience (5 s / 8 s, `LivePatienceLoadControl`) | Player ka buffer threshold stall ki ginti se (AVPlay `setBufferingParam`; `mpegts.js` config; `<video>` par hum khud `waiting` ke baad `canplay` + buffered ahead dekh kar `play()`) |
| Slow-fill 0.97x | `setSpeed` / `playbackRate`. Web par yeh Android se aasan ho sakta hai (tunneling ka masla nahi) |
| Match mode hold-on | Wahi policy, `setTimeout` se |
| Network grace | `navigator.onLine` + `online`/`offline` events + Tizen/webOS network API |
| "Connecting to <channel>…" | Pehle din se, har zap par |
| Interruption meter | Pehle din se, Firestore/analytics mein bhi (consent ke saath) |

## 6. Device ki pehchan aur license

Android par license ANDROID_ID se bani `deviceKey` par hai, aur Firestore rules `identityProof` mangte
hain. TV par:

- **Samsung:** `webapis.productinfo.getDuid()` (har TV ka mustaqil ID).
- **LG:** `luna://com.webos.service.sm/deviceid/getIDs` (LGUDID).
- deviceKey = `sha256(platform + ":" + id)`, taake Samsung/LG/Android ke ID kabhi takrayein nahi.

⚠️ **Backend ka kaam:** `admin-panel/firestore.rules` aur `functions` ko naye platform ki pehchan
qubool karni hogi (abhi sirf Android wala proof hai). Yeh wahi rules hain jin ka deploy abhi ruka hua
hai (3.4.1 ke baad). **Dono kaam ek saath hon**, rules ek hi dafa badlein.

## 7. Khatre (W0 inhi ko pehle pakdega)

1. **CORS.** Xtream servers CORS header nahi bhejte. Samsung packaged app `config.xml` mein
   `<access origin="*"/>` se cross-origin request kar sakti hai. LG par agar browser rok de to webOS app
   ke andar ek chhoti **JS service** (Node) proxy ka kaam karegi. Yeh sab se bada technical sawal hai.
2. **`.ts` live aur audio.** Har TV ka player `.ts` ko alag tarah chalata hai. Naye Samsung/LG par DTS
   nahi; kuch channels ki awaz ja sakti hai. AC3/E-AC3 aam taur par theek.
3. **Purane TV ki raftaar.** 2018 ke TV ka CPU aur RAM kam hai: virtual lists, kam animation, chhote
   images.
4. **Store ki manzoori.** Dono store IPTV "apni playlist lao" players qubool karte hain, lekin koi content
   saath nahi aana chahiye, aur privacy policy + terms chahiye. Debrid/torrent wala hissa review mein
   masla ban sakta hai, is liye **pehla store version sirf IPTV (Xtream)** ho, debrid baad mein.
5. **Remote keys.** Samsung par keys register karni padti hain (`tizen.tvinputdevice.registerKey`); Back
   ka code dono par alag (Samsung 10009, LG 461).

## 8. Phases (har ek alag commit, alag QA)

| Phase | Kya | Kaise pata chalega ke ho gaya |
|---|---|---|
| **W0** | Proof, asal TV par: ek `.ts` live channel AVPlay aur `<video>`/`mpegts.js` par, `player_api.php` ka CORS, Firebase anonymous login, device ID, Back/D-pad keys | Samsung aur LG dono par ek channel 10 min bina atke chale; CORS ka jawab saaf |
| **W1** ✅ code (2026-09-29, TV QA baqi) | Dhaancha: `tv-web/`, Vite build, platform layer, focus navigation, `.wgt` + `.ipk` packaging, CI mein build + tests | Dono TV par khali app khule, remote se chale, Back se nikle |
| **W2** | License + QR pairing + Xtream login + server-switch purge | Naya TV activation code dikhaye, panel se activate ho, phone se QR setup ho |
| **W3** | Live TV: categories, virtual channel list, preview, fullscreen, zap, now/next EPG, favourites, "Connecting…", cushion, hold-on, interruption meter | 10 tez zaps mein 0 × 403; landmine jaisi checklist TV ke liye |
| **W4** | VOD + series (Xtream), continue watching, audio/subtitle tracks | Movie resume ho, track badle |
| **W5** | Store submission (sirf IPTV version): icons, screenshots, privacy/terms, age rating | Dono store mein manzoor |
| **W6** | Debrid (TorBox, Stremio addons, MediaFusion) + zabaan wala auto-next — store ke qaide dekh kar | — |

**Tarteeb:** Samsung par pehle (W0–W3), phir wahi build LG par. Store tak sirf IPTV (W5), debrid baad
mein (W6).

## 9. Owner ke faisle / zaroorat

1. **Accounts:** Samsung Seller Office aur LG Seller Lounge (dono muft, company ki maloomat chahiye).
2. **TV:** ek Samsung (2019+ behtar) aur ek LG, developer mode ke saath, usi network par jahan local
   Claude hai (`.64` jaisa). W0 inhi par hoga.
3. **Naam:** store par app ka naam "DX Play" hi rahe? (Android ka `application-label`.)
4. **Pehla store version sirf IPTV** — theek hai? (Mashwara: haan.)
