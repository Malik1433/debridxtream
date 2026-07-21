package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.remote.XtreamRetrofitClient
import com.tvonnet.debridxtreamiptv.util.GlobalConfig

/**
 * Phase 7: the Xtream provider session — credentials + the built API service + the server URL — lifted
 * out of the XtreamRepository god-class into one cohesive owner. This is the state that was crossing
 * every domain (login, sync, Live, VOD, Series, EPG, URL building) as four loose mutable fields.
 *
 * Behaviour is preserved byte-for-byte from the old XtreamRepository.initialize():
 *  - idempotent for identical credentials (skip the expensive Retrofit/OkHttp rebuild),
 *  - on a real (re)build it normalises the URL, publishes GlobalConfig.baseUrl, and mints the service,
 *  - the post-init hooks (EpgParser init, search-index warm, memory monitoring) run inside the same
 *    try/catch via [onInitialized] so a failure there still nulls apiService exactly as before.
 *
 * The repository exposes username/password/baseUrl/apiService as read-only views onto this object, so
 * the ~140 existing read sites are unchanged. NOTE: the current provider-switch behaviour is frozen by
 * XtreamRepositoryStreamLookupTest — ensureInitialized keeps the first provider once a service exists.
 */
internal class XtreamSession(private val context: Context) {

    @Volatile
    var apiService: XtreamApiService? = null
        private set
    var username: String = "username"
        private set
    var password: String = "password"
        private set
    var baseUrl: String = ""
        private set

    fun isInitialized(): Boolean = apiService != null

    /**
     * Lock-free fast-path check: is the session already built for exactly these credentials? Used by
     * ensureInitialized to skip work when we are already on the requested provider, without taking the
     * initialize() lock. A stale read at worst causes one extra initialize() call, which re-checks
     * under the lock — so it is safe.
     */
    fun matches(serverUrl: String, username: String, password: String): Boolean =
        apiService != null &&
            this.username == username &&
            this.password == password &&
            this.baseUrl == serverUrl.trimEnd('/') + "/"

    /**
     * (Re)build the session for the given credentials. Returns true iff a real (re)initialisation
     * happened (i.e. not the idempotent skip and not a failure), so the caller can run its own
     * post-init side effects only when appropriate.
     *
     * @Synchronized preserves the original guard against concurrent first-callers rebuilding the
     * whole Retrofit/OkHttp stack twice (MainActivity + HomeFragment + every browse ViewModel all
     * call initialize()).
     */
    @Synchronized
    fun initialize(
        baseUrl: String,
        username: String,
        password: String,
        onInitialized: () -> Unit
    ): Boolean {
        val normalizedUrlPre = baseUrl.trimEnd('/') + "/"
        if (apiService != null &&
            this.username == username &&
            this.password == password &&
            this.baseUrl == normalizedUrlPre
        ) {
            return false
        }
        return try {
            this.username = username
            this.password = password
            this.baseUrl = baseUrl.trimEnd('/') + "/"
            GlobalConfig.baseUrl = this.baseUrl
            val normalizedUrl = this.baseUrl
            // Week 8: Pass context for HTTP caching support
            apiService = XtreamRetrofitClient.create(normalizedUrl, context)
            onInitialized()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize API service", e)
            apiService = null
            false
        }
    }

    private companion object {
        private const val TAG = "XtreamRepository"
    }
}
