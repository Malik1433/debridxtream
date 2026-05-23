# Companion Security
Status: automated QA verified; manual payload QA open
Scope: companion sync and payload handling
Done:
- local-network guard
- PIN lockout
- URL validation
- shared URL validation across local server, remote pairing, and Firestore companion setup
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
- 2026-05-23: `CompanionUrlValidatorTest` OK; install/launch/crash scan OK on `192.168.0.84:5555`
- 2026-05-23: launch/crash scan OK on `192.168.0.21:5555`
Next:
- run the manual payload checklist when needed

## Updates
- 2026-05-22: compacted the report to state/proof/next format.
- 2026-05-23: rejected unsafe companion URLs before persistence in all companion ingress paths.
- 2026-05-23: dual-device automated launch QA completed after validator patch.
