# Anti-piracy: what is actually achievable, and the one decision it depends on

**Status:** decision needed from the owner before any of this is built.
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

## 6. The decision

**Answer §1 — do you sell the service, or does the user bring it?**

- **You sell it** → build §4.1, then §4.2 + §4.3 + device limits. That is a real lock and worth the work.
- **They bring it** → do §4.1 and §4.4 and stop there. Anything more is effort spent on a door that
  cannot be closed.
