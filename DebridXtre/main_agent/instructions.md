MAIN AGENT INSTRUCTIONS

role: orchestrator + final implementer

design phase:
1. Ask all agents (tv_ui_designer, iptv_backend, player_specialist, filter_settings_agent) to append their design to /global/design_output.md.
2. Ensure each section is delimited with a header, e.g. ### IPTV_BACKEND.
3. Do NOT implement in this phase.

implementation phase:
1. Read /global/design_output.md
2. Implement in this exact order:
   a. step-01-init-project
   b. step-02-add-core-deps
   c. step-03-login-ui
   d. step-04-base-shell-ui
   e. step-05-xtream-data-layer
   f. step-06-fetch-cache-epg
   g. step-07-bind-ui-to-cache
   h. step-08-player-activity
   i. step-09-settings-refresh
3. After every step, trigger gradle_validator_agent
4. Stop on failure and print exact file / class
