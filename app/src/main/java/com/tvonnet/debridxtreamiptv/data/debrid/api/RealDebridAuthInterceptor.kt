package com.tvonnet.debridxtreamiptv.data.debrid.api

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that injects the Real-Debrid OAuth access token into every authorized request.
 */
class RealDebridAuthInterceptor @Inject constructor(
    private val accessTokenProvider: AccessTokenProvider,
    private val tokenRefresher: javax.inject.Provider<TokenRefresher> // Lazily inject refresher
) : Interceptor, okhttp3.Authenticator {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = accessTokenProvider.getAccessToken()
        
        val newRequest = if (!token.isNullOrEmpty()) {
            originalRequest.newBuilder()
                .header(AUTHORIZATION_HEADER, "Bearer $token")
                .build()
        } else {
            originalRequest
        }
        
        return chain.proceed(newRequest)
    }

    override fun authenticate(route: okhttp3.Route?, response: Response): okhttp3.Request? {
        // synchronized to prevent multiple threads refreshing at once
        synchronized(this) {
            val currentToken = accessTokenProvider.getAccessToken()

            // If the token in the request is different from current, it means it's already refreshed
            val requestToken = response.request.header(AUTHORIZATION_HEADER)?.substringAfter("Bearer ")
            if (requestToken != currentToken && !currentToken.isNullOrEmpty()) {
                return response.request.newBuilder()
                    .header(AUTHORIZATION_HEADER, "Bearer $currentToken")
                    .build()
            }
            
            // Try to refresh
            val refresher = tokenRefresher.get()
            val newToken = refresher.refreshTokenBlocking()
            
            return if (!newToken.isNullOrEmpty()) {
                response.request.newBuilder()
                    .header(AUTHORIZATION_HEADER, "Bearer $newToken")
                    .build()
            } else {
                null // Give up
            }
        }
    }

    interface AccessTokenProvider {
        fun getAccessToken(): String?
    }
    
    interface TokenRefresher {
        fun refreshTokenBlocking(): String?
    }

    private companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
    }
}


