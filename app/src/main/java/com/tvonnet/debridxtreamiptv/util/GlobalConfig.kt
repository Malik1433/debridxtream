package com.tvonnet.debridxtreamiptv.util

/**
 * Simple Singleton for Global Configuration.
 * Holds credentials and provides manual URL concatenation.
 */
object GlobalConfig {
    var baseUrl: String = ""
    var username: String = ""
    var password: String = ""

    fun isInitialized(): Boolean {
        return baseUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
    }

    /**
     * Correctly resolves relative image paths.
     * Xtream API images are served directly from the server root/path, NOT via the streaming endpoints.
     */
    fun resolveIconUrl(path: String?): String? {
        // Strip out any potentially embedded newlines or spaces (e.g. "path \n.png")
        val trimmedPath = path?.replace("\\s+".toRegex(), "")
        if (trimmedPath.isNullOrBlank()) {
            android.util.Log.w("IPTV_POSTER", "resolveIconUrl received NULL or BLANK path - returning null")
            return null
        }
        
        android.util.Log.d("IPTV_POSTER", "Resolving path: '$trimmedPath' (baseUrl: '$baseUrl')")
        
        // If it's already an absolute URL, return as is
        if (trimmedPath.startsWith("http")) {
            android.util.Log.d("IPTV_POSTER", "Resolved as Absolute: $trimmedPath")
            return trimmedPath
        }

        // Relative path resolution
        val base = baseUrl
        if (base.isNullOrBlank()) {
            android.util.Log.w("IPTV_POSTER", "WARNING: Returning raw path '$trimmedPath' - cannot resolve yet (baseUrl is EMPTY)")
            return trimmedPath // Return raw instead of null so it can be resolved later
        }

        val resolved = if (trimmedPath.startsWith("/")) {
            "${base.trimEnd('/')}$trimmedPath"
        } else {
            "${base.trimEnd('/')}/$trimmedPath"
        }
        
        android.util.Log.d("IPTV_POSTER", "Resolved Relative: $trimmedPath -> $resolved")
        return resolved
    }

    /**
     * Legacy support for older reconstruction, acts identically to resolveIconUrl
     * since we determined credentials are not used in static image paths.
     */
    fun getAbsoluteUrl(path: String?, type: String): String? {
        return resolveIconUrl(path)
    }
}
