package com.tvonnet.debridxtreamiptv.player.stabilized

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up (timeshift) URLs for an Xtream provider - C1 of docs/reports/LIVE_CATCHUP_DESIGN.md.
 *
 * `/timeshift/<user>/<pass>/<minutes>/<YYYY-MM-DD:HH-MM>/<stream_id>.ts`, the start in the
 * PROVIDER's time zone and to the minute (the only precision the endpoint has). The C0 device test
 * showed the provider ignores `minutes` and serves the ~10-minute chunk holding the start; it is
 * still sent, as the endpoint requires it.
 *
 * Built at play time from the stream id and a moment - never stored (CLAUDE.md: never store an
 * absolute stream URL as identity; it embeds the credentials).
 */
internal object CatchupUrlBuilder {

    /** Where and as whom - the same three values the live URL is built from. */
    data class Account(val serverUrl: String, val username: String, val password: String)

    fun url(account: Account, streamId: String, startEpochMs: Long, minutes: Int, serverZone: TimeZone): String =
        "${account.serverUrl.trimEnd('/')}/timeshift/${account.username}/${account.password}/" +
            "${minutes.coerceAtLeast(1)}/${startParam(startEpochMs, serverZone)}/$streamId.ts"

    /** `2026-09-27:13-10` in [zone]. */
    fun startParam(epochMs: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }.format(Date(epochMs))
}
