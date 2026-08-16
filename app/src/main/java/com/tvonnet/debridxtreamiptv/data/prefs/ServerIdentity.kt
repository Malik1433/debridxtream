package com.tvonnet.debridxtreamiptv.data.prefs

import java.security.MessageDigest
import java.util.Locale

/**
 * Which IPTV provider the data on this device belongs to, as one short string.
 *
 * The catalogue, favourites, watch history and watched state are all keyed by `streamId` and carry
 * no record of where they came from — two providers both number their streams from 1, so nothing in
 * the data itself can answer "is this still mine?". This is that missing answer, derived from the
 * only thing that identifies a provider: the host it is served from and the account used to reach
 * it.
 *
 * **Hashed on purpose.** The username is half of a credential; the fingerprint has to be comparable,
 * not readable, and it is written to the plain preference file (the encrypted store can fail over to
 * plain on some Fire OS builds, and a digest is safe either way).
 *
 * Normalisation is deliberately narrow. Scheme and path are ignored, because `http://x.tv`,
 * `https://x.tv` and `http://x.tv/` are one provider written three ways and must not read as three
 * switches. The username is NOT lower-cased — Xtream treats it case-sensitively, so two different
 * accounts on one host stay two different fingerprints.
 */
object ServerIdentity {

    /** Fingerprint meaning "no server". Distinct from any real one, so a logout is a change too. */
    const val NONE: String = ""

    /**
     * @return a stable 16-hex-char digest of (host[:port], username), or [NONE] when either half is
     *   missing — an incomplete credential identifies nothing and must never look like a provider.
     */
    fun fingerprint(serverUrl: String?, username: String?): String {
        val authority = authorityOf(serverUrl)
        if (authority.isEmpty() || username.isNullOrBlank()) return NONE
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$authority|$username".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * host[:port], lower-cased, with the scheme and everything after the authority dropped.
     *
     * Parsed by hand rather than with `Uri.parse` so this stays a plain unit-testable object with no
     * Android dependency — and so a scheme-less "tvonnet.xyz:8080", which customers do paste, is
     * still read as a host and not as a path.
     */
    private fun authorityOf(serverUrl: String?): String {
        val raw = serverUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val afterScheme = raw.substringAfter("://", raw)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // Credentials embedded in the URL (user:pass@host) belong to the account half, not the host.
        return authority.substringAfterLast('@').lowercase(Locale.ROOT)
    }
}
