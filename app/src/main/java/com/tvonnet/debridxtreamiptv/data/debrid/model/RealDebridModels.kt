package com.tvonnet.debridxtreamiptv.data.debrid.model

import com.google.gson.annotations.SerializedName
import kotlin.math.max

data class RealDebridDeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String, // Short code for user to enter (e.g., "J45KBGK6")
    @SerializedName("interval") val interval: Int, // Polling interval in seconds
    @SerializedName("expires_in") val expiresIn: Int, // Expiration time in seconds
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("direct_verification_url") val directVerificationUrl: String? = null
)

data class RealDebridCredentialsResponse(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String
)

data class RealDebridTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresInSeconds: Long,
    @SerializedName("refresh_token") val refreshToken: String?, // Make nullable
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("scope") val scope: String?
) {
    fun toAuthState(
        clientId: String,
        clientSecret: String,
        issuedAtMillis: Long = System.currentTimeMillis(),
        existingRefreshToken: String? = null // Add fallback
    ): RealDebridAuthState {
        val expiresAt = issuedAtMillis + max(0, expiresInSeconds) * 1000
        // Use new refresh token if available, otherwise keep existing one
        val finalRefreshToken = refreshToken ?: existingRefreshToken ?: ""
        
        return RealDebridAuthState(
            clientId = clientId,
            clientSecret = clientSecret,
            accessToken = accessToken,
            refreshToken = finalRefreshToken,
            tokenType = tokenType,
            scope = scope,
            issuedAt = issuedAtMillis,
            expiresAt = expiresAt
        )
    }
}

data class RealDebridAuthState(
    val clientId: String,
    val clientSecret: String,
    val accessToken: String,
    val refreshToken: String, // Kept non-nullable in state (must exist)
    val tokenType: String,
    val scope: String?,
    val issuedAt: Long,
    val expiresAt: Long
) {
    fun isExpired(referenceTime: Long = System.currentTimeMillis()): Boolean {
        return referenceTime >= expiresAt
    }

    fun willExpireWithin(windowMillis: Long, referenceTime: Long = System.currentTimeMillis()): Boolean {
        return !isExpired(referenceTime) && (expiresAt - referenceTime) <= windowMillis
    }

    fun withUpdatedTokens(response: RealDebridTokenResponse, issuedAtMillis: Long = System.currentTimeMillis()): RealDebridAuthState {
        return response.toAuthState(
            clientId = clientId,
            clientSecret = clientSecret,
            issuedAtMillis = issuedAtMillis,
            existingRefreshToken = refreshToken // Pass current refresh token as fallback
        )
    }
}

data class RealDebridUserResponse(
    @SerializedName("id") val id: Int?,
    @SerializedName("username") val username: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("points") val points: Int?,
    @SerializedName("locale") val locale: String?,
    @SerializedName("avatar") val avatar: String?,
    @SerializedName("type") val accountType: String?,
    @SerializedName("premium") val isPremium: Int?,
    @SerializedName("expiration") val premiumExpiration: String?
)

data class RealDebridTorrentAddResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("uri") val uri: String?
)

data class RealDebridTorrentInfoResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("filename") val filename: String?,
    @SerializedName("original_filename") val originalFilename: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("progress") val progress: Double?,
    @SerializedName("seeders") val seeders: Int?,
    @SerializedName("speed") val speed: Long?,
    @SerializedName("links") val links: List<String>?,
    @SerializedName("files") val files: List<RealDebridTorrentFile>?
)

data class RealDebridTorrentFile(
    @SerializedName("id") val id: Int?,
    @SerializedName("path") val path: String?,
    @SerializedName("bytes") val bytes: Long?,
    @SerializedName("selected") val selected: Int?
)

data class RealDebridUnrestrictLinkResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("filename") val filename: String?,
    @SerializedName("download") val downloadUrl: String?,
    @SerializedName("streaming") val streamingUrl: String?,
    @SerializedName("filesize") val fileSizeBytes: Long?,
    @SerializedName("limits") val limits: RealDebridLimits?,
    @SerializedName("host") val host: String?,
    @SerializedName("host_icon") val hostIcon: String?,
    @SerializedName("alternative") val alternativeLinks: List<String>?
)

data class RealDebridLimits(
    @SerializedName("traffic") val traffic: Long?,
    @SerializedName("download") val download: Long?
)

data class RealDebridErrorResponse(
    @SerializedName("error") val error: String?,
    @SerializedName("error_code") val errorCode: Int?,
    @SerializedName("error_description") val errorDescription: String?
)

class NotAuthenticatedException(message: String = "Not authenticated with Real-Debrid") :  Exception(message)


