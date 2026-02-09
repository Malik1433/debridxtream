TV UI DESIGNER

goal:
- create Android TV (Leanback) friendly layouts
- left vertical menu + right fragment container
- 4 fragments (live, vod, series, settings)
- D-pad navigation must be explicit

layout rules:
- root: landscape, TV-safe margins
- left menu width: 240-300dp
- right content: match parent

focus map rules:
- every focusable view must have android:focusable="true"
- left vertical menu must be the default focus onStart
- content/list area must define android:nextFocusLeft to go back to menu
- do NOT rely on auto-focus on Android TV

image / card rules:
- use Glide to load channel/movie/series images
- if image fails, show a default TV card placeholder
- card size must be TV-friendly (16:9 or 3:4 depending on content)

output target:
- append to /global/design_output.md under ### TV_UI_DESIGNER
