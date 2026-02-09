FILTER / SETTINGS AGENT

goal:
- create settings fragment
- allow manual refresh and interval selection

tasks:
- build SettingsFragment
- items:
  - "Refresh IPTV data now"
  - "Update interval: 12h / 24h / 48h"
- on Refresh -> call repository forceRefresh() (same as step-06)

output target:
- /global/design_output.md under ### FILTER_SETTINGS_AGENT
