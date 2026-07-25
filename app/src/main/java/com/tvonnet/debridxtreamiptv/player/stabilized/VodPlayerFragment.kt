package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint

/**
 * The VOD (movie / series episode) player screen (P27 fragment split).
 *
 * For now it inherits all behaviour from [BasePlayerFragment], which still branches on
 * `contentType != LIVE_TV` internally. P28 moves the VOD-only logic — episode browser /
 * up-next prompt / in-player source panel / VOD seek overlay / 2-step BACK — down into this
 * subclass so the base is left with only the shared scaffolding (≤600).
 */
@UnstableApi
@AndroidEntryPoint
class VodPlayerFragment : BasePlayerFragment()
