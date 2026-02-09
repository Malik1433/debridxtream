# How to use in Cursor 2.0

1. Open this project in Cursor.
2. Open `global/global_rules.md` and click **Include in context**.
3. Open `global/project_plan.yaml` and include it too.
4. Open `main_agent/commands.md`.
5. Run the **Design command** first — this will ask all agents to write into `/global/design_output.md`.
6. Then run the **Implement command** — this will build the Android TV app step-by-step in the TV-first order.
7. After every implement step, run `gradle_validator_agent/gradle_tasks.md` to make sure the project still builds.
8. Then continue with future phases (multi-playlist, TMDB, Debrid).
