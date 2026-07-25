package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint

/**
 * The Live-TV player screen (P27 fragment split).
 *
 * For now it inherits all behaviour from [BasePlayerFragment], which still branches on
 * `contentType == LIVE_TV` internally. Subsequent P27 steps move the live-only logic — the
 * live tuner / cinematic OSD / legacy EPG strip / channel zap / channel browser / the
 * shared-player adopt + hand-off + live key routing — down into this subclass so the base
 * shrinks toward the ≤600 guardrail.
 */
@UnstableApi
@AndroidEntryPoint
class LivePlayerFragment : BasePlayerFragment()
