package com.tvonnet.debridxtreamiptv.data.debrid.model

import retrofit2.HttpException

enum class DebridFailureType {
    RATE_LIMITED,
    COPYRIGHT_BLOCKED,
    LEGAL_RESTRICTION,
    AUTH_REQUIRED,
    NOT_CACHED,
    UNAVAILABLE,
    NETWORK,
    UNKNOWN
}

class DebridResolutionException(
    val type: DebridFailureType,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    val retryable: Boolean
        get() = type == DebridFailureType.NETWORK || type == DebridFailureType.UNKNOWN

    val terminal: Boolean
        get() = !retryable
}

object DebridFailureClassifier {

    // A classification rule: an HTTP status and/or observed message markers that map to a
    // failure type and its user message. Matching is "any code OR any marker".
    private class FailureRule(
        val type: DebridFailureType,
        val message: String,
        val httpCodes: Set<Int> = emptySet(),
        val markers: List<String> = emptyList(),
    ) {
        fun matches(httpCode: Int?, raw: String): Boolean =
            httpCode in httpCodes || markers.any { it in raw }
    }

    // Ordered, first match wins — the old when-chain as data, original branch order kept.
    // Every marker spelling was observed in the wild; a missing one means the player
    // buffers forever on an error page. Add to the lists, never prune them.
    private val FAILURE_RULES = listOf(
        FailureRule(
            type = DebridFailureType.RATE_LIMITED,
            message = "Rate limit hit. Wait a little before trying more sources.",
            httpCodes = setOf(429),
            markers = listOf("429", "rate limit"),
        ),
        FailureRule(
            type = DebridFailureType.LEGAL_RESTRICTION,
            message = "This source is blocked by its provider. Try another source.",
            httpCodes = setOf(451),
            markers = listOf("451", "legal restriction", "legal restrictions", "restricted due to"),
        ),
        FailureRule(
            type = DebridFailureType.COPYRIGHT_BLOCKED,
            message = "This source was removed or blocked. Try another source.",
            markers = listOf("copyright", "removed from debrid", "removed from the debrid", "infringement"),
        ),
        FailureRule(
            type = DebridFailureType.AUTH_REQUIRED,
            message = "The add-on rejected this request. Check the add-on in Settings.",
            httpCodes = setOf(401, 403),
            markers = listOf("unauthorized", "forbidden"),
        ),
        FailureRule(
            type = DebridFailureType.UNAVAILABLE,
            message = "This source is unavailable. Try another source.",
            httpCodes = setOf(404),
            markers = listOf("404", "not found"),
        ),
        FailureRule(
            type = DebridFailureType.NOT_CACHED,
            message = "This source is not cached. Try a VERIFIED source.",
            markers = listOf("not cached", "torrent_not_downloaded"),
        ),
        // B1/A1: "Torrent not ready after N attempts" (poll timeout), a torrent
        // with no usable links, and a blank unrestrict all mean the same thing —
        // this torrent is NOT usable right now. Classifying them UNKNOWN made
        // them retryable (a second full ~63s poll cycle) AND a dead-end in the
        // picker (no auto-advance). NOT_CACHED is terminal-for-this-source and
        // auto-advances to the next source.
        FailureRule(
            type = DebridFailureType.NOT_CACHED,
            message = "This source isn't ready yet. Trying the next source…",
            markers = listOf("not ready", "no links available", "no download url", "waiting_files_selection"),
        ),
        FailureRule(
            type = DebridFailureType.NETWORK,
            message = "Network issue while contacting the add-on. Try again.",
            markers = listOf("timeout", "socket", "connection", "network", "unknownhost"),
        ),
    )

    fun classify(error: Throwable?): DebridResolutionException {
        if (error is DebridResolutionException) return error

        val httpException = error as? HttpException
        val httpCode = httpException?.code()
        val retryAfterSeconds = httpException
            ?.response()
            ?.headers()
            ?.get("Retry-After")
            ?.toLongOrNull()

        val raw = buildString {
            append(error?.message.orEmpty())
            append(' ')
            append(error?.toString().orEmpty())
        }.lowercase()

        val rule = FAILURE_RULES.firstOrNull { it.matches(httpCode, raw) }
            ?: return DebridResolutionException(
                type = DebridFailureType.UNKNOWN,
                message = "This source could not be played. Try another source.",
                cause = error
            )
        return DebridResolutionException(
            type = rule.type,
            // Only the rate-limit reply carries a honoured Retry-After, exactly as before.
            retryAfterSeconds = if (rule.type == DebridFailureType.RATE_LIMITED) retryAfterSeconds else null,
            message = rule.message,
            cause = error
        )
    }
}
