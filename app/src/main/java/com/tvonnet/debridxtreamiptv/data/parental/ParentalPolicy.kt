package com.tvonnet.debridxtreamiptv.data.parental

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The parental-controls decisions, with no Android in them (2026-09-03).
 *
 * - **Hidden means hidden everywhere.** When controls are on and the session is locked, adult
 *   categories — and the streams that belong to them — are removed at the data facade, so every
 *   surface (TV rails, phone chips, the guide, "all channels") agrees without knowing why.
 * - **Unlocking is per session and expires.** A correct PIN opens the adult categories for
 *   [SESSION_UNLOCK_MS]; a process restart or [lockNow] closes them again. There is no
 *   "remember me" — that would be a toggle-off with extra steps.
 * - **The PIN is never stored.** A random salt and a SHA-256 of `salt + pin` are; verification is
 *   a constant-time compare. Four digits are not a secret against an adult with the device, they
 *   are a gate against a child with the remote — the standard every TV platform settled on.
 */
class ParentalPolicy(private val now: () -> Long = System::currentTimeMillis) {

    @Volatile private var unlockedUntilMs: Long = 0L

    val isUnlocked: Boolean get() = now() < unlockedUntilMs

    fun unlockForSession() { unlockedUntilMs = now() + SESSION_UNLOCK_MS }

    fun lockNow() { unlockedUntilMs = 0L }

    /** Controls on and the session locked → adult content is hidden. */
    fun hidesAdult(enabled: Boolean): Boolean = enabled && !isUnlocked

    fun <T> filterCategories(enabled: Boolean, categories: List<T>, name: (T) -> String?): List<T> =
        if (!hidesAdult(enabled)) categories
        else categories.filterNot { AdultContentDetector.isAdultCategoryName(name(it)) }

    fun <T> filterByCategory(enabled: Boolean, items: List<T>, adultIds: Set<String>, categoryId: (T) -> String?): List<T> =
        if (!hidesAdult(enabled) || adultIds.isEmpty()) items
        else items.filterNot { categoryId(it) in adultIds }

    companion object {
        const val SESSION_UNLOCK_MS = 30L * 60 * 1000
        const val PIN_LENGTH = 4

        /** ASCII digits only: `Char.isDigit()` also accepts ١٢٣٤ and ४८२१, which would hash differently from the wheel's 0-9. */
        fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        fun newSalt(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun hashPin(salt: String, pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Constant-time: a wrong PIN and a right one take the same time to reject. */
        fun matches(storedHash: String?, salt: String?, pin: String): Boolean {
            if (storedHash.isNullOrEmpty() || salt.isNullOrEmpty()) return false
            val candidate = hashPin(salt, pin)
            if (candidate.length != storedHash.length) return false
            var diff = 0
            for (i in candidate.indices) diff = diff or (candidate[i].code xor storedHash[i].code)
            return diff == 0
        }
    }
}
