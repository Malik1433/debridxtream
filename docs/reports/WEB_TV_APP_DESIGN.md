# Samsung + LG TV app (web): design

**Status:** DESIGN. Owner ne rasta manzoor kiya hai (2026-09-28): Samsung (Tizen) aur LG (webOS) ke liye
**ek web TV app**, aur Apple TV ke liye baad mein alag native app. Yeh doc sirf web TV app ka hai.
**Pehla qadam W0 hai** (neeche): asal TV par chhota sa proof. Us ke nateejay se pehle koi bada code nahi.

**Owner ke faisle (2026-09-28):** naam **DX Play**; pehla store version **sirf IPTV**. Test ke liye ghar
mein **Samsung** aur **VIDAA (Hisense)** TV hain, LG nahi. Is liye W0 Samsung + VIDAA par hoga, aur LG
tab jab LG TV mile. VIDAA bhi web TV hai, to wahi code chalega; W0 mein woh PC se chalne wale page
(`npm run vidaa`) ko TV ke browser mein khol kar test hota hai. Code: `tv-web/`.

---

## 0. W0 ke nateeje (VIDAA 2026-09-29, Samsung 2026-09-30) ⭐

**W0 MUKAMMAL — dono TV par.** VIDAA par TV ke browser mein LAN se khule page par; Samsung par asli
packaged app (`.wgt`) mein, Certificate Manager se bane profile ke saath. LG tab jab LG TV mile.

| Sawal | Jawab |
|---|---|
| Browser | Chrome **111** (armv7l). 2018 wale Chromium 53–56 ka dar yahan nahi |
| **CORS** (sab se bada khatra) | ✅ **Masla nahi:** `player_api.php` seedha HTTP 200 (164–465 ms) browser se. Yeh provider CORS allow karta hai. Doosre providers ke liye proxy ka plan wahi rahe, lekin pehle din zaroori nahi |
| MSE | ✅ hai (`mpegts.js` chal sakta hai) |
| Firebase anonymous login | ✅ |
| **mpegts.js**, live `.ts`, 10 min | ✅ pehli picture 1.5 s, **0 rukawat**, 622 s chala. 5 zaps: 0.7–1.6 s. Awaz theek (DD SPORTS bhi) |
| Slow-fill 0.97x → 1.0x | ✅ `playbackRate` se chalta hai |
| TV ka apna `<video>` | Picture aur awaz chalte hain, **lekin us ke upar koi HTML overlay nazar nahi aata** |
| Device ID (`Hisense_GetDeviceID`) | ✅ **milta hai** (`861003…`). W0 mein "nahi milta" likha tha — woh ghalat tha: platform detection ne VIDAA ko `browser` samjha (W1 mein fix), is liye code ne poochha hi nahi |
| Remote | Browser arrows aur CH+/− khud rakh leta hai, page ko sirf number keys milti hain — is liye VIDAA par numpad D-pad (2/8/4/6/5). Ye browser ki majburi hai: Samsung ki asli app mein asal arrows seedha milti hain |

### Samsung (GU75CU7179UXZG, **Tizen 9.0 / Chrome 120**, packaged app, 2026-09-30)

| Sawal | Jawab |
|---|---|
| Browser engine | **Chrome 120**. Ye 2023 ka set Tizen 9.0 tak update ho chuka hai — "2018 wala Chromium 53–56" is TV par nahi (purane TVs par barqarar, ES5 build rehti hai) |
| CORS | ✅ HTTP 200 (458–1380 ms). Packaged app mein `<access origin="*"/>` ka sawal hi khatam |
| MSE | ✅ |
| Firebase anonymous login | ✅ |
| Device ID | ✅ `getDuid()` → `K7G7MR…`, **`productinfo` privilege Public level par milti hai** (partner certificate nahi chahiye) |
| Remote | ✅ **12/12 keys registered**, asal arrows + OK; BACK (10009) se exit dialog |
| **AVPlay**, live `.ts` | first picture 0.4 s · **played 13 s** · **4 stops (94.8 s)** · buffer ahead **n/a** |
| **mpegts.js**, live `.ts` | first picture 0.6 s · **played 664 s** · **1 stop (0.1 s)** · buffer ahead **8.5 s** |
| Overlay | ✅ **dono par** panel ka text upar saaf dikhta hai |
| Slow-fill 0.97x → 1.0x | ✅ dono par |

**Faisle is se:**
1. **Har TV par player = `mpegts.js`.** VIDAA par TV ke apne `<video>` ke upar OSD/channel list/pill
   dikhte hi nahi; Samsung par AVPlay 108 second mein sirf 13 second tasveer de saka. Aur faisla-kun
   khana stops nahi, **`buffer ahead`** hai: `LiveCushionPolicy`, slow-fill aur hold-on sab cushion
   ke millisecond par likhe hain, aur AVPlay woh deta hi nahi.
2. ⭐ **AVPlay ko chunne ki wajah hi maujood nahi thi.** Ye doc kehta tha ke AVPlay video ko HTML ke
   *neeche* chalata hai, jo overlay ka aam tareeqa hai. Samsung par **mpegts.js ke upar bhi** poora
   overlay dikhta hai — yani woh bartari thi hi nahi. (VIDAA ka native `<video>` alag maamla hai:
   uske upar kuch nahi dikhta.)
3. Ye "AVPlay nakara hai" nahi hai: woh apni default settings par chala, live `.ts` ke liye koi
   buffering parameter diye baghair. `AvplayAdapter` isi liye rakha hai — jis din hardware decode ya
   HEVC us mehnat ke qabil hoga.
4. CORS ka proxy **pehle din nahi.** Sirf tab jab koi provider block kare.

### ⚠️ Do cheezein jo is W0 ne sikhayein

**Meter ko player par bharosa nahi karna.** W0 ka `PlayMetrics` player ke events par chal raha tha
(`onbufferingstart` / `onbufferingcomplete`). AVPlay ne tasveer jama di aur **koi event nahi bheja** —
meter ginta raha ke sab theek hai, aur us jami hui screen ko "stops 1 · played 57 s" likhta raha. Sahi
number tab mila jab meter ne **asal position** dekhna shuru ki (`getCurrentTime()` / `currentTime`):
wahi soorat-e-haal "4 stops (94.8 s) · played 13 s" nikli. Ye wahi ghalati ka andaz hai jo jank ke
"69.6%" mein hua tha — **number ne ghalat cheez naapi**. Stall ka waqt wahan se ginte hain jahan
tasveer **ruki**, na ke jahan humein pata chala (`metrics.test.ts` dono ko pakadta hai).

**Retail Samsung TV par `sdb shell` khamosh hai.** Install aur launch chalte hain, magar device ka log
nahi parha ja sakta — Fire TV wala `MediaCodecLogger` jaisa koi rasta yahan nahi. Is liye **screen par
mojood panel hi wahid aala hai**, aur usi wajah se meter ka sach bolna lazmi hai.

## 1. Kya banana hai

Ek TypeScript web app jo Samsung aur LG ke store par **apne naam se** jaye, remote (D-pad) se chale,
aur wahi account/license istemal kare jo Android app karta hai. Code ek, packaging do:

| | Samsung | LG |
|---|---|---|
| Package | `.wgt` (Tizen Studio / `tizen` CLI) | `.ipk` (`ares-package`) |
| Store | Samsung Seller Office | LG Seller Lounge (Content Store) |
| Player | **`mpegts.js`** (W0 ne tay kiya — §0) | **`mpegts.js`** (LG TV par tasdeeq baqi) |
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

- ⭐ **MpegtsAdapter — ye hi sab TV par chalta hai.** `mpegts.js` MSE par `.ts` ko fMP4 mein badal
  kar chalata hai. Cushion (`buffered`) deta hai, `playbackRate` se slow-fill, aur uske upar poora
  HTML overlay dikhta hai. `playerKindFor()` har platform par yehi lautata hai.
- **AvplayAdapter (Samsung):** `webapis.avplay` — `.ts` live, HLS, MKV, HEVC hardware par. **Abhi
  istemal mein nahi** (§0). ⚠️ Iska video `<object type="application/avplayer">` maangta hai —
  `avwindow` likhne par TV "Plug-in missing" keh kar safed screen deta hai aur koi error nahi aata.
- **VideoAdapter (LG/VIDAA ka native):** `<video>`. ⚠️ VIDAA par iske upar **koi HTML nazar nahi
  aata** — OSD, channel list aur "Connecting…" pill sab ghayab. Is liye fallback bhi nahi.

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

- **Samsung:** `webapis.productinfo.getDuid()` (har TV ka mustaqil ID). Privilege **Public** hai —
  partner certificate nahi chahiye (Samsung W0 par tasdeeq-shuda).
  ⚠️ **Samsung ke paas DO "DUID" hain aur woh alag hain.** App ko `getDuid()` se `K7G7MR…` milta
  hai; `sdb shell 0 getduid` — jo Certificate Manager distributor certificate mein daalta hai —
  `U7CJJ2…` deta hai (TV ki About screen par "Unique ID" vs "Unique Device ID"). **License wahi
  istemal karegi jo app khud parh sakti hai (`getDuid()`).** Certificate wala number kabhi
  `deviceKey` ke liye na lein, warna woh kabhi match nahi karega.
- **LG:** `luna://com.webos.service.sm/deviceid/getIDs` (LGUDID).
- deviceKey = `sha256(platform + ":" + id)`, taake Samsung/LG/Android ke ID kabhi takrayein nahi.

✅ **Backend badalna nahi padega (2026-09-30, rules parh kar tasdeeq).** Pehle yahan likha tha ke rules
ko naye platform ki pehchan sikhani hogi — woh ghalat tha:
- `licenses/{installId}` kisi bhi doc id ko qubool karta hai; device khud `pending` register hota hai,
  aur activation code `deriveActivationCode(installId)` se banta hai — Android (`LicenseManager`) aur
  `functions/index.js` dono mein wahi formula, platform ka koi zikr nahi. TV app bhi wahi SHA-256
  formula TypeScript mein chalayegi.
- `device_identity/{installId}` ka `proof` sirf "64 hex characters" hona chahiye (`proofOk`), yani
  `sha256("debridxtream-identity-proof-v1:" + tvId)` bilkul chalega.
- `licenseTelemetryOk` ki sharten (activation code alphabet, `appVersionName`, `appVersionCode` int)
  TV bhi poori karega.

Is liye W2 ko rules ke deploy ka intezar nahi. Security wale rules ka deploy (3.4.1 ke baad ruka hua)
alag, apne waqt par ho sakta hai. Sirf ek cheez baad mein chahiye ho sakti hai: admin panel mein
"yeh Samsung hai / VIDAA hai" dikhana — us ke liye `platform` telemetry field aur rules ki chhoti si
tabdeeli. W2 ke liye zaroori nahi.

## 7. Khatre (W0 inhi ko pehle pakdega)

1. **CORS — ✅ BAND (W0, dono TV).** Ye sab se bada khatra samjha gaya tha. VIDAA ke browser se
   aur Samsung ki packaged app se, dono jagah `player_api.php` seedha HTTP 200 deta hai. Proxy pehle
   din nahi; LG par dobara dekhenge, aur agar koi doosra provider block kare to webOS ki JS service
   wala plan bahaal hai.
2. **`.ts` live aur audio.** Har TV ka player `.ts` ko alag tarah chalata hai. Naye Samsung/LG par DTS
   nahi; kuch channels ki awaz ja sakti hai. AC3/E-AC3 aam taur par theek.
3. **Purane TV ki raftaar.** Ghar ka Samsung Tizen 9.0 / Chrome 120 par hai, is liye us par ye
   khatra nahi — magar woh humara sab se PURANA target nahi hai. 2018 ke TV ka CPU aur RAM kam hai:
   virtual lists, kam animation, chhote images, ES5 build. Ye khatra **ghair-aazmooda** rehta hai
   jab tak koi purana set na mile.
4. **Store ki manzoori.** Dono store IPTV "apni playlist lao" players qubool karte hain, lekin koi content
   saath nahi aana chahiye, aur privacy policy + terms chahiye. Debrid/torrent wala hissa review mein
   masla ban sakta hai, is liye **pehla store version sirf IPTV (Xtream)** ho, debrid baad mein.
5. **Remote keys — ✅ Samsung par band.** `registerKey` se 12/12 mil gayeen, asal arrows + OK
   seedha aate hain, BACK 10009 chalta hai. LG (461) baqi. ⚠️ VIDAA **browser** alag maamla hai:
   woh arrows aur CH+/− khud rakh leta hai aur page ko sirf digits deta hai — isi liye wahan
   numpad D-pad hai. Woh browser ki majburi hai, TV ki nahi.

## 8. Phases (har ek alag commit, alag QA)

| Phase | Kya | Kaise pata chalega ke ho gaya |
|---|---|---|
| **W0** ✅ **DONE** (VIDAA 2026-09-29, Samsung 2026-09-30) | Proof, asal TV par: ek `.ts` live channel AVPlay aur `<video>`/`mpegts.js` par, `player_api.php` ka CORS, Firebase anonymous login, device ID, Back/D-pad keys | ✅ mpegts.js: VIDAA 622 s / 0 stops, Samsung 664 s / 1 stop (0.1 s). CORS saaf. Player ka faisla §0 |
| **W1** ✅ **DONE** (2026-09-30, dono TV par QA) | Dhaancha: `tv-web/`, Vite build, platform layer, focus navigation, `.wgt` + `.ipk` packaging, CI mein build + tests | ✅ Dono TV par app khuli, remote se chali, Back se nikli, Settings ne sahi platform/player/device id dikhayi |
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

---

## 10. Samsung par build/install ka amal (2026-09-30 par tay hua)

Ek dafa ka setup: Tizen Studio (Baseline) → Package Manager → **Samsung Certificate Extension**
(TV Extensions baseline install mein hi aa jate hain) → Certificate Manager mein **Samsung/TV**
profile (owner ka Samsung account, aur TV ka `sdb` se jura hona lazmi taake DUID mil sake).

Har baar ka chakkar:

```
npm run tizen                        # build + build-tizen/ tayyar
tizen package -t wgt -s <profile> -- build-tizen
mv "DX Play.wgt" DXPlay.wgt          # <- ye qadam CHHORNA nahi
tizen install -n DXPlay.wgt -t <device-name> -- build-tizen
tizen run -p DXPlayTVap.DXPlay -t <device-name>
```

⚠️ **Teen jaal jin mein hum phans chuke hain — teeno ka error jhoota ya khamosh hota hai:**

1. **Package ke naam mein space.** `config.xml` ka `<name>DX Play</name>` file ko `DX Play.wgt`
   banata hai. Transfer chalta hai, phir install **`Failed to install Tizen application.`** kehta hai
   aur wajah nahi batata. File ka naam space ke baghair kar dein — signature file ke naam par nahi,
   andar ke maal par hoti hai, is liye rename mehfooz hai.
2. **SDK ka install path.** `E:\New folder (3)` jaisi jagah par `tizen.bat` apne hi path par toot
   jata hai: `The system cannot find the file E:\New.` / `Could not find or load main class folder`.
   `sdb` phir bhi chalta hai, is liye lagta hai sab theek hai. **Path mein space ya bracket nahi.**
3. **Certificate Manager chup-chaap band ho jata hai.** Samsung ki apni `eclipse.ini` mein
   `--add-modules=ALL-SYSTEM` (Java 9+) hai jabke saath aane wala JVM **Java 8** hai → JVM start hi
   nahi hoti, na window, na log, exit code 0. Us ek line ko hata dein.
   (Isi wajah se system ka Java version bemaani hai — Tizen apna Java saath lata hai.)

⚠️ **QA ki hadd: retail Samsung TV par `sdb shell` koi output nahi deta.** Install/launch chalte hain,
device ka log nahi milta. Screen par jo panel hai wahi wahid aala hai — is liye har naap **asal
progress** par honi chahiye, player ke apne dawe par nahi (§0).

**Ye certificate sirf UN TVs par install karne deta hai jin ka DUID uske distributor certificate mein
hai.** Naya test TV = us ka DUID bhi profile mein daalna. Store par jane wali app par ye pabandi nahi.
