package com.tvonnet.debridxtreamiptv.core.network

/**
 * A sealed class wrapper for network responses.
 * Distinguishes between successful responses, API errors (4xx/5xx), and network exceptions (IO).
 */
sealed class NetworkResult<T> {
    
    /**
     * Successful response with parsed data.
     */
    data class Success<T>(val data: T) : NetworkResult<T>()
    
    /**
     * API Error (e.g., 401 Unauthorized, 404 Not Found, 500 Server Error).
     * @param code HTTP status code.
     * @param message Error message from the server or default.
     */
    data class Error<T>(val code: Int, val message: String?) : NetworkResult<T>()
    
    /**
     * Network Exception (e.g., No Internet, Timeout, DNS failure).
     * @param exception The throwable caught during the request.
     */
    data class Exception<T>(val exception: Throwable) : NetworkResult<T>()
}
