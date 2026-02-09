# DESIGN COMMAND
Collect designs from tv_ui_designer, iptv_backend, player_specialist, filter_settings_agent and write them to /global/design_output.md. 
Make sure each agent writes under its own ### SECTION. 
Do NOT implement now.

# IMPLEMENT COMMAND
Read /global/design_output.md and implement steps in TV-first order:
1) init project
2) deps
3) login ui
4) base shell ui
5) xtream data layer
6) fetch + cache + epg
7) bind ui to cache
8) player activity
9) settings + refresh
After each step, ask gradle_validator_agent to run.
