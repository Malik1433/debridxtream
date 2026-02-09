package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridApiService
import com.tvonnet.debridxtreamiptv.data.debrid.di.RealDebridAuthorized
import com.tvonnet.debridxtreamiptv.data.debrid.di.RealDebridOAuth
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridAuthState
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridCredentialsResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridDeviceCodeResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTokenResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentAddResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridUnrestrictLinkResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridUserResponse
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.resultOf
import javax.inject.Inject

/**
 * Remote data source that wraps Real-Debrid REST/OAuth calls with Result semantics.
 */
class RealDebridRemoteDataSource @Inject constructor(
    @RealDebridOAuth private val oauthService: RealDebridApiService,
    @RealDebridAuthorized private val authorizedService: RealDebridApiService
) {

    suspend fun requestDeviceCode(clientId: String): Result<RealDebridDeviceCodeResponse> = resultOf {
        Log.d("RealDebridRemote", "Requesting device code with clientId: ${clientId.take(4)}...")
        val response = oauthService.requestDeviceCode(clientId)
        Log.d(
            "RealDebridRemote",
            "Device code received: userCode=${maskCode(response.userCode)}, deviceCode=${maskCode(response.deviceCode)}, expires_in=${response.expiresIn}"
        )
        response
    }.onSuccess { response ->
        Log.i(
            "RealDebridRemote",
            "Device code request successful: userCode=${maskCode(response.userCode)}, verificationUrl=${response.verificationUrl}"
        )
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Device code request failed", error)
    }

    suspend fun pollDeviceCredentials(
        clientId: String,
        deviceCode: String
    ): Result<RealDebridCredentialsResponse> = resultOf {
        Log.d("RealDebridRemote", "Polling device credentials with clientId: ${clientId.take(4)}..., deviceCode: ${deviceCode.take(4)}...")
        val response = oauthService.pollDeviceCredentials(clientId, deviceCode)
        Log.d("RealDebridRemote", "Credentials poll response received, has clientSecret: ${response.clientSecret.isNotEmpty()}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Device credentials polling successful")
    }.onFailure { error ->
        Log.w("RealDebridRemote", "Device credentials polling failed (this may be expected during polling)", error)
    }

    suspend fun exchangeDeviceCodeForToken(
        credentials: RealDebridCredentialsResponse,
        deviceCode: String
    ): Result<RealDebridAuthState> = resultOf {
        Log.d("RealDebridRemote", "Exchanging device code for token with clientId: ${credentials.clientId.take(4)}..., deviceCode: ${deviceCode.take(4)}...")
        val tokenResponse = oauthService.exchangeDeviceCodeForToken(
            clientId = credentials.clientId,
            clientSecret = credentials.clientSecret,
            code = deviceCode
        )
        Log.d("RealDebridRemote", "Token exchange successful, access_token_length: ${tokenResponse.accessToken.length}")
        tokenResponse.toAuthState(credentials.clientId, credentials.clientSecret)
    }.onSuccess { authState ->
        Log.i("RealDebridRemote", "Device code exchange successful, user is now authenticated")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Device code exchange failed", error)
    }

    suspend fun refreshAccessToken(state: RealDebridAuthState): Result<RealDebridAuthState> = resultOf {
        Log.d("RealDebridRemote", "Refreshing access token with clientId: ${state.clientId.take(4)}...")
        val tokenResponse = oauthService.refreshAccessToken(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            refreshToken = state.refreshToken
        )
        Log.d("RealDebridRemote", "Token refresh successful, new access_token_length: ${tokenResponse.accessToken.length}")
        tokenResponse.toAuthState(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            existingRefreshToken = state.refreshToken
        )
    }.onSuccess { authState ->
        Log.i("RealDebridRemote", "Access token refresh successful")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Access token refresh failed", error)
    }

    suspend fun fetchUserInfo(): Result<RealDebridUserResponse> = resultOf {
        Log.d("RealDebridRemote", "Fetching user info")
        val response = authorizedService.fetchUserInfo()
        Log.d("RealDebridRemote", "User info received: username=${response.username}, id=${response.id}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "User info fetch successful: ${response.username} (${response.email})")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "User info fetch failed", error)
    }

    suspend fun addMagnet(magnet: String, host: String? = null): Result<RealDebridTorrentAddResponse> = resultOf {
        Log.d("RealDebridRemote", "Adding magnet link: ${magnet.take(30)}...")
        val response = authorizedService.addMagnet(magnet = magnet, host = host)
        Log.d("RealDebridRemote", "Magnet added successfully, id: ${response.id}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Magnet link added successfully: ${response.id}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to add magnet link", error)
    }

    suspend fun getTorrentInfo(torrentId: String): Result<RealDebridTorrentInfoResponse> = resultOf {
        Log.d("RealDebridRemote", "Getting torrent info for id: $torrentId")
        val response = authorizedService.getTorrentInfo(torrentId)
        Log.d("RealDebridRemote", "Torrent info received: status=${response.status}, filename=${response.filename}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Torrent info fetch successful: ${response.filename} (${response.status})")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to get torrent info for id: $torrentId", error)
    }

    suspend fun unrestrictLink(link: String, password: String? = null): Result<RealDebridUnrestrictLinkResponse> = resultOf {
        Log.d("RealDebridRemote", "Unrestricting link: ${link.take(30)}...")
        val response = authorizedService.unrestrictLink(link = link, password = password)
        Log.d("RealDebridRemote", "Link unrestricted successfully, filename: ${response.filename}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Link unrestrict successful: ${response.filename}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to unrestrict link", error)
    }

    suspend fun selectFiles(torrentId: String, files: String): Result<Unit> = resultOf {
        Log.d("RealDebridRemote", "Selecting files for torrent $torrentId: $files")
        authorizedService.selectFiles(torrentId, files)
    }.onSuccess {
        Log.i("RealDebridRemote", "Files selected successfully for torrent $torrentId")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to select files for torrent $torrentId", error)
    }

    private fun maskCode(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "n/a"
        return when {
            trimmed.length <= 2 -> "**"
            else -> trimmed.take(2) + "***"
        }
    }
}


