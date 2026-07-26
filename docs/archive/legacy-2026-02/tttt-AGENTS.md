# Working Agreement (for this repo)

## Verification rule (mandatory)

Whenever code is implemented/changed:

- Run a verification step before handing off.
  - Minimum: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`
  - Prefer: `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (and any more specific tests if applicable)
- In the final update, explicitly state which Gradle tasks were run and whether they passed.

