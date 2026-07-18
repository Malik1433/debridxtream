package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.tvonnet.debridxtreamiptv.BuildConfig
import java.security.MessageDigest

/**
 * Anti-repack signature self-check.
 *
 * A cracked build is typically the app decompiled, the licence gate patched to
 * `return true`, then re-signed with the attacker's OWN key. This compares the
 * certificate the app is actually running under against the expected RELEASE cert
 * SHA-256 baked in at build time ([BuildConfig.EXPECTED_SIGNING_SHA256]).
 *
 * SAFE-BY-DEFAULT: when the expected hash is empty (no `keystore.properties`, i.e.
 * every debug/dev build today) the check is a NO-OP and returns `true`. It only
 * starts rejecting once the owner sets up release signing + the expected hash, so
 * shipping it now cannot break anything.
 *
 * This is not tamper-PROOF — a determined attacker can also patch out this check —
 * but combined with private release signing it stops the trivial "repack, re-sign
 * with any key, redistribute" crack, which is the common case.
 */
object AppIntegrity {

    private const val TAG = "AppIntegrity"

    @Volatile private var cached: Boolean? = null

    /** True when the running signature matches the expected release cert (or no expected hash is set). */
    fun isTrustedSignature(context: Context): Boolean {
        cached?.let { return it }
        val expected = BuildConfig.EXPECTED_SIGNING_SHA256.trim()
        val ok = if (expected.isEmpty()) {
            true // not configured → self-check disabled
        } else {
            val actual = currentSignatureSha256(context)
            (actual != null && actual.equals(expected, ignoreCase = true)).also {
                if (!it) Log.w(TAG, "signature mismatch — app is not running under the expected release certificate")
            }
        }
        cached = ok
        return ok
    }

    /** SHA-256 (uppercase hex, no separators) of the app's first signing certificate, or null. */
    private fun currentSignatureSha256(context: Context): String? = try {
        val pm = context.packageManager
        val pkg = context.packageName
        val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners
            signers?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        certBytes?.let {
            MessageDigest.getInstance("SHA-256").digest(it)
                .joinToString("") { b -> "%02X".format(b) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read signing certificate", e)
        null
    }
}
