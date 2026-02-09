package com.tvonnet.debridxtreamiptv.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

/**
 * Safely executes a Retrofit API call and maps the result to a [NetworkResult].
 * 
 * @param dispatcher The CoroutineDispatcher to execute the call on (defaults to IO).
 * @param apiCall The suspending API call function returning a Retrofit [Response].
 * @return [NetworkResult.Success], [NetworkResult.Error], or [NetworkResult.Exception].
 */
suspend fun <T> safeApiCall(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return withContext(dispatcher) {
        try {
            val response = apiCall()
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    // Handle nullable body case if T is nullable, or treat as error for non-null T.
                    // For now, we assume null body with success code is technically an error for data APIs,
                    // or we could allow it if T is nullable. 
                    // Given strict typing, we'll return Error if body is null but expected.
                    // Ideally check if T is Unit or Void, but generic erasure makes it hard.
                    // Fallback: 204 No Content is success but null body.
                    
                    if (response.code() == 204) {
                         // Safe cast hack or strictly require non-null. 
                         // For this project's API style, empty body is usually error or strictly mapped.
                         // Let's assume error for now to be safe, or message "No content".
                         NetworkResult.Error(response.code(), "Response body is null")
                    } else {
                        NetworkResult.Error(response.code(), "Response body is null")
                    }
                }
            } else {
                val msg = response.errorBody()?.string() ?: response.message()
                NetworkResult.Error(response.code(), msg)
            }
        } catch (e: IOException) {
            // Network failures (no internet, timeout)
            NetworkResult.Exception(e)
        } catch (e: Exception) {
            // Parsing errors or other unexpected exceptions
            NetworkResult.Exception(e)
        }
    }
}
