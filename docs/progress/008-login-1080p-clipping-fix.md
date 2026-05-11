# TASK 008 - Login 1080p Clipping Fix

## Final Result

PASS

## Scope

Fixed only the 1920x1080 login screen clipping risk for the mobile setup button.

No login logic, credential storage, device identity, companion flow, or Home/Live/VOD/Series/Debrid/Player code was changed.

## Files Changed

- `app/src/main/res/layout/fragment_login.xml`

## Behavior Before

- The login screen loaded at 1920x1080, but the bottom mobile setup button was at risk of being clipped when focused.
- Focus scaling could expand the button near the lower visible edge.
- The layout hierarchy had tight vertical spacing and clipping behavior around the scroll/card content.

## Behavior After

- The login scroll/card hierarchy allows safe focus overflow.
- The login card has safer vertical padding for the bottom button.
- The mobile setup button remains fully visible at 1920x1080 in normal and focused states.
- D-pad order remains unchanged:
  - server
  - username
  - password
  - login
  - mobile setup
  - up returns to login

## Layout Verification

Device tested:

- `192.168.0.21:5555`

Screen:

- `1920x1080`

Mobile setup button bounds:

- Normal visible bounds: `[616,868][1304,956]`
- Focused visible bounds: `[599,866][1321,958]`
- Screen bottom: `1080`

Result:

- PASS - button is fully visible with focus scale applied.

Screenshot:

- `artifacts/task008_login_1080p.png`

## D-pad Verification

Clean focus capture on `192.168.0.21:5555`:

| Step | Focused View | Text | Bounds |
| --- | --- | --- | --- |
| initial | `com.debridxtream.tv:id/et_server_url` | Server URL | `[616,322][1304,418]` |
| down1 | `com.debridxtream.tv:id/et_username` | Username | `[616,445][1304,541]` |
| down2 | `com.debridxtream.tv:id/et_password` | Password | `[616,568][1304,664]` |
| down3 | `com.debridxtream.tv:id/btn_login` | SIGN IN | `[599,694][1321,794]` |
| down4 | `com.debridxtream.tv:id/btn_setup_phone` | CONNECT MOBILE | `[599,866][1321,958]` |
| up_from_mobile | `com.debridxtream.tv:id/btn_login` | SIGN IN | `[599,694][1321,794]` |

Result:

- PASS - no dead zone found in the required path.

## Build and Test Verification

| Verification | Result |
| --- | --- |
| `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain` | PASS |
| `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console plain` | PASS |
| `.\gradlew.bat :app:assembleDebug --no-daemon --console plain` | PASS |
| `adb -s 192.168.0.21:5555 install -r app\build\outputs\apk\debug\app-debug.apk` | PASS |
| Launch MainActivity on `192.168.0.21:5555` | PASS |

## What Was Not Touched

- Login validation/auth logic
- Credential keys or storage behavior
- Device identity architecture
- Companion pairing behavior
- Home, Live, VOD, Series, Debrid, or Player modules
- Login visual redesign

## Remaining Notes

- This was a small XML-only clipping pass.
- The current login layout existed before this pass in the working tree; this task only adjusted spacing/clipping/focus safety around the existing layout.

