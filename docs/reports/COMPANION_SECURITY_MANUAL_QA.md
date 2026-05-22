# Companion Security
Status: verified
Scope: companion sync and payload handling
Done:
- local-network guard
- PIN lockout
- URL validation
- Firestore log reduction
Open:
- manual payload round-trip
- wrong-PIN live retest
- invalid URL live retest
- non-local rejection retest
Risk:
- transport edge cases and payload drift
Proof:
- `:app:assembleDebug` OK
- install OK on `192.168.0.84:5555`
- install OK on `192.168.0.21:5555`
- launch OK on both devices
- no app crash signatures on recent log scans
Next:
- run the manual payload checklist when needed

## Updates
- 2026-05-22: compacted the report to state/proof/next format.
