package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridAccountRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Keeps Real-Debrid tokens refreshed in the background so sessions don't expire.
 */
@HiltWorker
class DebridAuthRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val debridAccountRepository: DebridAccountRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "DebridAuthRefreshWorker"
        const val WORK_NAME = "debrid_auth_refresh"
        private const val REFRESH_BUFFER_MILLIS = 12 * 60 * 60 * 1000L // 12 hours
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val authState = debridAccountRepository.getCachedAuthState()
            if (authState == null) {
                Log.d(TAG, "No auth state found; skipping refresh.")
                return@withContext Result.success()
            }

            if (authState.refreshToken.isBlank() && !debridAccountRepository.refreshAuthStateIfNeeded(REFRESH_BUFFER_MILLIS).let { it is com.tvonnet.debridxtreamiptv.data.Result.Success }) {
                Log.w(TAG, "Missing refresh token and not a static token; skipping refresh.")
                return@withContext Result.success()
            }

            return@withContext when (val refreshResult = debridAccountRepository.refreshAuthStateIfNeeded(REFRESH_BUFFER_MILLIS)) {
                is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                    Log.d(TAG, "Auth refresh successful.")
                    Result.success()
                }
                is com.tvonnet.debridxtreamiptv.data.Result.Error -> {
                    if (isAuthInvalid(refreshResult.exception)) {
                        Log.w(TAG, "Auth refresh failed with invalid credentials; clearing auth state.")
                        debridAccountRepository.clearAuthState()
                        Result.success()
                    } else {
                        Log.w(TAG, "Auth refresh failed; will retry.", refreshResult.exception)
                        Result.retry()
                    }
                }
                else -> Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth refresh worker failed.", e)
            Result.retry()
        }
    }

    private fun isAuthInvalid(error: Exception?): Boolean {
        if (error is HttpException) {
            return error.code() == 400 || error.code() == 401 || error.code() == 403
        }
        val message = error?.message?.lowercase() ?: return false
        return message.contains("invalid_grant") ||
            message.contains("invalid_token") ||
            message.contains("invalid token") ||
            message.contains("unauthorized") ||
            message.contains("forbidden")
    }
}
