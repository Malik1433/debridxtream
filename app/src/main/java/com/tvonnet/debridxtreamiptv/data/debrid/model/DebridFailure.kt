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

        return when {
            httpCode == 429 || raw.contains("429") || raw.contains("rate limit") -> {
                DebridResolutionException(
                    type = DebridFailureType.RATE_LIMITED,
                    message = "Real-Debrid rate limit hit. Wait a little before trying more sources.",
                    retryAfterSeconds = retryAfterSeconds,
                    cause = error
                )
            }
            httpCode == 451 || raw.contains("451") || raw.contains("legal restriction") ||
                raw.contains("legal restrictions") || raw.contains("restricted due to") -> {
                DebridResolutionException(
                    type = DebridFailureType.LEGAL_RESTRICTION,
                    message = "This source is blocked by Real-Debrid. Try another source.",
                    cause = error
                )
            }
            raw.contains("copyright") || raw.contains("removed from debrid") ||
                raw.contains("removed from the debrid") || raw.contains("infringement") -> {
                DebridResolutionException(
                    type = DebridFailureType.COPYRIGHT_BLOCKED,
                    message = "This source was removed or blocked by Real-Debrid. Try another source.",
                    cause = error
                )
            }
            httpCode == 401 || httpCode == 403 || raw.contains("unauthorized") || raw.contains("forbidden") -> {
                DebridResolutionException(
                    type = DebridFailureType.AUTH_REQUIRED,
                    message = "Real-Debrid session expired. Sign in again.",
                    cause = error
                )
            }
            httpCode == 404 || raw.contains("404") || raw.contains("not found") -> {
                DebridResolutionException(
                    type = DebridFailureType.UNAVAILABLE,
                    message = "This source is unavailable. Try another source.",
                    cause = error
                )
            }
            raw.contains("not cached") || raw.contains("torrent_not_downloaded") -> {
                DebridResolutionException(
                    type = DebridFailureType.NOT_CACHED,
                    message = "This source is not cached on Real-Debrid. Try a VERIFIED source.",
                    cause = error
                )
            }
            // B1/A1: "Torrent not ready after N attempts" (poll timeout), a torrent
            // with no usable links, and a blank unrestrict all mean the same thing —
            // this torrent is NOT usable right now. Classifying them UNKNOWN made
            // them retryable (a second full ~63s poll cycle) AND a dead-end in the
            // picker (no auto-advance). NOT_CACHED is terminal-for-this-source and
            // auto-advances to the next source.
            raw.contains("not ready") || raw.contains("no links available") ||
                raw.contains("no download url") || raw.contains("waiting_files_selection") -> {
                DebridResolutionException(
                    type = DebridFailureType.NOT_CACHED,
                    message = "This source isn't ready on Real-Debrid. Trying the next source…",
                    cause = error
                )
            }
            raw.contains("timeout") || raw.contains("socket") || raw.contains("connection") ||
                raw.contains("network") || raw.contains("unknownhost") -> {
                DebridResolutionException(
                    type = DebridFailureType.NETWORK,
                    message = "Network issue while contacting Real-Debrid. Try again.",
                    cause = error
                )
            }
            else -> DebridResolutionException(
                type = DebridFailureType.UNKNOWN,
                message = "Real-Debrid could not play this source. Try another source.",
                cause = error
            )
        }
    }
}
