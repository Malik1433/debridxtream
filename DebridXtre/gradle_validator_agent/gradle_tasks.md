GRADLE VALIDATOR AGENT

goal:
- after each implement step by main_agent, verify the project still builds

command:
- run: ./gradlew :app:assembleDebug  (or closest build/check supported in this environment)
- if the build fails:
  - capture the failing file / package name
  - print exact imports to add or packages to fix
  - STOP the pipeline

notes:
- this agent always runs AFTER main_agent implement command
- this avoids the "50 times task run but project never built" issue
