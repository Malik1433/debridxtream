package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import javax.inject.Inject

/**
 * Remote data source that wraps Real-Debrid REST/OAuth calls with Result semantics.
 */
class RealDebridRemoteDataSource @Inject constructor(
    @RealDebridOAuth private val oauthService: RealDebridApiService,
    @RealDebridAuthorized private val authorizedService: RealDebridApiService
) {

    suspend fun requestDeviceCode(clientId: String): Result<RealDebridDeviceCodeResponse> = resultOf {
        Log.d("RealDebridRemote", "Requesting device code with clientId=${SensitiveLogRedactor.describeSecret(clientId)}")
        val response = oauthService.requestDeviceCode(clientId)
        Log.d(
            "RealDebridRemote",
            "Device code received: userCode=${maskCode(response.userCode)}, deviceCode=${SensitiveLogRedactor.describeSecret(response.deviceCode)}, expires_in=${response.expiresIn}"
        )
        response
    }.onSuccess { response ->
        Log.i(
            "RealDebridRemote",
            "Device code request successful: userCode=${maskCode(response.userCode)}, verificationUrl=${SensitiveLogRedactor.describeUrl(response.verificationUrl)}"
        )
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Device code request failed: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun pollDeviceCredentials(
        clientId: String,
        deviceCode: String
    ): Result<RealDebridCredentialsResponse> = resultOf {
        Log.d("RealDebridRemote", "Polling device credentials with clientId=${SensitiveLogRedactor.describeSecret(clientId)}, deviceCode=${SensitiveLogRedactor.describeSecret(deviceCode)}")
        val response = oauthService.pollDeviceCredentials(clientId, deviceCode)
        Log.d("RealDebridRemote", "Credentials poll response received, has client secret: ${response.clientSecret.isNotEmpty()}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Device credentials polling successful")
    }.onFailure { error ->
        Log.w("RealDebridRemote", "Device credentials polling failed (this may be expected during polling): error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun exchangeDeviceCodeForToken(
        credentials: RealDebridCredentialsResponse,
        deviceCode: String
    ): Result<RealDebridAuthState> = resultOf {
        Log.d("RealDebridRemote", "Exchanging device code for token with clientId=${SensitiveLogRedactor.describeSecret(credentials.clientId)}, deviceCode=${SensitiveLogRedactor.describeSecret(deviceCode)}")
        val tokenResponse = oauthService.exchangeDeviceCodeForToken(
            clientId = credentials.clientId,
            clientSecret = credentials.clientSecret,
            code = deviceCode
        )
        Log.d("RealDebridRemote", "Token exchange successful, access length: ${tokenResponse.accessToken.length}")
        tokenResponse.toAuthState(credentials.clientId, credentials.clientSecret)
    }.onSuccess { authState ->
        Log.i("RealDebridRemote", "Device code exchange successful, user is now authenticated")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Device code exchange failed: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun refreshAccessToken(state: RealDebridAuthState): Result<RealDebridAuthState> = resultOf {
        Log.d("RealDebridRemote", "Refreshing access token with clientId=${SensitiveLogRedactor.describeSecret(state.clientId)}")
        val tokenResponse = oauthService.refreshAccessToken(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            refreshToken = state.refreshToken
        )
        Log.d("RealDebridRemote", "Token refresh successful, new access length: ${tokenResponse.accessToken.length}")
        tokenResponse.toAuthState(
            clientId = state.clientId,
            clientSecret = state.clientSecret,
            existingRefreshToken = state.refreshToken
        )
    }.onSuccess { authState ->
        Log.i("RealDebridRemote", "Access token refresh successful")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Access token refresh failed: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun fetchUserInfo(): Result<RealDebridUserResponse> = resultOf {
        Log.d("RealDebridRemote", "Fetching user info")
        val response = authorizedService.fetchUserInfo()
        Log.d("RealDebridRemote", "User info received: id=${SensitiveLogRedactor.describeHash(response.id?.toString())}, account=${SensitiveLogRedactor.describeSecret(response.username)}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "User info fetch successful: account=${SensitiveLogRedactor.describeSecret(response.username)}, email=${SensitiveLogRedactor.describeSecret(response.email)}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "User info fetch failed: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun addMagnet(magnet: String, host: String? = null): Result<RealDebridTorrentAddResponse> = resultOf {
        Log.d("RealDebridRemote", "Adding magnet link: ${SensitiveLogRedactor.describeUrl(magnet)}")
        val response = authorizedService.addMagnet(magnet = magnet, host = host)
        Log.d("RealDebridRemote", "Magnet added successfully, id=${SensitiveLogRedactor.describeHash(response.id)}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Magnet link added successfully: id=${SensitiveLogRedactor.describeHash(response.id)}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to add magnet link: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun getTorrentInfo(torrentId: String): Result<RealDebridTorrentInfoResponse> = resultOf {
        Log.d("RealDebridRemote", "Getting torrent info for id=${SensitiveLogRedactor.describeHash(torrentId)}")
        val response = authorizedService.getTorrentInfo(torrentId)
        Log.d("RealDebridRemote", "Torrent info received: status=${response.status}, filename=${SensitiveLogRedactor.describeSecret(response.filename)}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Torrent info fetch successful: filename=${SensitiveLogRedactor.describeSecret(response.filename)}, status=${response.status}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to get torrent info for id=${SensitiveLogRedactor.describeHash(torrentId)}: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun getInstantAvailability(infoHash: String): Result<Boolean> = resultOf {
        val normalized = normalizeInfoHash(infoHash)
        Log.d("RealDebridRemote", "Checking instant availability for hash=${SensitiveLogRedactor.describeHash(normalized)}")
        val response = authorizedService.getInstantAvailability(normalized)
        parseInstantAvailability(response, normalized)
    }.onSuccess { cached ->
        Log.i("RealDebridRemote", "Instant availability check completed: cached=$cached")
    }.onFailure { error ->
        Log.w("RealDebridRemote", "Instant availability check failed; source cache state remains unknown: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun unrestrictLink(link: String, password: String? = null): Result<RealDebridUnrestrictLinkResponse> = resultOf {
        Log.d("RealDebridRemote", "Unrestricting link: ${SensitiveLogRedactor.describeUrl(link)}")
        val response = authorizedService.unrestrictLink(link = link, password = password)
        Log.d("RealDebridRemote", "Link unrestricted successfully, filename=${SensitiveLogRedactor.describeSecret(response.filename)}")
        response
    }.onSuccess { response ->
        Log.i("RealDebridRemote", "Link unrestrict successful: filename=${SensitiveLogRedactor.describeSecret(response.filename)}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to unrestrict link: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun selectFiles(torrentId: String, files: String): Result<Unit> = resultOf {
        Log.d("RealDebridRemote", "Selecting files for torrent=${SensitiveLogRedactor.describeHash(torrentId)}, fileSelectionPresent=${files.isNotBlank()}")
        authorizedService.selectFiles(torrentId, files)
    }.onSuccess {
        Log.i("RealDebridRemote", "Files selected successfully for torrent=${SensitiveLogRedactor.describeHash(torrentId)}")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to select files for torrent=${SensitiveLogRedactor.describeHash(torrentId)}: error=${SensitiveLogRedactor.describeException(error)}")
    }

    suspend fun deleteTorrent(torrentId: String): Result<Unit> = resultOf {
        Log.d("RealDebridRemote", "Deleting torrent=${SensitiveLogRedactor.describeHash(torrentId)}")
        authorizedService.deleteTorrent(torrentId)
    }.onSuccess {
        Log.i("RealDebridRemote", "Torrent ${SensitiveLogRedactor.describeHash(torrentId)} deleted successfully")
    }.onFailure { error ->
        Log.e("RealDebridRemote", "Failed to delete torrent=${SensitiveLogRedactor.describeHash(torrentId)}: error=${SensitiveLogRedactor.describeException(error)}")
    }

    private fun maskCode(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "n/a"
        return when {
            trimmed.length <= 2 -> "**"
            else -> trimmed.take(2) + "***"
        }
    }

    private fun normalizeInfoHash(infoHash: String): String {
        return infoHash.trim().lowercase()
    }

    private fun parseInstantAvailability(root: JsonObject, infoHash: String): Boolean {
        val hashNode = root.entrySet()
            .firstOrNull { it.key.equals(infoHash, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: root

        return hashNode.entrySet().any { (_, value) -> hasAvailableItem(value) }
    }

    private fun hasAvailableItem(value: JsonElement?): Boolean {
        if (value == null || value.isJsonNull) return false
        return when {
            value.isJsonArray -> value.asJsonArray.size() > 0
            value.isJsonObject -> value.asJsonObject.entrySet().any { (_, child) -> hasAvailableItem(child) }
            else -> false
        }
    }
}


