# DebridXtream Admin Panel — One-Time Setup

Everything runs on the existing Firebase project **debridxtream-new**. Three console
steps, then the panel works.

## 1. Create your admin login
1. Firebase console → **Authentication** → Sign-in method → enable **Email/Password**.
2. Authentication → Users → **Add user** (your email + a strong password).
3. Copy the user's **UID** from the users table.

## 2. Register yourself as admin
1. Firestore Database → **Start collection** → id: `admins`.
2. Document ID = the **UID** you copied. Fields: `role` = `"owner"` (string).

## 3. Publish the security rules
1. Firestore Database → **Rules** tab → replace everything with the contents of
   [`firestore.rules`](firestore.rules) → **Publish**.
   (Keeps companion pairing working; locks licensing so only admins can activate.)

## Open the panel
- Easiest: just open `admin-panel/index.html` in a browser (double-click) — it talks
  to Firebase directly, no server needed.
- Hosted (recommended): `firebase deploy --only hosting` with this folder as the
  hosting public dir, so you can open it from anywhere.

## Using it
- **Devices tab:** every installed app registers itself automatically (status
  PENDING). Client reads you their activation code from the TV screen → find it →
  **Activate**. Set tier (Normal/Premium), expiry date, and a note (client name).
  **Deactivate** cuts them off.
- **Licensing tab:** the master switch. Keep **OFF** until you have activated your
  real clients, then switch **ON**. While OFF, everyone can use the app (safe
  rollout); while ON, only ACTIVE devices work.
- **App Update tab:** to ship an update — build the APK, upload it to Firebase
  **Storage** (console → Storage → upload → copy the download URL), paste the URL
  here, set the new `versionCode` (must match the APK's, e.g. 29), write a
  changelog, Save. Clients get the update prompt on next launch. Tick **Force
  update** (or raise min supported version) to block old versions.

## Notes
- The TV app is fail-open: if Firebase is unreachable or enforcement is OFF, it
  works normally. An activated device also keeps working through network blips
  (cached entitlement).
- Client devices need the one-time Android "install unknown apps" permission for
  the in-app updater (standard for sideloaded Fire TV apps).
