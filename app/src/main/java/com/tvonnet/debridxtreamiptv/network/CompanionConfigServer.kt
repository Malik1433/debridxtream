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
    private var server: ApplicationEngine? = null
    
    /**
     * Callback for UI synchronization feedback
     */
    var onConfigSynced: (() -> Unit)? = null

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
            // Changed port to 8085 to avoid potential conflicts
            server = embeddedServer(CIO, port = 8085, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    gson()
                }
                install(CORS) {
                    anyHost() // Required for local network companion app access
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Get)
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        Log.e("CompanionServer", "Uncaught exception in server", cause)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to cause.message))
                    }
                }
                routing {
                    // Manual static file serving for API 21-25 compatibility
                    // Avoids JarEntry.getLastModifiedTime() crash on older devices
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
                    
                    // Main configuration endpoint
                    post("/api/config") {
                        try {
                            val payload = call.receive<CompanionConfigPayload>()
                            
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
                                "message" to "Error: ${e.message}"
                            ))
                        }
                    }
                }
            }.start(wait = false)
            Log.d("CompanionServer", "Server started successfully on port 8085")
        } catch (e: Exception) {
            Log.e("CompanionServer", "Failed to start server", e)
        }
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
                Log.d("CompanionServer", "Syncing IPTV credentials for: ${iptv.username}")
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
            if (!debrid.mediaFusionUrl.isNullOrBlank()) {
                Log.d("CompanionServer", "Syncing MediaFusion URL: ${debrid.mediaFusionUrl}")
                debridPreferences.saveMediaFusionUrl(debrid.mediaFusionUrl)
            }
        }
    }
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
    val mediaFusionUrl: String? = null
)
