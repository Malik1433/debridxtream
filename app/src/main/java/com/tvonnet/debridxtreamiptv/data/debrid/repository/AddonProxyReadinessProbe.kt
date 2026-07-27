package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** The four fields that identify what is being probed; they always travel together. */
internal data class ProbeSubject(
    val url: String,
    val provider: String? = null,
    val sourceName: String? = null,
    val sourceType: String? = null,
) {
    fun fields(): Map<String, Any?> = mapOf(
        "provider" to provider,
        "sourceName" to sourceName,
        "sourceType" to sourceType,
    )
}

/** Whether an addon-proxy URL will actually play right now. */
enum class AddonProxyReadiness {
    READY,
    UNCERTAIN,
    TERMINAL
}

/**
 * C6: asks an addon proxy whether a stream is actually ready *before* the player commits to it.
 *
 * Addon proxies (MediaFusion, AIOStreams) return a perfectly valid-looking URL for a torrent their
 * debrid backend has not cached yet. Handing that to ExoPlayer produced the "picked a source, got
 * an endless spinner" complaint, so this probes with a HEAD (following one redirect by hand) and
 * classifies the answer.
 *
 * The three-way answer is the whole point and must not collapse to a boolean:
 *  - [AddonProxyReadiness.TERMINAL] — this will never play (auth/gone, an error page, or a
 *    redirect whose target says "not downloaded"): advance to the next source.
 *  - [AddonProxyReadiness.UNCERTAIN] — the probe itself was inconclusive (network failure, 204, a
 *    suspiciously tiny body): **try to play anyway**. Treating this as failure would drop working
 *    sources whenever the network hiccuped.
 *  - [AddonProxyReadiness.READY] — go.
 *
 * Bodies moved verbatim from `DebridPlaybackRepository`.
 */
internal class AddonProxyReadinessProbe(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        /** A "video" this small is an error page wearing a video content type. */
        private const val SUSPICIOUS_BODY_MAX_BYTES = 4096L
        private const val REDIRECT_PROBE_TIMEOUT_SECONDS = 5L
    }

    fun isRequired(
        url: String?,
        provider: String?,
        sourceName: String?,
        sourceType: String?
    ): Boolean {
        return !url.isNullOrBlank() && (
            DebridUrlRules.isAddonProxyPlaybackUrl(url) ||
                DebridUrlRules.isAddonProxySource(provider) ||
                DebridUrlRules.isAddonProxySource(sourceName) ||
                DebridUrlRules.isAddonProxySource(sourceType)
            )
    }

    /**
     * Headers the probe and the player must agree on — they have to be identical, or the proxy can
     * answer one thing to the probe and another to playback.
     */
    fun effectiveHeaders(headers: Map<String, String>?): Map<String, String> {
        val normalized = LinkedHashMap<String, String>()
        var hasUserAgent = false
        var hasAccept = false
        headers.orEmpty().forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            if (name.equals("Accept", ignoreCase = true)) hasAccept = true
            normalized[name] = value
        }
        if (!hasUserAgent) normalized["User-Agent"] = "Stremio/1.0"
        if (!hasAccept) normalized["Accept"] = "*/*"
        return normalized
    }

    suspend fun readiness(
        subject: ProbeSubject,
        headers: Map<String, String>?,
        diagnosticsContext: Context?
    ): Result<AddonProxyReadiness> {
        val url = subject.url

        if (!isRequired(url, subject.provider, subject.sourceName, subject.sourceType)) {
            record(
                diagnosticsContext, "readiness_check_finished", subject, headers,
                outcomeFields(required = false, readiness = AddonProxyReadiness.READY, reasonCode = "NOT_REQUIRED")
            )
            return Success(AddonProxyReadiness.READY)
        }

        record(
            diagnosticsContext, "readiness_check_started", subject, effectiveHeaders(headers),
            mapOf("required" to true)
        )

        return try {
            val (readiness, statusCode, wasRedirect) = probe(url, headers)
            record(
                diagnosticsContext, "readiness_check_finished", subject, effectiveHeaders(headers),
                outcomeFields(required = true, readiness = readiness, reasonCode = readiness.name) +
                    mapOf("httpStatusCode" to statusCode, "isRedirect" to wasRedirect)
            )
            Success(readiness)
        } catch (e: Exception) {
            record(
                diagnosticsContext, "readiness_check_finished", subject, effectiveHeaders(headers),
                outcomeFields(
                    required = true,
                    readiness = AddonProxyReadiness.UNCERTAIN,
                    reasonCode = PlaybackDiagnosticsRecorder.sanitizeReason(e::class.java.simpleName)
                )
            )
            // A probe that could not complete says nothing about the stream — fail OPEN.
            Success(AddonProxyReadiness.UNCERTAIN)
        }
    }

    private suspend fun probe(
        url: String,
        headers: Map<String, String>?
    ): Triple<AddonProxyReadiness, Int, Boolean> {
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .head()
            .applyPlaybackHeaders(headers)
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val result = if (response.isRedirect) {
                    followRedirectAndClassify(client, response, headers)
                } else {
                    classify(response)
                }
                Triple(result, response.code, response.isRedirect)
            }
        }
    }

    private fun Request.Builder.applyPlaybackHeaders(headers: Map<String, String>?): Request.Builder {
        effectiveHeaders(headers).forEach { (name, value) ->
            header(name, value)
        }
        return this
    }

    private fun classify(response: Response): AddonProxyReadiness {
        if (response.isRedirect) {
            return AddonProxyReadiness.UNCERTAIN
        }
        if (DebridUrlRules.isTerminalAddonProxyStatus(response.code)) {
            return AddonProxyReadiness.TERMINAL
        }
        if (response.code == 204) {
            return AddonProxyReadiness.UNCERTAIN
        }
        if (!response.isSuccessful) {
            return AddonProxyReadiness.UNCERTAIN
        }

        val contentType = response.header("Content-Type")?.lowercase()
        if (contentType != null && DebridUrlRules.isAddonProxyErrorContentType(contentType)) {
            return AddonProxyReadiness.TERMINAL
        }

        val contentLength = response.header("Content-Length")?.toLongOrNull()
        if (contentLength != null &&
            contentLength in 1..SUSPICIOUS_BODY_MAX_BYTES &&
            contentType?.contains("video") != true
        ) {
            return AddonProxyReadiness.UNCERTAIN
        }

        return AddonProxyReadiness.READY
    }

    /**
     * The redirect target is where a proxy admits the torrent is not cached, so the location is
     * checked *before* spending a request on it; otherwise one byte is fetched to see what is
     * really there.
     */
    private fun followRedirectAndClassify(
        client: OkHttpClient,
        response: Response,
        headers: Map<String, String>?
    ): AddonProxyReadiness {
        val location = response.header("Location") ?: return AddonProxyReadiness.UNCERTAIN
        if (DebridUrlRules.isAddonProxyErrorTarget(location)) return AddonProxyReadiness.TERMINAL
        val redirectUrl = response.request.url.resolve(location) ?: return AddonProxyReadiness.UNCERTAIN
        val probeClient = client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(REDIRECT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(redirectUrl)
            .get()
            .header("Range", "bytes=0-0")
            .applyPlaybackHeaders(headers)
            .build()
        return try {
            probeClient.newCall(request).execute().use { probeResponse ->
                classify(probeResponse)
            }
        } catch (_: Exception) {
            AddonProxyReadiness.UNCERTAIN
        }
    }

    // ── diagnostics ───────────────────────────────────────────────────────

    private fun outcomeFields(
        required: Boolean,
        readiness: AddonProxyReadiness,
        reasonCode: String?,
    ): Map<String, Any?> = mapOf(
        "required" to required,
        "ready" to (readiness == AddonProxyReadiness.READY),
        "readinessOutcome" to readiness.name,
        "reasonCode" to reasonCode,
    )

    private fun record(
        diagnosticsContext: Context?,
        event: String,
        subject: ProbeSubject,
        headers: Map<String, String>?,
        fields: Map<String, Any?>,
    ) {
        diagnosticsContext?.let { context ->
            PlaybackDiagnosticsRecorder.record(
                context,
                event,
                PlaybackDiagnosticsRecorder.urlFields(context, subject.url) +
                    PlaybackDiagnosticsRecorder.headerFields(headers) +
                    subject.fields() +
                    fields
            )
        }
    }
}
