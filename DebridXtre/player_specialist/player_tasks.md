PLAYER SPECIALIST AGENT

goal:
- create standalone PlayerActivity using ExoPlayer
- MUST release onBackPressed / onStop

design:
- file: player/PlayerActivity.kt
- intent extras: STREAM_URL, STREAM_TITLE
- layout: res/layout/player_activity.xml with PlayerView
- onBackPressed:
  - player.stop()
  - player.release()
  - playerView.player = null
  - finish()

output target:
- /global/design_output.md under ### PLAYER_SPECIALIST
