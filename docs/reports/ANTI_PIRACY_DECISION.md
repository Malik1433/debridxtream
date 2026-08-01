# Anti-piracy: what is actually achievable, and the one decision it depends on

**Status:** §6 decided and §4.4 shipped. **§7 (end-user accounts + device slots + playlist management)
is the live plan; its three open questions were answered 2026-07-31 (§7.8) and U0 is unblocked.**
**Date:** 2026-07-31. Written after a research pass plus reading how credentials actually flow here.

---

## 1. The finding that decides everything

Two facts about this app, both verified in the code:

1. **Xtream stream URLs embed the username and password in the path**
   (`buildLiveStreamUrl(baseServerUrl, username, password, streamId, …)`). The player builds them
   on-device, so the credentials **must** be present on the device at playback time. Keeping them
   purely server-side is impossible without proxying the video itself, which is not practical.

2. **The app accepts manually typed credentials** (`LoginFragment` → `credentialsPrefs.saveCredentials`).

Fact 2 is the one that matters. It means:

> If a user can bring their own IPTV subscription, then **no client-side protection can stop a cracked
> build being useful to them.** They strip the licence check, type their own credentials, and the app
> works — because the thing of value was never yours to withhold.

Everything else in this document follows from that. So the question is not "how do we make the APK
crack-proof" — that has no answer — it is:

> **Are you selling the IPTV service, or an app that people point at their own service?**

---

## 2. If you SELL the service (credentials come from you)

Then there is a real lock, and it is not on the device at all:

- The credentials are the product. **Deliver them from your server, per session, only to a device with
  a valid licence.** A cracked build that removes the licence check gains nothing: it still has no
  credentials, and the app is empty without them.
- **Remove or restrict manual login**, or the lock has a door next to it. This is the part that is
  easy to skip and fatal to skip.
- Add **hardware key attestation** on that endpoint (see §4) so credentials only go to a genuine,
  untampered device rather than to a script replaying the API.
- Add a **device limit per licence** — one licence, N devices. Sharing an account is not a cracking
  problem and cannot be solved by anti-tamper; it is solved by counting devices.

This is the only configuration in which "nobody can use the app without a licence" is actually true.

## 3. If users BRING their own service

Then accept what is achievable and do not spend weeks on the rest:

- Licence enforcement stops **casual** copying — someone handed the APK who cannot be bothered to
  patch it.
- The signature self-check (§4.1) stops **repackaged redistribution** — a modified build passed around
  as "the app".
- Neither stops a determined person with the APK and their own subscription, and no amount of client
  hardening will. Google's own anti-piracy guidance says the goal is to raise the bar out of reach of
  opportunistic attackers, not to guarantee.

Spending the effort in §2 here would buy very little.

---

## 4. The measures themselves, in order of value

### 4.1 Release signing — READY, one command, do it either way
`scripts/setup_release_signing.sh`. Until it is run, `EXPECTED_SIGNING_SHA256` is empty and
`AppIntegrity.isTrustedSignature()` returns **true unconditionally** — a repacked, re-signed build
passes today. Note it gates **premium only** (`isPremiumCached`), not basic entitlement, by existing
design: a repack loses debrid, keeps plain IPTV.
*Cost:* every existing install needs one uninstall + reinstall (Android rejects an in-place update
when the signing certificate changes).

### 4.2 Server-delivered credentials — the actual lock, only meaningful under §2
Move credential delivery behind the licence. Companion pairing writes them to your backend; the TV
fetches per session rather than storing them permanently. Touches the **live pairing flow**, so it
needs owner-present QA.
> **Superseded 2026-07-31 by §7.** Dropped as an anti-piracy measure (see §6) — but the *account*
> half of it came back for a different and better reason: managing the customer's own playlists.
> §7 is that design.

### 4.3 Hardware key attestation — works on Fire TV, unlike Play Integrity
Play Integrity needs Play Services, which Fire TV does not have. **Android hardware key attestation
does not** — the device's secure hardware produces a certificate chain rooted in Google's attestation
root, and your server verifies the chain plus a challenge (anti-replay). In some respects it is
stronger than Play Integrity. It shares its building block with the planned device_codes encryption
work, so the two should be designed together.

### 4.4 Online licence gate — cheap, and no Blaze needed
The owner's stated preference is online-only, no offline token. **That removes the Cloud Function, and
with it the Firebase Blaze blocker** — `LicenseManager` already reads the licence from Firestore. The
gate becomes: a successful check within N hours, else lock.
*Recommended:* keep a short grace window (24–48h). It gives an attacker nothing — they patched the
check anyway — but it stops a Wi-Fi blip or a Firebase outage locking out every paying user at once.

### 4.5 Already in place
R8 / `minifyEnabled` is on for release builds. Keep it; it makes the licence branches harder to find.

---

## 5. What is NOT worth doing

- **Chasing "crack-proof".** It does not exist for a client-side check. Time is better spent on §4.2.
- **Root detection / emulator detection.** Trivially bypassed, and it breaks legitimate users.
- **Play Integrity.** Not available on the target hardware.
- **Offline signed tokens.** Explicitly rejected by the owner in favour of online-only, and online-only
  is simpler and needs no backend function.

---

## 6. The decision — ANSWERED 2026-07-31

**The owner's answer: users bring their own IPTV subscription.**

So this is the §3 branch, and the scope is settled:

| | |
|---|---|
| **§4.1 Release signing** | DO — one command, owner runs it (holds the password) |
| **§4.4 Online licence gate** | DO — cheap, no backend function, no Blaze |
| **§4.2 Server-delivered credentials** | **DO NOT BUILD** — see below |
| **§4.3 Hardware attestation** | Not for licensing. Keep it in scope only for the device_codes encryption work, where it protects the user's credentials rather than the licence |

**Why §4.2 is dropped, explicitly.** It is the strongest measure available and it would be wasted
here. Its whole power is withholding something the app cannot work without — and when the user brings
their own subscription, we hold nothing to withhold. Building it would be weeks of work on the live
pairing flow for a lock with no door behind it.

**What that means honestly:** licence enforcement will stop casual copying, and the signature check
will stop repackaged redistribution. A determined person with the APK and their own subscription will
still get through, and nothing buildable on the client changes that. That is the ceiling, and it is
worth knowing rather than paying to discover.

---

## 7. End-user accounts, device slots, and playlist management (plan, 2026-07-31)

**Requested by the owner**, TiviMate-shaped: the customer makes their own account on the companion
site, one subscription covers N devices, they scan the QR on the TV to attach a device, and they can
add and edit their playlists at any time from the phone.

This is not an anti-piracy feature and should not be sold to ourselves as one — under §6 the customer
brings their own subscription, so nothing here makes the APK harder to crack. It earns its place for
two other reasons, and both are real:

1. **It closes the credential leak.** Today `device_codes/{code}` is `allow get, create, update: if
   true` and holds the customer's Xtream username and password in plaintext. The key is printed on the
   TV screen and handed to the provider. Anyone who sees it can read those credentials and overwrite
   that TV's config. An account model replaces *"knowing the key grants access"* with *"owning the
   record grants access"*, which is the only framing that actually fixes this.
2. **Device counting is the one real answer to account sharing** (§2 already says so). It is not an
   anti-tamper problem and no client hardening addresses it.

### 7.0 Two sales channels, two entitlement models — and only one of them counts devices

Clarified by the owner 2026-07-31, after U0 had already been built the wrong way. This is the frame
everything else in §7 sits inside:

| | **Reseller channel** (exists, working) | **Direct / consumer channel** (new) |
|---|---|---|
| Who buys | reseller, on behalf of a client | the end user, in the mobile companion app |
| Unit | **per device** | **per subscription** |
| Device limit | **none — they pay per device** | **3** (§7.8) |
| Mechanism | `activateClient` spends credits, writes `licenses/{installId}` | `claimDevice` consumes a slot on `subscriptions/{subId}` |

**So `deviceLimit` must never live on `plans`** — `plans` is the reseller's price list and a limit
there would be meaningless at best and a mis-sale at worst. It lives on the **subscription**.

This also splits a thing that looked like one thing:

- **Entitlement** — either reseller-per-device *or* a consumer subscription.
- **Ownership** — the account that manages a device's playlists.

They are independent. A customer whose device was activated by a reseller can still create an account
and manage their playlists from the phone, and **that must not consume a subscription slot**, because
they have no subscription. `claimDevice` therefore has two paths: a device already entitled through a
reseller is bound for *management only*; anything else needs an active subscription with a free slot.

### 7.1 The decisions this rests on

**D1 — The subscription becomes the unit of entitlement; `licenses/{installId}` stays exactly as it
is.** The TV's licence gate reads `licenses/{installId}` and it is device-verified and working.
Claiming a device writes *the same fields the TV already reads*. **The licence gate is not rewritten
and not touched.** This is the difference between a feature and an outage.

**D2 — The TV gets an identity: Firebase Anonymous auth, bound to the device when it is claimed.**
Without an identity, every rule we can write reduces to "knows the key ⇒ allowed", which is the bug.
With one, rules can express ownership. The anonymous uid is lost if app data is cleared — but
`installId` is derived from `ANDROID_ID` and survives `pm clear` (verified on `.64` today), so a
Function can re-bind a returning device automatically without the customer doing anything.

**D3 — Devices READ playlists; the phone stops PUSHING config.** An edit on the phone reaches the TV
through the device's own listener. This is what "edit any time" actually requires, and it deletes the
push channel that carries the credentials.

**D4 — `device_codes` is demoted to a short-lived claim ticket carrying no credentials.** Once nothing
sensitive is stored there, the pending *"encrypt device_codes at rest"* task is **not needed** —
ownership rules are strictly stronger than encrypting a world-readable document. Do not build both.

**D5 — Email verification must not stand between a paying customer and their first frame.** Give an
unverified account a **48-hour window in which everything works**, then require verification for
claiming and editing. Same reasoning as the licence grace shipped in §4.4: lenient where being wrong
punishes a real customer, bounded so it is not a hole. The reseller's activate-by-code path stays as
the fallback so nobody is ever stuck at 9pm.

**D6 — Slot accounting lives in a Function transaction, never in rules.** Rules cannot count.
A freed slot must not make "3 devices" mean unlimited, so **the customer cannot free a slot at all**
(owner's call, §7.8): removing a device is an owner/reseller action. That removes the need for any
swap-rate machinery — no counters, no windows — at the price named in §7.7.

### 7.2 Data model (additions only; nothing existing changes shape)

| Collection | Shape | Notes |
|---|---|---|
| `users/{uid}` | `{email, displayName, createdAt, status}` | uid = Firebase Auth uid. Profile only; Auth is the identity |
| `subscriptions/{subId}` | `{ownerUid, planId, tier, status, expiresAt, deviceLimit, createdAt}` | **consumer channel only** (§7.0). `deviceLimit` is stamped here at creation, not read live from a plan, so changing a product later never silently shrinks someone's existing subscription. Defaults to 3 until consumer purchase exists and defines products |
| `playlists/{playlistId}` | `{ownerUid, name, type:'xtream'\|'m3u', url, username, password, enabled, createdAt, updatedAt}` | owner-scoped; see 7.6 on encryption |
| `device_auth/{authUid}` | `{installId, ownerUid, subscriptionId}` | lets rules resolve "which device is this caller" |
| `licenses/{installId}` | **+** `{ownerUid, subscriptionId, deviceName, lastSeenAt}` | existing fields untouched — the TV keeps reading what it reads |
| `plans/{planId}` | **unchanged** | reseller price list, **per device**. No `deviceLimit` here — see §7.0 |

### 7.3 Cloud Functions (Blaze is already enabled; `activateClient`/`renewClient` are live)

- `claimDevice({activationCode, deviceName})` — auth required. Resolve the licence by code, then
  **branch on how the device is entitled** (§7.0):
  - *already entitled through a reseller* → bind `ownerUid` + `device_auth` for **management only**.
    No slot is consumed and no entitlement field is touched — the reseller sold that device and this
    call must not be able to alter what they sold.
  - *otherwise* → require an active subscription with a free slot; in one transaction bind
    `licenses/{installId}.{ownerUid, subscriptionId}` and project `{status, tier, expiresAt}` from the
    subscription.

  Idempotent on re-claim by the same owner in both cases.

  A TV that has never signed in has no identity yet. The claim still goes through and returns
  `identityPending` — refusing would leave a paying customer unable to watch over a background
  detail. Only the playlist binding waits.
- `releaseDevice({installId})` — **owner/reseller only** (§7.8 Q3). Frees a slot, and takes back the
  entitlement it had projected — but only for a subscription device. A reseller-entitled device keeps
  what the reseller sold. The end user has no path to call this; their Devices page is read-and-rename.
- ~~`rebindDevice`~~ — **dropped during U4, and the reason matters.** It was meant to re-link a device
  automatically after its app data was cleared. But the only thing it could authenticate against is
  `device_identity/{installId}`, which the device itself writes — so anyone who learned an installId
  could point a victim's binding at their own anonymous uid and read that account's IPTV credentials.
  That is the exact "knowing the id grants access" hole §7 exists to close, rebuilt in a new place.
  The customer simply re-scans the QR instead: `claimDevice` is idempotent and re-claiming a device
  already on the subscription costs no slot. A rare re-scan is a much better price than an attack
  class.
- `activateSubscription({userEmail, planId})` — the reseller sells a *subscription* instead of a single
  device. Existing `activateClient` stays untouched for the legacy device-at-a-time flow.

### 7.4 Rules, in one line each

`users` own-read/own-write. `subscriptions` own-read, **writes function-only**. `playlists` full CRUD
for `ownerUid == request.auth.uid`, plus **read** for a bound device (`get(device_auth/$(uid)).ownerUid
== resource.data.ownerUid`). `device_codes` loses `get` for anyone but the owning account once the
claim ticket carries no credentials. `licenses` keeps its current device-unauth create/telemetry
rules; the new ownership fields are function-only.

### 7.5 The QR journey (the part that decides whether customers succeed)

TV shows a QR for `https://<companion>/link?code=DZ5D-WKV7`. The code must survive the whole detour:

| State on arrival | What happens |
|---|---|
| Not signed in | signup/login, **`code` preserved through the round trip**, land back on the claim screen |
| Signed in, unverified, <48h | claim proceeds (D5) with a "verify your email" banner |
| Signed in, no subscription | "ask your provider" + the activation code shown for the reseller |
| Signed in, slot free | "Add this TV?" → `claimDevice` → the TV's existing listener unlocks it |
| Signed in, slots full | device list (read-only) + "all 3 devices in use — ask your provider to remove one". Removal is not a customer action (D6) |
| No playlist yet | straight into "add your first playlist", test-connection via the existing `api/verify-iptv.ts` |

### 7.6 Build phases — one commit each, verify before moving on

| # | Phase | Ships behaviour? |
|---|---|---|
| U0 | Data model + `plans.deviceLimit` + rules skeleton | no |
| U1 | End-user auth on the companion (signup / login / verify) + `users/{uid}` | web only |
| U2 | Playlists CRUD, owner-scoped, with connection test | web only |
| U3 | TV anonymous auth + `device_auth` binding — TV still uses the old path | no |
| U4 | `claimDevice` / `releaseDevice` / `rebindDevice` + slot transaction | no |
| U5 | `/link?code=` journey incl. the signup-and-return path (7.5) | yes |
| U6 | TV reads playlists from the account, **behind a flag**, old path intact | flagged |
| | *Flag lives in `iptv_settings` → `account_playlist_sync`, default OFF. It rewrites the credentials the app logs in with — the most disruptive thing that can be done to a working install — so it ships dark and is turned on only after the claim flow has been exercised on real hardware.* | |
| U7 | Devices page (list / rename, **no remove**) + reseller-side Unlink | yes |
| U8 | Flip the flag, stop writing credentials to `device_codes`, tighten rules | yes |
| U9 | TV QR screen wording + D-pad pass | yes |

**Migration:** a device with no `ownerUid` keeps behaving exactly as it does today. There is no forced
migration and no flag day. U8 only stops *writing* credentials to `device_codes`; existing devices that
never get claimed carry on.

### 7.7 What this does NOT do, and what it costs

It does not make the app harder to crack (§6 still stands) and it does not stop a customer sharing
their own Xtream credentials outside the app.

The price is support load, and the §7.8 answers concentrate it in one place: **every replaced or dead
TV becomes a reseller ticket**, because the customer cannot free their own slot. That is the deliberate
trade for making the 3-device limit mean three devices. Watch it after launch — if it turns into the
most common ticket, the cheapest relief is a self-service removal with a cooldown rather than raising
the limit. On top of that come the ordinary costs of running accounts: password resets and
verification emails, which we do not carry today.

### 7.8 Owner's answers — ANSWERED 2026-07-31

| Question | Answer | Consequence in this plan |
|---|---|---|
| Device limit | **3 — and only for the direct/consumer channel.** Resellers have **no limit**; they pay per device | stamped on `subscriptions`, never on `plans` (§7.0). Corrected after U0 was first built with it on `plans` |
| Who sells | **Resellers now; consumer purchase later** | `subscriptions.ownerUid` is the end user from day one, so consumer checkout is an added path, not a reshape |
| Freeing a slot | **Owner/reseller only** | no swap counters anywhere; `releaseDevice` is privileged; customer Devices page is read + rename (§7.7 names the cost) |

**U0 is unblocked.**

---

## 8. Account sign-in on the TV, multi-server, and premium (owner, 2026-07-31)

Three follow-ups from the owner after U6 was proven on hardware. The first answer is the one that
keeps this small:

> **"Email login sirf premium activation verification ke liye, baaki sab same hi rahega."**

So the TV's login screen does **not** change. IPTV credentials still arrive the way they do today
(QR pairing, manual entry, or §7 U6's account sync). Signing in with a DebridXtream account is a
separate, optional act with exactly one purpose: proving the user has a subscription that includes
premium, so debrid unlocks.

That is a much smaller and safer change than putting account login in front of everything, and it
leaves every working path alone.

### 8.1 The interaction that would otherwise break U6 silently

Today the TV signs in **anonymously** and its playlists are found through
`device_auth/{anonymousUid} → ownerUid`.

The moment a user signs in with email, **the TV's uid changes to their own**. `device_auth` has no
row under that uid, so playlist sync would simply stop — with no error, on a device that was working
a second earlier.

The fix is to stop treating the anonymous binding as the only way to know the owner:

- signed in as a **real user** → the owner IS `request.auth.uid`; read `playlists` directly. This
  path is actually simpler than the anonymous one, and needs no `device_auth` row at all.
- signed in **anonymously** → resolve the owner through `device_auth` exactly as U6 does now.

Firebase's anonymous→email *linking* is deliberately not used: it fails when the account already
exists, which is the normal case here (the customer made their account on the phone first).

### 8.2 Where premium comes from (owner: account subscription, resellers unchanged)

§7.0's two channels stay intact:

| | source of premium |
|---|---|
| Reseller-sold device | its own licence, exactly as today — **untouched** |
| Consumer, signed in | their subscription's `tier` + `expiresAt` |

So premium becomes *"the device licence grants it **or** the signed-in account's subscription does"*.
Widening only: no device that has premium today can lose it because of this.

`subscriptions` is written only by Functions/admin and readable only by its owner, so it is a
trustworthy source. The existing tamper-evidence on the local cache is unaffected — a subscription is
re-read from the server, not cached and trusted.

### 8.3 Multi-server (owner: one active at a time, switchable)

The account may hold several playlists; the TV runs **one** and the user switches it in Settings.
Deliberately not "merge everything into one catalogue": that would touch sync, EPG and the catalogue
layer — the parts of this app that have caused the loading problems — for a convenience.

Today `AccountPlaylistSync` silently picks the *first* enabled playlist, which is a coin flip once
someone has two. An explicit choice, remembered, replaces it.

### 8.4 Phases

| # | Phase | Risk |
|---|---|---|
| V1 | Owner-resolution fix (8.1) — real user vs anonymous. Ships **before** any sign-in UI exists, so U6 cannot break when V2 lands | low |
| V2 | Multi-server: remember the active playlist, pick it in Settings, sync honours it | medium — touches the credentials the app logs in with |
| V3 | Account sign-in on the TV, in Settings only, for premium activation | medium — new auth path, must not disturb anonymous identity for unclaimed devices |
| V4 | Premium from the subscription, folded into the existing entitlement checks (8.2) | **high — gates debrid.** Widening only, and verified on device before it ships |
| U8 | Flag default ON + stop writing credentials to `device_codes` + tighten rules | high |
| U9 | Point the TV's QR at `/link` — **only after U8**, or the QR leads somewhere that does nothing | medium |

V1 first is not sequencing for its own sake: without it, the day V3 ships is the day every
signed-in TV quietly stops receiving playlists.

---

## 9. Account sign-in as the entitlement check (owner, 2026-07-31)

The owner's shape, in their words: after install the app asks for the account email and password,
the server confirms the subscription and **which services are active**, the app unlocks accordingly,
and the link the customer is given afterwards offers only what they are entitled to configure.

### 9.1 What was settled

| Question | Answer |
|---|---|
| Does manual IPTV login go away? | **No.** Account sign-in is added; manual entry and the QR stay exactly as they are |
| Reseller-sold devices | **The device key is a second, equal way in.** If a reseller has activated that key, the device is verified through that activation and needs no email/password at all |
| Services | **The existing `normal` / `premium` tiers.** `normal` = IPTV, `premium` = IPTV + debrid. No new services model |

The reseller answer is the one worth dwelling on: it does not add a mechanism, it **surfaces one that
already exists**. `licenses/{installId}` has carried the entitlement all along — the login screen
simply stops pretending that email/password is the only way to prove it. Two paths, one question:
*is this device entitled, and to what?*

### 9.2 Verification has two sources, and neither weakens the other

```
entitled(device) = reseller-activated licence  OR  signed-in account's subscription
tier             = whichever of those granted it
```

Widening only. No device that works today can stop working because of this, which is the property
that lets it ship without a migration plan — the concern that made "account mandatory" the wrong
answer.

### 9.3 The link is scoped by tier

Today the companion page offers IPTV fields *and* debrid addons to everyone. Once entitlement is
known it shows what the customer actually has:

- `normal` → IPTV only. No debrid section at all — not disabled, absent. A control that exists but
  refuses is a support ticket; a control that is not there is an answer.
- `premium` → both, as now.

### 9.4 Phases

| # | Phase | Risk |
|---|---|---|
| W1 | Account sign-in on the TV (email/password) — authenticates only, changes no entitlement | low |
| W2 | Entitlement reads the account subscription **as well as** the licence (9.2) | **high — gates debrid** |
| W3 | Companion link scoped by tier (9.3) | low |

W1 before W2 for the same reason V1 came before V3: the sign-in has to exist and be exercised before
anything starts depending on what it returns.

**Superseded by this section:** §8's V3/V4. They described the same work when sign-in was still
imagined as a premium-only afterthought hidden in Settings. W1–W3 replace them.

---

## 10. Where this actually stands (2026-08-01) — read this instead of §8/§9

The owner said the plan had become confusing. It had. One decision they made is what un-confuses it:

> **There is only ONE tier.**

That single answer deleted most of §8 and §9: no normal/premium split, no "which services did they
buy", no entitlement-scoped pairing link, and no risky phase gating debrid on a subscription lookup.

What is left fits in two sentences:

> **The licence answers one question: may this device run the app.**
> **What the customer can watch depends on what they entered.**

IPTV details → IPTV works. Debrid addons as well → debrid works too. The customer brings both
services; we sell neither.

### 10.1 Done and device-verified

| | |
|---|---|
| **One tier** (`ce8243d`) | `isDebridAllowed` now follows the app's own licence. A second question, `isDebridConfigured`, is used in exactly two places — the home badge and Continue Watching — because those are the only places where the alternative is telling the customer something untrue |
| **TV login page** (`728fa33`) | "Sign in with your account" + the device key, beside the untouched IPTV fields and phone/QR. Account sign-in sits BELOW phone/QR because typing on a remote is the slowest way in |
| **Debrid setup guide** (`aeda106`) | Opening Debrid with no addons explains how to add them instead of showing rows that never fill. The section stays reachable on purpose — hiding it teaches nobody anything |
| **Google on the website** (`ee35905`) | Popup with redirect fallback, `prompt=select_account`, profile doc created for Google users |

### 10.2 Owner decisions recorded so they are not re-litigated

- **Multi-server: dropped, and why.** Watch history, favourites and the catalogue are keyed by stream
  id with no record of the provider, so switching servers on one device would fill Continue Watching
  with entries that cannot play. What survives is per-device assignment: different TVs, different
  providers, each seeing exactly one.
- **The audio-wedge warning: deliberately NOT built.** The owner's call — the freeze appeared under
  our own heavy double-playlist testing, not under normal use, and the cause is now understood
  (turn the TV off and on). Recorded so this is not mistaken for an oversight.
- **No Google button on the TV.** Fire TV has no Play Services. A TV-side Google sign-in has to be a
  device-code flow, which is its own phase — and is only needed by someone who has a Google-only
  account *and* no phone.

### 10.3 Blocked on the owner, not on code

Two Firebase console settings, or Google sign-in cannot work — found by querying the project rather
than waiting for it to fail in the owner's hands:

1. Authentication → Sign-in method → **Google** → Enable.
2. Authentication → Settings → **Authorized domains** → add `debxtrem-companion.vercel.app`.

Only `localhost`, `debridxtream-new.firebaseapp.com` and `debridxtream-new.web.app` are listed today.
Authorized domains gate **OAuth only**, which is why email/password already works from Vercel — and
why this stays invisible until somebody presses the Google button.

### 10.4 Still open

- **U8a — DONE.** `account_playlist_sync` now defaults ON.
- **U9 — DONE.** The TV's QR points at `/link` and the overlay was rewritten for that flow.
- **U8b — the only thing left, and it waits on the fleet, not on code.** Stop writing credentials to
  `device_codes` and tighten those rules. It is a small change held back for one reason: **a device's
  QR address is baked into the build it is running.** Older builds still point at the legacy
  ConfigPage, so removing credential writing kills their only pairing route. Do it once old builds
  are gone — not before.
  *(An earlier note here claimed U8b and U9 had to ship together. Wrong: repointing the QR only
  affects devices running the NEW build, so U9 was safe on its own. Only U8b is coupled to old
  builds still existing.)*
- TV-side Google (device-code flow), if a Google-only customer without a phone ever turns up.
