package com.tvonnet.debridxtreamiptv.network

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.http.*
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CompanionConfigServer: A local Ktor-based web server that allows remote configuration
 * of IPTV and Debrid settings from a companion web app.
 * 
 * Port: 8080
 * Path: /api/config (POST)
 * Path: /api/status (GET)
 */
@Singleton
class CompanionConfigServer @Inject constructor(
    private val credentialsPreferences: CredentialsPreferences,
    private val debridPreferences: DebridPreferences
) {
    private val pinAbuseLock = Any()
    private var wrongPinAttempts = 0
    private var pinLockoutUntilMs = 0L

    private val maxWrongPinAttempts = 3
    private val pinLockoutDurationMs = 30_000L

    private var server: ApplicationEngine? = null
    
    /**
     * Callback for UI synchronization feedback
     */
    var onConfigSynced: (() -> Unit)? = null
    
    /**
     * The dynamic PIN required for requests to be accepted.
     */
    var currentPin: String = ""

    /**
     * Starts the Ktor server on port 8080.
     * Configures CORS to allow local network requests and ContentNegotiation for JSON.
     */
    fun start() {
        if (server != null) {
            Log.d("CompanionServer", "Server is already running.")
            return
        }
        
        try {
            // Generate a dynamic 4-digit PIN for this session
            currentPin = (1000..9999).random().toString()
            
            // Changed port to 8085 to avoid potential conflicts
            server = embeddedServer(CIO, port = 8085, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    gson()
                }
                install(CORS) {
                    buildCorsAllowedHosts(8085).forEach { allowedHost ->
                        allowHost(allowedHost, schemes = listOf("http"))
                    }
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Get)
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader("X-Pairing-PIN")
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        Log.e("CompanionServer", "Uncaught exception in server", cause)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Server error"))
                    }
                }
                routing {
                    webAndStatusRoutes()

                    // Main configuration endpoint
                    post("/api/config") { handleConfigPost(call) }
                }
            }.start(wait = false)
            Log.d("CompanionServer", "Server started successfully on port 8085")
        } catch (e: Exception) {
            Log.e("CompanionServer", "Failed to start server", e)
        }
    }

    // Manual static file serving for API 21-25 compatibility
    // Avoids JarEntry.getLastModifiedTime() crash on older devices
    private fun Route.webAndStatusRoutes() {
        get("/") {
            val stream = javaClass.classLoader.getResourceAsStream("web/index.html")
            if (stream != null) {
                call.respondBytes(stream.readBytes(), ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/{static_path...}") {
            val path = call.parameters.getAll("static_path")?.joinToString("/") ?: ""
            val resourcePath = "web/$path"
            val stream = javaClass.classLoader.getResourceAsStream(resourcePath)

            if (stream != null) {
                val mimeType = ContentType.fromFilePath(resourcePath).firstOrNull()
                    ?: ContentType.Application.OctetStream
                call.respondBytes(stream.readBytes(), mimeType)
            } else {
                // SPA fallback: return index.html for unknown virtual routes
                val indexStream = javaClass.classLoader.getResourceAsStream("web/index.html")
                if (indexStream != null) {
                    call.respondBytes(indexStream.readBytes(), ContentType.Text.Html)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }

        // Health check endpoint
        get("/api/status") {
            call.respond(mapOf(
                "status" to "online",
                "device" to "DebridXtream TV",
                "version" to "1.0"
            ))
        }
    }

    // The /api/config handler, verbatim: local-network gate, PIN lockout/verification,
    // optional IPTV validation, then save + notify.
    private suspend fun handleConfigPost(call: ApplicationCall) {
        try {
            if (!isRequestFromLocalNetwork(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "success" to false,
                    "message" to "Companion config is only available from the local network"
                ))
                return
            }

            if (rejectIfPinLockedOut(call)) return
            if (rejectIfPinWrong(call)) return

            synchronized(pinAbuseLock) {
                wrongPinAttempts = 0
                pinLockoutUntilMs = 0L
            }

            val payload = call.receive<CompanionConfigPayload>()

            // Validate IPTV credentials before saving
            if (payload.iptv != null && payload.iptv.serverUrl.isNotBlank() && payload.iptv.username.isNotBlank()) {
                val validation = validateIptvCredentials(payload.iptv)
                if (!validation.success) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "IPTV Validation Failed: ${validation.message}"
                    ))
                    return
                }
            }

            saveConfiguration(payload)

            // Notify listeners on the main thread
            withContext(Dispatchers.Main) {
                onConfigSynced?.invoke()
            }

            call.respond(mapOf(
                "success" to true,
                "message" to "Configuration synced successfully! TV app will reload now."
            ))
        } catch (e: Exception) {
            Log.e("CompanionServer", "Failed to parse or validate config payload", e)
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "success" to false,
                "message" to "Invalid config payload"
            ))
        }
    }

    // 429 with Retry-After while a lockout window is active. True = request handled.
    private suspend fun rejectIfPinLockedOut(call: ApplicationCall): Boolean {
        val now = System.currentTimeMillis()
        val lockoutUntilMs = synchronized(pinAbuseLock) { pinLockoutUntilMs }
        if (now >= lockoutUntilMs) return false
        val retryAfterSeconds = ((lockoutUntilMs - now + 999) / 1000).coerceAtLeast(1)
        call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
        call.respond(HttpStatusCode.TooManyRequests, mapOf(
            "success" to false,
            "message" to "Too many incorrect PIN attempts. Try again in ${retryAfterSeconds}s."
        ))
        return true
    }

    // Wrong/missing PIN: 401, or 429 when this attempt tripped the lockout. True = handled.
    private suspend fun rejectIfPinWrong(call: ApplicationCall): Boolean {
        val pinHeader = call.request.header("X-Pairing-PIN")
        if (pinHeader == currentPin) return false

        val now = System.currentTimeMillis()
        val lockoutTriggered = synchronized(pinAbuseLock) {
            wrongPinAttempts += 1
            if (wrongPinAttempts >= maxWrongPinAttempts) {
                wrongPinAttempts = 0
                pinLockoutUntilMs = now + pinLockoutDurationMs
                true
            } else {
                false
            }
        }

        if (lockoutTriggered) {
            call.response.headers.append(HttpHeaders.RetryAfter, (pinLockoutDurationMs / 1000).toString())
            call.respond(HttpStatusCode.TooManyRequests, mapOf(
                "success" to false,
                "message" to "Too many incorrect PIN attempts. Try again in ${pinLockoutDurationMs / 1000}s."
            ))
        } else {
            call.respond(HttpStatusCode.Unauthorized, mapOf(
                "success" to false,
                "message" to "Incorrect or missing X-Pairing-PIN"
            ))
        }
        return true
    }

    private suspend fun validateIptvCredentials(iptv: IptvConfig): ValidationResult {
        return try {
            val url = if (iptv.serverUrl.endsWith("/")) iptv.serverUrl else "${iptv.serverUrl}/"
            val apiService = com.tvonnet.debridxtreamiptv.data.remote.XtreamRetrofitClient.create(url)
            val response = apiService.login(iptv.username, iptv.password)
            
            if (response.isSuccessful && response.body() != null) {
                val loginBody = response.body()!!
                if (loginBody.user_info?.status == "Active" || loginBody.user_info?.auth == 1) {
                    ValidationResult(true, "Validation Success")
                } else {
                    ValidationResult(false, "Account is not active or unauthorized")
                }
            } else {
                val errorMsg = when (response.code()) {
                    401 -> "Invalid Username or Password"
                    404 -> "Server URL not found"
                    else -> "Server returned error: ${response.code()}"
                }
                ValidationResult(false, errorMsg)
            }
        } catch (e: Exception) {
            Log.e("CompanionServer", "Validation exception", e)
            ValidationResult(false, "Connection Failed: ${e.message}")
        }
    }

    private data class ValidationResult(val success: Boolean, val message: String)

    /**
     * Gracefully stops the Ktor server.
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.d("CompanionServer", "Server stopped")
    }

    /**
     * Persists the received configuration into SharedPreferences.
     */
    private fun saveConfiguration(payload: CompanionConfigPayload) {
        // 1. Sync IPTV Credentials
        payload.iptv?.let { iptv ->
            if (iptv.serverUrl.isNotBlank() && iptv.username.isNotBlank()) {
                Log.d("CompanionServer", "Syncing IPTV credentials")
                credentialsPreferences.saveSyncedCredentials(
                    serverUrl = iptv.serverUrl,
                    username = iptv.username,
                    password = iptv.password
                )
            }
        }
        
        // 2. Sync Debrid Settings
        payload.debrid?.let { debrid ->
            if (!debrid.token.isNullOrBlank()) {
                Log.d("CompanionServer", "Syncing Real-Debrid token")
                debridPreferences.saveRealDebridToken(debrid.token)
            }
            val mediaFusionUrl = debrid.mediaFusionUrl?.trim().orEmpty()
            if (mediaFusionUrl.isNotEmpty()) {
                val safeMediaFusionUrl = CompanionUrlValidator.normalizeSafeHttpUrl(mediaFusionUrl)
                if (safeMediaFusionUrl != null) {
                    Log.d("CompanionServer", "Syncing MediaFusion URL")
                    debridPreferences.saveMediaFusionUrl(safeMediaFusionUrl)
                } else {
                    Log.w("CompanionServer", "Rejected invalid MediaFusion URL from companion payload")
                }
            }
            val stremioUrls = CompanionUrlValidator.normalizeSafeAddonUrls(debrid.stremioAddonUrls)
            if (stremioUrls.isNotEmpty()) {
                Log.d("CompanionServer", "Syncing Stremio addon URLs: count=${stremioUrls.size}")
                debridPreferences.setStremioAddonUrls(stremioUrls)
            } else if (!debrid.stremioAddonUrls.isNullOrEmpty()) {
                Log.w("CompanionServer", "Rejected invalid Stremio addon URLs from companion payload")
            }
            val registryUrls = CompanionUrlValidator.normalizeSafeAddonUrls(debrid.addonRegistryUrls)
            if (registryUrls.isNotEmpty()) {
                Log.d("CompanionServer", "Syncing addon registry URLs: count=${registryUrls.size}")
                // Replace semantics: remove all then re-add. We keep this scoped to companion sync only.
                val existing = debridPreferences.getAddonRegistryUrls()
                existing.forEach { debridPreferences.removeAddonRegistryUrl(it) }
                registryUrls.forEach { debridPreferences.addAddonRegistryUrl(it) }
            } else if (!debrid.addonRegistryUrls.isNullOrEmpty()) {
                Log.w("CompanionServer", "Rejected invalid addon registry URLs from companion payload")
            }
        }
    }

    private fun buildCorsAllowedHosts(port: Int): Set<String> {
        val hosts = linkedSetOf(
            "localhost:$port",
            "127.0.0.1:$port"
        )

        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return hosts
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses

            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    hosts.add("${address.hostAddress}:$port")
                }
            }
        }

        return hosts
    }

    private fun isRequestFromLocalNetwork(call: ApplicationCall): Boolean {
        val remoteAddress = call.request.local.remoteHost.trim()
        val requestAddress = runCatching { InetAddress.getByName(remoteAddress) }.getOrNull() ?: return false

        if (requestAddress.isLoopbackAddress || requestAddress.isAnyLocalAddress) {
            return true
        }

        if (requestAddress is Inet4Address) {
            val localSubnets = buildLocalIpv4Subnets()
            if (localSubnets.isNotEmpty()) {
                return localSubnets.any { subnet ->
                    requestAddress.matchesSubnet(subnet.address, subnet.prefixLength)
                }
            }

            return requestAddress.isSiteLocalAddress || requestAddress.isLinkLocalAddress
        }

        return false
    }

    private fun buildLocalIpv4Subnets(): List<LocalIpv4Subnet> {
        val subnets = mutableListOf<LocalIpv4Subnet>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return subnets

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                continue
            }

            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val localAddress = interfaceAddress.address
                val prefixLength = interfaceAddress.networkPrefixLength.toInt()
                if (localAddress is Inet4Address && prefixLength in 0..32) {
                    subnets.add(LocalIpv4Subnet(localAddress.address, prefixLength))
                }
            }
        }

        return subnets
    }

    private fun Inet4Address.matchesSubnet(networkAddress: ByteArray, prefixLength: Int): Boolean {
        val targetAddress = address
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8

        for (index in 0 until fullBytes) {
            if (targetAddress[index] != networkAddress[index]) {
                return false
            }
        }

        if (remainingBits == 0) {
            return true
        }

        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (targetAddress[fullBytes].toInt() and mask) == (networkAddress[fullBytes].toInt() and mask)
    }

    private data class LocalIpv4Subnet(
        val address: ByteArray,
        val prefixLength: Int
    )
}

/**
 * Data models for the configuration payload.
 */
data class CompanionConfigPayload(
    val iptv: IptvConfig? = null,
    val debrid: DebridConfig? = null
)

data class IptvConfig(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class DebridConfig(
    val token: String? = null,
    val mediaFusionUrl: String? = null,
    val stremioAddonUrls: List<String>? = null,
    val addonRegistryUrls: List<String>? = null
)
