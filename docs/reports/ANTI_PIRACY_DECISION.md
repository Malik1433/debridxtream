# Anti-piracy: what is actually achievable, and the one decision it depends on

**Status:** §6 decided and §4.4 shipped. **§7 (end-user accounts + device slots + playlist management)
is the live plan** — three questions in §7.8 need the owner before U0 starts.
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
And a freed slot must not make "5 devices" mean unlimited: cap **swaps per subscription per 30 days**
(suggest 2). This number is a business knob, not a technical one — owner sets it.

### 7.2 Data model (additions only; nothing existing changes shape)

| Collection | Shape | Notes |
|---|---|---|
| `users/{uid}` | `{email, displayName, createdAt, status}` | uid = Firebase Auth uid. Profile only; Auth is the identity |
| `subscriptions/{subId}` | `{ownerUid, resellerId, planId, tier, status, expiresAt, deviceLimit, swapsUsed, swapWindowStart, createdAt}` | entitlement truth |
| `playlists/{playlistId}` | `{ownerUid, name, type:'xtream'\|'m3u', url, username, password, enabled, createdAt, updatedAt}` | owner-scoped; see 7.6 on encryption |
| `device_auth/{authUid}` | `{installId, ownerUid, subscriptionId}` | lets rules resolve "which device is this caller" |
| `licenses/{installId}` | **+** `{ownerUid, subscriptionId, deviceName, lastSeenAt}` | existing fields untouched — the TV keeps reading what it reads |
| `plans/{planId}` | **+** `{deviceLimit}` | reseller-visible |

### 7.3 Cloud Functions (Blaze is already enabled; `activateClient`/`renewClient` are live)

- `claimDevice({activationCode, deviceName})` — auth required. Resolve the licence by code, assert the
  caller has an active subscription **with a free slot**, then in one transaction bind
  `licenses/{installId}.{ownerUid, subscriptionId}`, project `{status, tier, expiresAt}` from the
  subscription, write `device_auth`, and audit. Idempotent on re-claim by the same owner.
- `releaseDevice({installId})` — frees a slot, increments `swapsUsed`, refuses past the cap.
- `rebindDevice({installId, newAuthUid})` — the `pm clear` / reinstall path; allowed only when the
  device is already owned, so it needs no customer action.
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
| Signed in, slots full | device list, remove one to continue (subject to the swap cap) |
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
| U7 | Devices page (list / rename / remove) + reseller sees `deviceLimit` | yes |
| U8 | Flip the flag, stop writing credentials to `device_codes`, tighten rules | yes |
| U9 | TV QR screen wording + D-pad pass | yes |

**Migration:** a device with no `ownerUid` keeps behaving exactly as it does today. There is no forced
migration and no flag day. U8 only stops *writing* credentials to `device_codes`; existing devices that
never get claimed carry on.

### 7.7 What this does NOT do

It does not make the app harder to crack (§6 still stands), it does not stop a customer sharing their
own Xtream credentials outside the app, and it adds a real support burden — password resets and
verification emails for end users, which we do not have today. That burden is the actual price of this
feature and it should be weighed as such.

### 7.8 Open — owner's call before U0

1. **Device limit**: how many, and does it come from the plan or per-subscription?
2. **Can end users buy directly**, or only through a reseller? (Decides whether the payment path from
   the reseller portal has to be duplicated for consumers.)
3. **Swap cap** number (D6).
